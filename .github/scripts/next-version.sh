#!/usr/bin/env bash
#
# Derives the next semantic version from the Conventional Commits made since the last v* tag,
# and renders the release notes that go with it.
#
# Bump rules, highest wins:
#   major — a `!` before the colon (`feat!:`, `fix(ui)!:`) or a `BREAKING CHANGE:` footer
#   minor — any `feat:`
#   patch — anything else, so a push that only fixes CI still ships a build
#
# With no v* tag in the repository yet the first release is 1.0.0 rather than a bump of 0.0.0,
# matching the versionName the app has carried since its first build.
#
# Outputs (to $GITHUB_OUTPUT when set, otherwise stdout, so this is runnable locally):
#   skip, version, tag, version_code, bump, previous_tag, notes
set -euo pipefail

previous_tag="$(git tag --list 'v[0-9]*' --sort=-v:refname | head -n1)"

if [[ -z "$previous_tag" ]]; then
    version="1.0.0"
    bump="initial"
    range=""
else
    range="${previous_tag}..HEAD"
    if [[ -z "$(git log --format=%H "$range")" ]]; then
        echo "No commits since ${previous_tag} — nothing to release." >&2
        {
            echo "skip=true"
            echo "previous_tag=${previous_tag}"
        } >>"${GITHUB_OUTPUT:-/dev/stdout}"
        exit 0
    fi

    subjects="$(git log --format=%s "$range")"
    bodies="$(git log --format=%B "$range")"

    if grep -qE '^[a-zA-Z]+(\([^)]*\))?!:' <<<"$subjects" ||
        grep -qE '^BREAKING[ -]CHANGE:' <<<"$bodies"; then
        bump="major"
    elif grep -qE '^feat(\([^)]*\))?:' <<<"$subjects"; then
        bump="minor"
    else
        bump="patch"
    fi

    IFS=. read -r major minor patch <<<"${previous_tag#v}"
    case "$bump" in
        major) version="$((major + 1)).0.0" ;;
        minor) version="${major}.$((minor + 1)).0" ;;
        patch) version="${major}.${minor}.$((patch + 1))" ;;
    esac
fi

IFS=. read -r major minor patch <<<"$version"
# Monotonic as long as minor and patch stay below 100, which the bump rules guarantee.
version_code="$((major * 10000 + minor * 100 + patch))"

# ── Release notes ──────────────────────────────────────────────────────────────────────
section() {
    local title="$1" pattern="$2" lines
    lines="$(git log --format='%s|%h' ${range:+"$range"} | grep -E "$pattern" || true)"
    if [[ -z "$lines" ]]; then
        return 0
    fi
    printf '### %s\n\n' "$title"
    while IFS='|' read -r subject sha; do
        # Drop the conventional prefix; the section heading already says what kind it is.
        printf -- '- %s (%s)\n' "${subject#*: }" "$sha"
    done <<<"$lines"
    printf '\n'
}

notes="$(
    section "Features" '^feat(\([^)]*\))?!?:'
    section "Fixes" '^(fix|perf)(\([^)]*\))?!?:'
    section "Other" '^(build|chore|ci|docs|refactor|revert|style|test)(\([^)]*\))?!?:'
    if [[ -n "$previous_tag" ]]; then
        printf 'Changes since %s.\n' "$previous_tag"
    fi
)"

{
    echo "skip=false"
    echo "version=${version}"
    echo "tag=v${version}"
    echo "version_code=${version_code}"
    echo "bump=${bump}"
    echo "previous_tag=${previous_tag}"
    echo "notes<<NOTES_EOF"
    echo "$notes"
    echo "NOTES_EOF"
} >>"${GITHUB_OUTPUT:-/dev/stdout}"
