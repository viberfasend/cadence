#!/usr/bin/env bash
#
# Builds the release artefacts — APKs, and the desktop installers this machine can produce — and
# stages them under `dist/` with the names the release links point at.
#
# One script rather than a block of YAML per workflow: android.yml, desktop.yml and release.yml
# were each carrying their own copy of "derive the version, run Gradle, copy the outputs", which
# is how the three drifted apart. It runs the same way on a laptop as on a runner, so a build that
# works here works there — and a release can be cut without spending Actions minutes at all.
#
# Usage: bash .github/scripts/build.sh [target …] [option …]
#
# Targets, defaulting to `all`:
#   apk       assembleDebug + assembleRelease, staged as cadence-{debug,release}.apk
#   deb rpm   Linux packages       (jpackage; needs fakeroot / rpmbuild)
#   dmg msi   macOS / Windows      (jpackage; msi needs the WiX Toolset)
#   tar       the Linux app image as primico-linux-x64.tar.gz
#   desktop   every desktop format above that *this* OS can build
#   all       apk + desktop
#
# A format whose packaging tool is missing is skipped with a note when it was implied by `desktop`
# or `all`, and is a hard error when it was named outright — asking for a .deb and silently
# getting nothing is worse than failing.
#
# Options:
#   --version X.Y.Z      override the derived version (also sets the versionCode)
#   --skip-tests         don't run the unit tests first (CI runs them in their own job)
#   --skip-signing-check don't verify the APK signing certificate (see check-signing.sh)
#   --keystore FILE      sign the release APK with this keystore instead of the debug key;
#                        needs CADENCE_KEYSTORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD too
#
# Environment the build reads besides those: CADENCE_NEON_DATA_API_URL and CADENCE_NEON_AUTH_URL
# are compiled in as the sync endpoints (docs/self-hosting.md); unset, the build offers no sync.
#   --output DIR         where to stage (default: dist)
#   --clean              wipe the output directory first
#   --dry-run            print the plan and exit
#   -h, --help           this text
set -euo pipefail

readonly SCRIPT_NAME="${0##*/}"

# ── Output ─────────────────────────────────────────────────────────────────────────────
# Colour only when a terminal is watching: a runner log gets escape codes otherwise.
if [[ -t 1 ]]; then
    readonly C_BOLD=$'\033[1m' C_DIM=$'\033[2m' C_RED=$'\033[31m' C_YELLOW=$'\033[33m'
    readonly C_GREEN=$'\033[32m' C_OFF=$'\033[0m'
else
    readonly C_BOLD='' C_DIM='' C_RED='' C_YELLOW='' C_GREEN='' C_OFF=''
fi

step() { printf '\n%s==> %s%s\n' "$C_BOLD" "$*" "$C_OFF"; }
info() { printf '    %s\n' "$*"; }
note() { printf '    %s%s%s\n' "$C_DIM" "$*" "$C_OFF"; }
warn() { printf '%swarning:%s %s\n' "$C_YELLOW" "$C_OFF" "$*" >&2; }
die() {
    printf '%serror:%s %s\n' "$C_RED" "$C_OFF" "$1" >&2
    [[ $# -gt 1 ]] && printf '       %s\n' "${@:2}" >&2
    exit 1
}

usage() {
    sed -n '3,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
    exit "${1:-0}"
}

# ── Where we are ───────────────────────────────────────────────────────────────────────
repo_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel 2>/dev/null ||
    (cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd))"
cd "$repo_root"

case "$(uname -s)" in
    Linux) host_os=linux ;;
    Darwin) host_os=macos ;;
    MINGW* | MSYS* | CYGWIN*) host_os=windows ;;
    *) die "unsupported operating system: $(uname -s)" ;;
esac

# ── Arguments ──────────────────────────────────────────────────────────────────────────
targets=()
version_override=''
output_dir='dist'
run_tests=true
check_signing=true
clean_output=false
dry_run=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        apk | deb | rpm | dmg | msi | tar | desktop | all) targets+=("$1") ;;
        --version)
            version_override="${2:?--version needs a value}"
            shift
            ;;
        --version=*) version_override="${1#*=}" ;;
        --keystore)
            CADENCE_KEYSTORE="${2:?--keystore needs a path}"
            shift
            ;;
        --keystore=*) CADENCE_KEYSTORE="${1#*=}" ;;
        --output)
            output_dir="${2:?--output needs a path}"
            shift
            ;;
        --output=*) output_dir="${1#*=}" ;;
        --skip-tests) run_tests=false ;;
        --skip-signing-check) check_signing=false ;;
        --clean) clean_output=true ;;
        --dry-run) dry_run=true ;;
        -h | --help) usage 0 ;;
        *) die "unknown argument: $1" "run '$SCRIPT_NAME --help' for the accepted targets." ;;
    esac
    shift
done
if [[ ${#targets[@]} -eq 0 ]]; then targets=(all); fi

# Which desktop formats jpackage can even attempt here — it shells out to the host's own packaging
# tool for each one, so there is no cross-compiling a .dmg from Linux (ADR 0001 §8).
case "$host_os" in
    linux) native_formats=(deb rpm tar) ;;
    macos) native_formats=(dmg) ;;
    windows) native_formats=(msi) ;;
esac

# Expand the aliases, remembering which formats were asked for by name: those fail on a missing
# tool, while the ones an alias pulled in are skipped with a note.
wanted=()
explicit=()
for target in "${targets[@]}"; do
    case "$target" in
        all)
            wanted+=(apk "${native_formats[@]}")
            ;;
        desktop)
            wanted+=("${native_formats[@]}")
            ;;
        *)
            wanted+=("$target")
            explicit+=("$target")
            ;;
    esac
done

wants() { [[ " ${wanted[*]} " == *" $1 "* ]]; }
named() { [[ " ${explicit[*]-} " == *" $1 "* ]]; }

for format in deb rpm dmg msi tar; do
    if wants "$format" && [[ " ${native_formats[*]} " != *" $format "* ]] && named "$format"; then
        die "$format cannot be built on $host_os — jpackage runs on the target OS."
    fi
done

# ── Preflight ──────────────────────────────────────────────────────────────────────────
#
# Every check that can fail the build is made here, before Gradle spends five minutes getting to
# the same conclusion. A missing tool for an implied format demotes that format instead.
drop() {
    local format="$1" reason="$2" hint="${3:-}"
    if named "$format"; then
        if [[ -n "$hint" ]]; then
            die "cannot build $format: $reason" "$hint"
        fi
        die "cannot build $format: $reason"
    fi
    warn "skipping $format: $reason${hint:+ ($hint)}"
    local kept=()
    for w in "${wanted[@]}"; do [[ "$w" == "$format" ]] || kept+=("$w"); done
    wanted=("${kept[@]}")
}

step 'Preflight'

[[ -x ./gradlew ]] || chmod +x ./gradlew
command -v java >/dev/null || die "no java on PATH." "JDK 17 or newer is required."

java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ -n "$java_major" && "$java_major" -lt 17 ]]; then
    die "Java $java_major is too old — the build targets 17."
fi
info "java $java_major, $host_os"

if wants apk; then
    sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk_dir" && -f local.properties ]]; then
        sdk_dir="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n1)"
    fi
    if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
        drop apk "no Android SDK found" "set ANDROID_HOME or write sdk.dir into local.properties"
    fi
fi

# jpackage's deb/rpm backends shell out to these directly; a runner image carries neither.
if wants deb && ! command -v fakeroot >/dev/null; then
    drop deb "fakeroot is not installed" "sudo apt-get install -y fakeroot"
fi
if wants rpm && ! command -v rpmbuild >/dev/null; then
    drop rpm "rpmbuild is not installed" "sudo apt-get install -y rpm"
fi
if wants msi && ! command -v candle >/dev/null && ! command -v light >/dev/null; then
    drop msi "the WiX Toolset is not on PATH" "choco install wixtoolset"
fi
if wants dmg && ! command -v hdiutil >/dev/null; then
    drop dmg "hdiutil is missing" "it ships with macOS — is this really a Mac?"
fi

if [[ ${#wanted[@]} -eq 0 ]]; then die "nothing left to build."; fi

# ── Version ────────────────────────────────────────────────────────────────────────────
#
# Derived from the Conventional Commits since the last tag, never edited (see next-version.sh).
# An explicit --version wins; so does an inherited CADENCE_VERSION_NAME, which is how a workflow
# that already ran the version step passes its answer down rather than deriving it twice.
version_code_of() {
    local major minor patch
    IFS=. read -r major minor patch <<<"${1%%-*}"
    printf '%d' "$((10#$major * 10000 + 10#${minor:-0} * 100 + 10#${patch:-0}))"
}

if [[ -n "$version_override" ]]; then
    [[ "$version_override" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
        die "--version must be major.minor.patch, got '$version_override'."
    version="$version_override"
    version_code="$(version_code_of "$version")"
    version_source='--version'
elif [[ -n "${CADENCE_VERSION_NAME:-}" ]]; then
    version="$CADENCE_VERSION_NAME"
    version_code="${CADENCE_VERSION_CODE:-$(version_code_of "$version")}"
    version_source='environment'
else
    derived="$(env -u GITHUB_OUTPUT bash .github/scripts/next-version.sh)"
    version="$(sed -n 's/^version=//p' <<<"$derived" | head -n1)"
    version_code="$(sed -n 's/^version_code=//p' <<<"$derived" | head -n1)"
    # `skip=true` means no commits since the last tag, so there is nothing to bump: build what
    # that tag says rather than refusing, since a local rebuild of the current release is a
    # perfectly ordinary thing to want.
    if [[ -z "$version" ]]; then
        version="$(sed -n 's/^previous_tag=v//p' <<<"$derived" | head -n1)"
        [[ -n "$version" ]] || die "could not derive a version." "pass --version X.Y.Z."
        version_code="$(version_code_of "$version")"
    fi
    version_source='derived from commits'
fi

export CADENCE_VERSION_NAME="$version" CADENCE_VERSION_CODE="$version_code"
info "version $version ($version_code) — $version_source"

# ── The Gradle plan ────────────────────────────────────────────────────────────────────
#
# One invocation for everything, not one per artefact: configuration is the expensive part of a
# Gradle run and repeating it per target is most of the wall clock in a naive script.
gradle_tasks=()
# :core's and :ui's tests are one source set compiled twice — jvmTest for the desktop,
# testDebugUnitTest for Android — so both halves are named whenever an APK is in play, and
# :ui:compileKotlinJvm still runs because compiling is not testing: the module has screens no
# test touches. :app-desktop is a plain JVM module, so its task is `test`. A desktop-only build
# skips the Android half rather than dragging the SDK onto a machine packaging a .dmg, which is
# also what keeps this runnable on a Mac with no Android tooling at all.
#
# The Android half is named per module rather than as a bare `testDebugUnitTest`. Unqualified it
# also matches `:app-android:testDebugUnitTest`, and that module has had no unit tests since
# storage moved into :core — but asking for the task still builds the entire debug variant
# (31 tasks: resource merge, manifest processing, the Compose compilation of every screen) to
# then run nothing. `assembleDebug` below compiles all of it anyway when an APK is wanted, so
# nothing goes unchecked. Give :app-android tests again and this line has to name it.
if $run_tests; then
    gradle_tasks+=(:core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm)
    if wants apk; then gradle_tasks+=(:core:testDebugUnitTest :ui:testDebugUnitTest); fi
fi
if wants apk; then gradle_tasks+=(assembleDebug assembleRelease); fi
if wants deb; then gradle_tasks+=(:app-desktop:packageDeb); fi
if wants rpm; then gradle_tasks+=(:app-desktop:packageRpm); fi
if wants dmg; then gradle_tasks+=(:app-desktop:packageDmg); fi
if wants msi; then gradle_tasks+=(:app-desktop:packageMsi); fi
# The tarball is this script's own doing — Compose Desktop has no tarball format — so the app
# image has to be asked for by name. packageDeb/packageRpm build their own and leave none behind.
if wants tar; then gradle_tasks+=(:app-desktop:createDistributable); fi

# The build cache is on in gradle.properties now, everywhere — a CI run that recompiles four
# Compose modules from scratch costs minutes, which is worth more than the Actions cache entry it
# saves — so there is no --build-cache to add here any more.
gradle_args=(--console=plain --stacktrace)

if $dry_run; then
    step 'Plan'
    info "targets:  ${wanted[*]}"
    info "version:  $version ($version_code)"
    info "gradle:   ./gradlew ${gradle_tasks[*]} ${gradle_args[*]}"
    info "output:   $output_dir/"
    exit 0
fi

# ── Build ──────────────────────────────────────────────────────────────────────────────
step "Building: ${wanted[*]}"
if [[ -n "${CADENCE_KEYSTORE:-}" ]]; then
    [[ -f "$CADENCE_KEYSTORE" ]] || die "keystore not found: $CADENCE_KEYSTORE"
    export CADENCE_KEYSTORE
    info "signing the release APK with $CADENCE_KEYSTORE"
elif wants apk; then
    note 'no CADENCE_KEYSTORE set — the release APK falls back to the committed debug key.'
fi

./gradlew "${gradle_tasks[@]}" "${gradle_args[@]}"

# ── Stage ──────────────────────────────────────────────────────────────────────────────
#
# The APKs get fixed names because the release download URLs point at them; the installers keep
# whatever jpackage called them, which already carries the version.
step "Staging into $output_dir/"
if $clean_output; then rm -rf "${output_dir:?}"; fi
mkdir -p "$output_dir"

staged=()
stage() {
    local src="$1" name="${2:-$(basename "$1")}"
    cp -f "$src" "$output_dir/$name"
    staged+=("$name")
}

binaries='app-desktop/build/compose/binaries/main'

if wants apk; then
    stage "$(find app-android/build/outputs/apk/debug -name '*.apk' | head -n1)" primico-debug.apk
    stage "$(find app-android/build/outputs/apk/release -name '*.apk' | head -n1)" primico-release.apk
fi
for format in deb rpm dmg msi; do
    wants "$format" || continue
    found=false
    for file in "$binaries/$format"/*."$format"; do
        [[ -e "$file" ]] || continue
        stage "$file"
        found=true
    done
    if ! $found; then die "$format was built but nothing landed in $binaries/$format."; fi
done
if wants tar; then
    tar -C "$binaries/app" -czf "$output_dir/primico-linux-x64.tar.gz" .
    staged+=(primico-linux-x64.tar.gz)
fi

# ── Verify ─────────────────────────────────────────────────────────────────────────────
#
# A build signed by a different key than the last release cannot be installed over it — the phone
# only says "App not installed", weeks after the build went green. See check-signing.sh.
if wants apk && $check_signing; then
    step 'Checking the signing certificate'
    bash .github/scripts/check-signing.sh "$output_dir/primico-debug.apk" "$output_dir/primico-release.apk"
fi

# ── Summary ────────────────────────────────────────────────────────────────────────────
step "Primico $version"
for name in "${staged[@]}"; do
    printf '    %-40s %8s\n' "$name" "$(du -h "$output_dir/$name" | cut -f1)"
done

# Everything needed to publish this build by hand, which is the point of running it here: the
# assets are already the ones the release notes link to.
printf '\n%sTo publish these:%s\n' "$C_GREEN" "$C_OFF"
printf '    gh release create v%s %s/* --title "Primico %s" --generate-notes\n' \
    "$version" "$output_dir" "$version"
