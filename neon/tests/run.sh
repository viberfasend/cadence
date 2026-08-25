#!/usr/bin/env bash
# Applies neon/migrations/*.sql to a throwaway Postgres and checks what the schema promises the
# sync protocol. This is the only test the server half has: no Gradle task compiles this SQL, and
# the parts that go wrong quietly — a policy that makes a DELETE match nothing, a trigger that
# stops stamping the column the pull cursor reads — fail silently in production rather than
# loudly here.
#
#   bash neon/tests/run.sh          # needs docker, nothing else
#
# The container is removed on the way out, pass or fail.
set -euo pipefail

readonly IMAGE="${CADENCE_PG_IMAGE:-postgres:16}"
readonly CONTAINER="cadence-migration-test-$$"
readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly MIGRATIONS="$HERE/../migrations"
readonly OWNER_URL="postgresql://owner_role:pg@127.0.0.1/postgres"

# The uuids the seed writes rows for.
readonly USER_A="11111111-1111-1111-1111-111111111111"
readonly USER_B="22222222-2222-2222-2222-222222222222"

failures=0

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

psql_as() {
    local role="$1"; shift
    docker exec -i "$CONTAINER" psql -U "$role" -d postgres -v ON_ERROR_STOP=1 -At "$@"
}

# expect <what> <expected> <sql>, run as the superuser (fixture reads and cleanup).
expect() {
    local what="$1" want="$2" sql="$3" got
    got="$(psql_as postgres -c "$sql" 2>&1 || true)"
    if [[ "$got" == "$want" ]]; then
        echo "  ok    $what"
    else
        echo "  FAIL  $what: expected '$want', got '$got'"
        failures=$((failures + 1))
    fi
}

# expect_user <what> <expected> <user-uuid> <sql> — run the sql the way the Data API would: as
# `authenticated`, with the stubbed auth.user_id() answering the given uuid. psql prints a tag for
# every command, so the two SETs are filtered back out of the comparison.
expect_user() {
    local what="$1" want="$2" uid="$3" sql="$4" got
    got="$(psql_as postgres -c "set role authenticated; set cadence.test_user_id to '$uid'; $sql" 2>&1 | grep -v '^SET$' || true)"
    if [[ "$got" == "$want" ]]; then
        echo "  ok    $what"
    else
        echo "  FAIL  $what: expected '$want', got '$got'"
        failures=$((failures + 1))
    fi
}

# expect_error <what> <fragment> <sql> [db]
expect_error() {
    local what="$1" fragment="$2" sql="$3" db="${4:-postgres}" got
    got="$(docker exec -i "$CONTAINER" psql -U postgres -d "$db" -v ON_ERROR_STOP=1 -At -c "$sql" 2>&1 || true)"
    if [[ "$got" == *"$fragment"* ]]; then
        echo "  ok    $what"
    else
        echo "  FAIL  $what: expected an error containing '$fragment', got '$got'"
        failures=$((failures + 1))
    fi
}

echo "starting $IMAGE"
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=pg "$IMAGE" >/dev/null
for _ in $(seq 60); do
    docker exec "$CONTAINER" pg_isready -q 2>/dev/null && break
    sleep 1
done
docker exec "$CONTAINER" pg_isready >/dev/null

docker cp "$HERE/stub.sql" "$CONTAINER:/tmp/stub.sql" >/dev/null
docker cp "$HERE/seed.sql" "$CONTAINER:/tmp/seed.sql" >/dev/null
docker cp "$MIGRATIONS" "$CONTAINER:/tmp/migrations" >/dev/null
docker cp "$HERE/../migrate.sh" "$CONTAINER:/tmp/migrate.sh" >/dev/null
docker cp "$HERE/../db.sh" "$CONTAINER:/tmp/db.sh" >/dev/null
psql_as postgres -f /tmp/stub.sql >/dev/null

# First application goes through migrate.sh itself — the script is part of what is under test
# (the bookkeeping table, the migration+record transaction).
echo "applying migrations via migrate.sh"
docker exec -e CADENCE_NEON_DB_URL="$OWNER_URL" "$CONTAINER" bash /tmp/migrate.sh

# Applying the files directly a second time is not a nicety: they get pasted into the SQL editor
# of a project that already carries half of them, so each file has to be idempotent on its own,
# without the bookkeeping table's protection.
echo "applying migrations again, directly (idempotence)"
for migration in "$MIGRATIONS"/*.sql; do
    name="$(basename "$migration")"
    echo "  $name"
    docker exec "$CONTAINER" psql "$OWNER_URL" -v ON_ERROR_STOP=1 -q -f "/tmp/migrations/$name"
done

# And a second migrate.sh run must skip everything by name.
echo "re-running migrate.sh (must skip)"
rerun="$(docker exec -e CADENCE_NEON_DB_URL="$OWNER_URL" "$CONTAINER" bash /tmp/migrate.sh)"
if [[ "$rerun" == *"  apply"* ]]; then
    echo "  FAIL  re-run applied something: $rerun"
    failures=$((failures + 1))
else
    echo "  ok    re-run skipped every migration"
fi

# The precondition guard: on a database without the Data API's provisioning (no auth.user_id(),
# no roles), the baseline must refuse with a sentence, not die mid-file.
psql_as postgres -c "create database bare" >/dev/null
guard="$(docker exec -i "$CONTAINER" psql -U postgres -d bare -v ON_ERROR_STOP=1 -f /tmp/migrations/0001_cadence_sync.sql 2>&1 || true)"
if [[ "$guard" == *"enable the Data API"* ]]; then
    echo "  ok    a project without the Data API is refused with a clear message"
else
    echo "  FAIL  guard: expected 'enable the Data API', got '$guard'"
    failures=$((failures + 1))
fi

psql_as postgres -f /tmp/seed.sql >/dev/null

echo "checks"

# ── What the Data API roles can and cannot do ─────────────────────────────────────

expect_user "user_id fills itself from the JWT" \
    "INSERT 0 1" "$USER_A" \
    "insert into public.tasks (id, title, priority, created_at, sort_order, updated_at)
     values ('00000000-0000-0000-0000-000000000008', 'defaulted', 1, now(), 0, now())"
expect "…with the signed-in account's uuid" \
    "$USER_A" \
    "select user_id from public.tasks where id = '00000000-0000-0000-0000-000000000008'"

expect_user "the server's clock overwrites whatever server_updated_at the client sends" \
    "INSERT 0 1" "$USER_A" \
    "insert into public.tasks (id, title, priority, created_at, sort_order, updated_at, server_updated_at)
     values ('00000000-0000-0000-0000-000000000009', 'clocked', 1, now(), 0, now(), '2000-01-01')"
expect "…so the stored row carries a fresh stamp" \
    "t" \
    "select server_updated_at > now() - interval '1 minute'
     from public.tasks where id = '00000000-0000-0000-0000-000000000009'"

expect_user "a stale write is skipped, not an error" \
    "UPDATE 0" "$USER_A" \
    "update public.tasks set title = 'hijacked', updated_at = '2000-01-01'
     where id = '00000000-0000-0000-0000-000000000001'"
expect "…and the row keeps its title" \
    "live" \
    "select title from public.tasks where id = '00000000-0000-0000-0000-000000000001'"
expect_user "a fresh write lands" \
    "UPDATE 1" "$USER_A" \
    "update public.tasks set updated_at = now()
     where id = '00000000-0000-0000-0000-000000000001'"

# The stale row echoes an id that already exists; the trigger drops it *before* the primary key
# is ever checked, which is why the batch neither errors nor shrinks to zero.
expect_user "one stale row does not poison a batch" \
    "INSERT 0 1" "$USER_A" \
    "insert into public.tasks (id, title, priority, created_at, sort_order, updated_at)
     values ('00000000-0000-0000-0000-000000000001', 'stale echo', 1, now(), 0, '2000-01-01'),
            ('00000000-0000-0000-0000-00000000000f', 'fresh in batch', 1, now(), 0, now())"

expect_user "another account sees only its own rows" \
    "2" "$USER_B" \
    "select count(*) from public.tasks"
expect_user "another account cannot touch this one's rows" \
    "UPDATE 0" "$USER_B" \
    "update public.tasks set updated_at = now()
     where id = '00000000-0000-0000-0000-000000000001'"
expect_user "another account cannot collect this one's tombstones" \
    "DELETE 0" "$USER_B" \
    "delete from public.tasks
     where id = '00000000-0000-0000-0000-000000000003'"
expect_error "signed out, nothing is readable" \
    "permission denied" "set role anonymous; select count(*) from public.tasks"
expect_error "the bookkeeping table is not in the Data API" \
    "permission denied" "set role authenticated; select count(*) from public.schema_migrations"
expect_error "…and cannot be written either" \
    "permission denied" \
    "set role authenticated; insert into public.schema_migrations (filename) values ('forged.sql')"
expect "RLS is on the bookkeeping table too, so a returned grant still denies" "t" \
    "select relrowsecurity from pg_class where oid = 'public.schema_migrations'::regclass"

# The landmine 0002 defuses: with Neon's default privileges in place, a table created after this
# point would otherwise be granted to `authenticated` the moment it exists.
psql_as owner_role -c "create table if not exists public.later_table (id int)" >/dev/null
expect "a table added later is not granted away by default" "f" \
    "select has_table_privilege('authenticated', 'public.later_table', 'select')"
psql_as postgres -c "drop table if exists public.later_table" >/dev/null

# Cleanup of the rows the checks above created, so the sweep fixtures below are exactly the seed.
psql_as postgres -c "delete from public.tasks where title in ('defaulted', 'clocked', 'fresh in batch')" >/dev/null

# ── The client-driven sweep ───────────────────────────────────────────────────────
#
# The exact statement CadenceSyncEngine issues through the Data API after a push, per table:
# tombstones only, past the 90-day horizon, by the server's clock. RLS scopes it to the account;
# the horizon filter is the client's own discipline.

expect_user "the sweep collects this account's aged task tombstone" \
    "DELETE 1" "$USER_A" \
    "delete from public.tasks
     where deleted_at is not null and server_updated_at < now() - interval '90 days'"
expect_user "…and the aged project tombstone" \
    "DELETE 1" "$USER_A" \
    "delete from public.projects
     where deleted_at is not null and server_updated_at < now() - interval '90 days'"
expect_user "…and the aged section tombstone" \
    "DELETE 1" "$USER_A" \
    "delete from public.sections
     where deleted_at is not null and server_updated_at < now() - interval '90 days'"
expect_user "…and the aged tag tombstone" \
    "DELETE 1" "$USER_A" \
    "delete from public.tags
     where deleted_at is not null and server_updated_at < now() - interval '90 days'"
expect_user "a second sweep collects nothing" \
    "DELETE 0" "$USER_A" \
    "delete from public.tasks
     where deleted_at is not null and server_updated_at < now() - interval '90 days'"

survivors="$(psql_as postgres -c "select string_agg(title, ', ' order by id) from public.tasks")"
if [[ "$survivors" == "live, fresh tombstone, other account, stale tombstone, other account, old and live" ]]; then
    echo "  ok    live rows, the fresh tombstone and the other account survive"
else
    echo "  FAIL  survivors: got '$survivors'"
    failures=$((failures + 1))
fi

expect "the live project survives" "live" \
    "select string_agg(name, ', ' order by id) from public.projects"
expect "the live section survives" "live" \
    "select string_agg(name, ', ' order by id) from public.sections"
expect "the live tag survives" "live" \
    "select string_agg(name, ', ' order by id) from public.tags"

# The trade the packed column makes, asserted rather than assumed: sweeping a tag does not touch
# the tasks that wore it, so the live task still names both ids and the client is what drops the
# one nothing answers to. If this ever starts returning 1, something has begun rewriting tasks
# server-side and the "one row per delete" property is gone.
expect "a swept tag leaves its id on the task, for the client to drop" "2" \
    "select cardinality(tag_ids) from public.tasks
     where id = '00000000-0000-0000-0000-000000000001'"

# ── neon/db.sh, the maintenance CLI ────────────────────────────────────────────────
#
# Run as `postgres` here rather than `owner_role`: on Neon the CLI connects as `neondb_owner`,
# which carries BYPASSRLS, and reaching *every* account's rows is the whole reason the CLI exists
# beside the client's own per-account sweep. `owner_role` is deliberately nobypassrls, so it
# would see nothing and the checks below would pass while proving nothing.
readonly CLI_URL="postgresql://postgres:pg@127.0.0.1/postgres"

echo "neon/db.sh"

# What is left at this point: user B's aged tombstone (task 4), which user A's sweep above could
# not touch — exactly the row a client can never collect.
cli_out="$(docker exec -e CADENCE_NEON_DB_URL="$CLI_URL" "$CONTAINER" bash /tmp/db.sh sweep 2>&1)"
if [[ "$cli_out" == *"Nothing was deleted"* ]]; then
    echo "  ok    a bare sweep is a dry run"
else
    echo "  FAIL  dry run: got '$cli_out'"
    failures=$((failures + 1))
fi
# Four, not five: user A's own sweep above already took its aged tombstone.
expect "…and it really deleted nothing" "4" "select count(*) from public.tasks"

cli_out="$(docker exec -e CADENCE_NEON_DB_URL="$CLI_URL" "$CONTAINER" bash /tmp/db.sh sweep --horizon 1 2>&1 || true)"
if [[ "$cli_out" == *"Refusing a horizon of 1 days"* ]]; then
    echo "  ok    a horizon below the clients' floor is refused"
else
    echo "  FAIL  horizon floor: got '$cli_out'"
    failures=$((failures + 1))
fi

docker exec -e CADENCE_NEON_DB_URL="$CLI_URL" "$CONTAINER" bash /tmp/db.sh sweep --yes >/dev/null
expect "the CLI collects the other account's aged tombstone, which no client could" \
    "live, fresh tombstone, other account, old and live" \
    "select string_agg(title, ', ' order by id) from public.tasks"

docker exec -e CADENCE_NEON_DB_URL="$CLI_URL" "$CONTAINER" bash /tmp/db.sh status >/dev/null
echo "  ok    status runs"
docker exec -e CADENCE_NEON_DB_URL="$CLI_URL" "$CONTAINER" bash /tmp/db.sh vacuum >/dev/null
echo "  ok    vacuum runs"

if (( failures > 0 )); then
    echo "$failures check(s) failed"
    exit 1
fi
echo "all checks passed"
