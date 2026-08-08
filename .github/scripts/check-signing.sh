#!/usr/bin/env bash
#
# Asserts that the debug APK was signed with the debug key committed to the repository.
#
# An APK only installs over an already-installed app when both carry the same signing
# certificate; otherwise the installer refuses with "App not installed". The key therefore has
# to survive from one release to the next, and the failure is invisible at build time — the
# build succeeds, and the update breaks on the phone weeks later. This turns that into a red CI
# run instead: it compares the certificate inside the built APK with the one in
# app-android/debug.keystore, and prints both fingerprints so a mismatch is readable.
#
# Usage: bash .github/scripts/check-signing.sh <apk> [<apk> …]
set -euo pipefail

keystore="app-android/debug.keystore"

# The APK is signed by whichever key the build picked; the keystore is the one it should have
# picked. Both fingerprints are normalised to bare lowercase hex, since keytool prints
# colon-separated uppercase and apksigner prints neither.
#
# The digest is read as the line's last field rather than by splitting on the colon: the two
# tools label it differently ("SHA256:" vs "certificate SHA-256 digest:"), and apksigner's label
# is itself preceded by a colon on some versions, which put half the label into the fingerprint.
# The last whitespace-separated field is the hex in both.
fingerprint() { awk '{ print $NF }' | tr -d ': ' | tr '[:upper:]' '[:lower:]'; }

apksigner="$(find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools" -name apksigner -type f 2>/dev/null |
    sort -V | tail -n1)"
if [[ -z "$apksigner" ]]; then
    echo "apksigner not found under the Android SDK build-tools." >&2
    exit 1
fi

expected="$(keytool -list -v -keystore "$keystore" -storepass android -alias androiddebugkey |
    grep -m1 'SHA256:' | fingerprint)"
echo "Committed debug key: $expected"

status=0
for apk in "$@"; do
    actual="$("$apksigner" verify --print-certs "$apk" |
        grep -m1 -i 'certificate SHA-256 digest' | fingerprint)"

    if [[ "$actual" == "$expected" ]]; then
        echo "  ✓ $(basename "$apk") — $actual"
    else
        # Not necessarily a bug for the release APK: signing it with the CADENCE_* secrets is
        # exactly what those secrets are for. Only the debug APK must match.
        echo "  ✗ $(basename "$apk") — $actual"
        if [[ "$apk" == *debug* ]]; then
            echo "    The debug APK is not signed with ${keystore}, so it cannot update" >&2
            echo "    a previously released debug build. Check the debug signingConfig." >&2
            status=1
        else
            echo "    Signed by something other than the committed debug key, which is what"
            echo "    the CADENCE_* secrets do. Expected when they are set."
        fi
    fi
done

exit "$status"
