#!/usr/bin/env bash
# Applies supabase/migrations/*.sql to a throwaway Postgres and checks what the tombstone sweep
# does. This is the only test the server half has: no Gradle task compiles this SQL, and the parts
# that go wrong quietly — a policy that makes a DELETE match nothing, a trigger that overwrites the
# column the sweep reads — fail silently in production rather than loudly here.
#
#   bash supabase/tests/run.sh          # needs docker, nothing else
#
# The container is removed on the way out, pass or fail.
set -euo pipefail

readonly IMAGE="${CADENCE_PG_IMAGE:-postgres:16}"
readonly CONTAINER="cadence-migration-test-$$"
readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly MIGRATIONS="$HERE/../migrations"

failures=0

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

psql_as() {
    local role="$1"; shift
    docker exec -i "$CONTAINER" psql -U "$role" -d postgres -v ON_ERROR_STOP=1 -At "$@"
}

# expect <what> <expected> <sql>, run as owner_role — the role the cron job runs as.
expect() {
    local what="$1" want="$2" sql="$3" got
    got="$(psql_as owner_role -c "$sql" 2>&1 || true)"
    if [[ "$got" == "$want" ]]; then
        echo "  ok    $what"
    else
        echo "  FAIL  $what: expected '$want', got '$got'"
        failures=$((failures + 1))
    fi
}

# expect_error <what> <fragment> <sql> <role>
expect_error() {
    local what="$1" fragment="$2" sql="$3" role="${4:-owner_role}" got
    got="$(psql_as "$role" -c "$sql" 2>&1 || true)"
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

docker cp "$HERE/stub.sql" "$CONTAINER:/tmp/stub.sql"
docker cp "$HERE/seed.sql" "$CONTAINER:/tmp/seed.sql"
psql_as postgres -f /tmp/stub.sql >/dev/null

echo "applying migrations"
for migration in "$MIGRATIONS"/*.sql; do
    # pg_cron is not in the stock image and cannot be, so its `create extension` is the one line
    # dropped; stub.sql supplies `cron.schedule` and the migration is otherwise applied verbatim.
    grep -v "create extension if not exists pg_cron;" "$migration" > "/tmp/$(basename "$migration")"
    docker cp "/tmp/$(basename "$migration")" "$CONTAINER:/tmp/migration.sql"
    echo "  $(basename "$migration")"
    psql_as owner_role -f /tmp/migration.sql >/dev/null
done

# Applying twice is not a nicety: this file is pasted into SQL editors of projects that already
# carry half of it.
echo "applying migrations again (idempotence)"
for migration in "$MIGRATIONS"/*.sql; do
    grep -v "create extension if not exists pg_cron;" "$migration" > "/tmp/$(basename "$migration")"
    docker cp "/tmp/$(basename "$migration")" "$CONTAINER:/tmp/migration.sql"
    psql_as owner_role -f /tmp/migration.sql >/dev/null
done

psql_as postgres -f /tmp/seed.sql >/dev/null

echo "checks"
expect "the sweep sees tombstones and only tombstones" \
    "3" "select count(*) from public.tasks"
expect "a live row is not deletable by the sweeping role" \
    "DELETE 0" "delete from public.tasks where deleted_at is null"
expect "the sweep collects 2 tasks and 1 project" \
    "2|1" "select * from public.collect_tombstones()"
expect "a second sweep collects nothing" \
    "0|0" "select * from public.collect_tombstones()"
expect_error "a horizon shorter than the clients' is refused" \
    "shorter than the 90 days" "select * from public.collect_tombstones(interval '1 day')"
expect_error "signed-in users cannot run the sweep" \
    "permission denied" "set role authenticated; select * from public.collect_tombstones()" postgres

survivors="$(psql_as postgres -c "select string_agg(title, ', ' order by id) from public.tasks")"
if [[ "$survivors" == "live, fresh tombstone, other account, old and live" ]]; then
    echo "  ok    live rows and the fresh tombstone survive"
else
    echo "  FAIL  survivors: got '$survivors'"
    failures=$((failures + 1))
fi

projects="$(psql_as postgres -c "select string_agg(name, ', ' order by id) from public.projects")"
if [[ "$projects" == "live" ]]; then
    echo "  ok    the live project survives"
else
    echo "  FAIL  projects: got '$projects'"
    failures=$((failures + 1))
fi

if (( failures > 0 )); then
    echo "$failures check(s) failed"
    exit 1
fi
echo "all checks passed"
