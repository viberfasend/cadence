#!/usr/bin/env bash
# Maintenance for the Neon mirror, from a laptop — what the app cannot do for itself.
#
#   CADENCE_NEON_DB_URL='postgresql://...' bash neon/db.sh status
#   CADENCE_NEON_DB_URL='postgresql://...' bash neon/db.sh sweep          # dry run
#   CADENCE_NEON_DB_URL='postgresql://...' bash neon/db.sh sweep --yes
#   CADENCE_NEON_DB_URL='postgresql://...' bash neon/db.sh vacuum
#
# The URL is the direct Postgres connection string from the Neon console, read from the
# environment and never stored here — the same rule `migrate.sh` follows. Needs `psql`.
#
# **Why this exists beside the client's own sweep.** Every signed-in device already collects its
# own tombstones past the horizon after a push, at most daily (ADR 0005, decision 5), and that
# covers the ordinary case. What it cannot cover is anything scoped outside one signed-in
# account: rows belonging to an account that no longer signs in (a throwaway user, an old test
# login), or a mirror nobody has synced with in months. This script connects as the database
# owner, which on Neon carries BYPASSRLS, so it sees every account's rows at once.
#
# **That is also the danger, so it is worth stating plainly**: this credential ignores row-level
# security entirely — the one thing protecting the rows from each other. Hence the shape of
# `sweep`: it prints what it would collect and changes nothing until `--yes`, its predicate can
# only ever match rows that are *already* tombstones, and the horizon has a floor.
set -euo pipefail

readonly DEFAULT_HORIZON_DAYS=90
readonly TABLES=(tasks projects sections tags)

usage() {
    cat <<'USAGE'
Usage: bash neon/db.sh <command> [options]

Commands:
  status                What the mirror holds: live rows, tombstones, how many are collectable,
                        and how the rows split across accounts.
  sweep [options]       Delete tombstones past the horizon. Prints what it would take and exits
                        unless --yes is given.
    --yes               Actually delete.
    --horizon <days>    How old a tombstone must be. Default 90.
    --force             Allow a horizon below 90 days. See the warning it prints.
  vacuum                VACUUM ANALYZE the four tables, to hand the freed space back and refresh
                        the planner's statistics. Worth running after a large sweep.

Environment:
  CADENCE_NEON_DB_URL   Direct Postgres connection string from the Neon console. Required.
USAGE
}

require_url() {
    if [[ -z "${CADENCE_NEON_DB_URL:-}" ]]; then
        echo "CADENCE_NEON_DB_URL is not set." >&2
        echo "Copy the connection string from the Neon console and re-run:" >&2
        echo "  CADENCE_NEON_DB_URL='postgresql://...' bash neon/db.sh $*" >&2
        exit 1
    fi
}

# Aligned output, for the eye.
psql_pretty() { psql "$CADENCE_NEON_DB_URL" -v ON_ERROR_STOP=1 -q "$@"; }
# One value, for the script.
psql_value() { psql "$CADENCE_NEON_DB_URL" -v ON_ERROR_STOP=1 -qAt "$@"; }

# The one definition of "safe to remove", written once so the dry run and the delete can never
# drift apart. Two halves, both load-bearing:
#
#   deleted_at is not null      — only ever a tombstone. A live row is unreachable from here.
#   server_updated_at < cutoff  — by the *server's* clock, not `deleted_at`, which is whichever
#                                 device did the deleting. A device with a wrong clock must not
#                                 be able to age a tombstone out from under another device that
#                                 has not pulled it yet. Same rule the client sweep follows.
tombstone_predicate() {
    local days="$1"
    echo "deleted_at is not null and server_updated_at < now() - make_interval(days => $days)"
}

cmd_status() {
    require_url status
    local pred; pred="$(tombstone_predicate "$DEFAULT_HORIZON_DAYS")"
    local parts=()
    for table in "${TABLES[@]}"; do
        parts+=("select '$table' as \"table\",
            count(*) filter (where deleted_at is null) as live,
            count(*) filter (where deleted_at is not null) as tombstones,
            count(*) filter (where $pred) as collectable,
            coalesce(to_char(min(server_updated_at) filter (where deleted_at is not null),
                             'YYYY-MM-DD'), '-') as \"oldest tombstone\"
        from public.$table")
    done
    local union; union="$(printf '%s union all ' "${parts[@]}")"
    union="${union% union all }"

    echo "Rows (collectable = tombstone older than $DEFAULT_HORIZON_DAYS days)"
    psql_pretty -c "$union"

    # Which accounts the rows belong to. Only visible from here — a signed-in client sees its
    # own rows and nothing else — and it is what tells a forgotten test login apart from you.
    echo "Rows per account"
    local per_account=()
    for table in "${TABLES[@]}"; do
        per_account+=("select user_id from public.$table")
    done
    local accounts; accounts="$(printf '%s union all ' "${per_account[@]}")"
    accounts="${accounts% union all }"
    psql_pretty -c "select user_id as account, count(*) as rows
                    from ($accounts) all_rows group by 1 order by 2 desc"
}

cmd_sweep() {
    local apply=false horizon="$DEFAULT_HORIZON_DAYS" force=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --yes) apply=true; shift ;;
            --force) force=true; shift ;;
            --horizon)
                horizon="${2:-}"
                if [[ ! "$horizon" =~ ^[0-9]+$ ]] || (( horizon < 1 )); then
                    echo "--horizon takes a positive number of days, got '${2:-}'" >&2
                    exit 1
                fi
                shift 2 ;;
            *) echo "unknown option for sweep: $1" >&2; usage >&2; exit 1 ;;
        esac
    done

    # The floor is not a nicety. A device that has been offline longer than the horizon has never
    # seen these deletions; when it finally syncs it finds rows the mirror no longer mentions and
    # pushes them all back. 90 days is what every client assumes (ADR 0002), so collecting sooner
    # means resurrected tasks on the next sync from a laptop that spent a summer in a drawer.
    if (( horizon < DEFAULT_HORIZON_DAYS )) && [[ "$force" != true ]]; then
        echo "Refusing a horizon of $horizon days: the clients assume $DEFAULT_HORIZON_DAYS." >&2
        echo "A device offline longer than the horizon re-inserts what the others deleted." >&2
        echo "Pass --force if every device has synced more recently than that." >&2
        exit 1
    fi
    if (( horizon < DEFAULT_HORIZON_DAYS )); then
        echo "WARNING: sweeping at $horizon days, below the $DEFAULT_HORIZON_DAYS every client"
        echo "         assumes. Any device that has not synced within $horizon days will put"
        echo "         these rows back on its next round."
    fi

    require_url sweep
    local pred; pred="$(tombstone_predicate "$horizon")"

    if [[ "$apply" != true ]]; then
        local counts=()
        for table in "${TABLES[@]}"; do
            counts+=("(select count(*) from public.$table where $pred) as $table")
        done
        echo "Would collect (tombstones older than $horizon days):"
        psql_pretty -c "select $(printf '%s, ' "${counts[@]}" | sed 's/, $//')"
        echo "Nothing was deleted. Re-run with --yes to apply."
        return
    fi

    # All four deletes in one statement, so the sweep is atomic and its counts are the counts of
    # what this run actually took — no window in which another writer changes the answer between
    # a count and a delete.
    local ctes=() selects=()
    for table in "${TABLES[@]}"; do
        ctes+=("${table}_gone as (delete from public.$table where $pred returning 1)")
        selects+=("(select count(*) from ${table}_gone) as $table")
    done
    echo "Collecting tombstones older than $horizon days:"
    psql_pretty -c "with $(printf '%s, ' "${ctes[@]}" | sed 's/, $//')
                    select $(printf '%s, ' "${selects[@]}" | sed 's/, $//')"
    echo "Done. Run 'bash neon/db.sh vacuum' to hand the space back."
}

cmd_vacuum() {
    require_url vacuum
    for table in "${TABLES[@]}"; do
        echo "  vacuum public.$table"
        # Not inside a transaction, which is why this is one statement per table rather than the
        # single-statement shape `sweep` uses: VACUUM cannot run in a transaction block.
        psql_value -c "vacuum (analyze) public.$table" >/dev/null
    done
    echo "Done."
}

case "${1:-}" in
    status) shift; cmd_status "$@" ;;
    sweep) shift; cmd_sweep "$@" ;;
    vacuum) shift; cmd_vacuum "$@" ;;
    -h|--help|help) usage ;;
    "") usage >&2; exit 1 ;;
    *) echo "unknown command: $1" >&2; usage >&2; exit 1 ;;
esac
