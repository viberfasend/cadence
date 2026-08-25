#!/usr/bin/env bash
# Applies neon/migrations/*.sql to the Neon project, in filename order, each exactly once.
#
#   CADENCE_NEON_DB_URL='postgresql://...' bash neon/migrate.sh
#
# The URL is the *direct* Postgres connection string from the Neon console (it never ships in the
# app; the app itself only ever speaks to the Data API). Needs `psql` and nothing else; without a
# local psql, `docker run --rm -i postgres:16 psql "$CADENCE_NEON_DB_URL" ...` speaks to Neon just
# as well.
#
# Bookkeeping is one table, `public.schema_migrations`, keyed by filename — deliberately not the
# Supabase CLI's server-clock version stamps, which is the mismatch that made `db push` refuse
# (see the retired CLAUDE.md section). A file is applied inside one transaction together with the
# row that records it, so a failed migration leaves neither. Re-running the script is always safe:
# applied files are skipped by name, and the files themselves are idempotent besides (they also
# get pasted into SQL editors).
set -euo pipefail

if [[ -z "${CADENCE_NEON_DB_URL:-}" ]]; then
    echo "CADENCE_NEON_DB_URL is not set." >&2
    echo "Copy the direct (non-pooled) connection string from the Neon console and re-run:" >&2
    echo "  CADENCE_NEON_DB_URL='postgresql://...' bash neon/migrate.sh" >&2
    exit 1
fi

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly MIGRATIONS="$HERE/migrations"

run_psql() {
    psql "$CADENCE_NEON_DB_URL" -v ON_ERROR_STOP=1 -q "$@"
}

# The bookkeeping table, locked down in the same breath that creates it.
#
# Enabling the Data API leaves a default-privilege entry granting `authenticated` every new table
# the owner creates in `public`, so a bare `create table` here hands the migration history to any
# signed-in account over HTTP — read *and* write (see 0002_lock_bookkeeping.sql, which repairs
# projects created before this). Doing it here as well as there means a fresh project is never
# exposed, not even between this statement and that migration.
run_psql -c "create table if not exists public.schema_migrations (
    filename text primary key,
    applied_at timestamptz not null default now()
);" \
    -c "do \$\$
        begin
            revoke all on public.schema_migrations from public;
            if exists (select 1 from pg_roles where rolname = 'authenticated') then
                revoke all on public.schema_migrations from authenticated;
            end if;
            if exists (select 1 from pg_roles where rolname = 'anonymous') then
                revoke all on public.schema_migrations from anonymous;
            end if;
        end
        \$\$;" \
    -c "alter table public.schema_migrations enable row level security;"

for migration in "$MIGRATIONS"/*.sql; do
    name="$(basename "$migration")"
    applied="$(run_psql -At -c "select 1 from public.schema_migrations where filename = '$name'")"
    if [[ "$applied" == "1" ]]; then
        echo "  skip  $name (already applied)"
        continue
    fi
    echo "  apply $name"
    # -f then -c inside --single-transaction: the migration and the row recording it commit
    # together or not at all.
    run_psql --single-transaction \
        -f "$migration" \
        -c "insert into public.schema_migrations (filename) values ('$name');"
done

echo "migrations up to date"
