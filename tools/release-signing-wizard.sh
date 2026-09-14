#!/usr/bin/env bash
#
# Release signing and sync endpoints for Primico's official builds — a wizard that walks the
# maintainer (or anyone running their own fork) through the one-time setup the release
# workflows need, and that only a human can do because it involves a passphrase:
#
#   1. mint the Android release keystore (or reuse one), kept OUTSIDE the repository
#   2. store the four CADENCE_KEY* signing secrets in GitHub Actions
#   3. store the two CADENCE_NEON_* sync endpoints as secrets, so the published APK and
#      installers are built against the maintainer's Neon project
#
# Re-running is safe: an existing keystore is reused, never overwritten, and every secret is
# simply set again. Non-secret answers (paths, alias, URLs) are remembered in a small env file
# next to the keystore; passphrases are never written anywhere.
#
#   bash tools/release-signing-wizard.sh
#
# Needs: keytool (ships with the JDK), gh (authenticated against the repository), base64.
# See docs/self-hosting.md for what the two URLs are and README.md for why the release key
# matters (Android only updates an app over one signed with the same key).
#
# Everything above the "STAGES" marker is the wizard library: do not hand-edit
# it. Author the per-step stages below the marker.

set -euo pipefail

# ──────────────────────────────────────────────────────────────────────────
# Wizard library — delightful, consistent UX. Identical across every wizard.
# ──────────────────────────────────────────────────────────────────────────

if [[ -t 1 ]] && command -v tput >/dev/null 2>&1 && [[ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]]; then
  BOLD=$(tput bold); DIM=$(tput dim); RESET=$(tput sgr0)
  BLUE=$(tput setaf 4); GREEN=$(tput setaf 2); YELLOW=$(tput setaf 3); RED=$(tput setaf 1)
else
  BOLD=""; DIM=""; RESET=""; BLUE=""; GREEN=""; YELLOW=""; RED=""
fi

# Author sets this at the top of the stages section.
TOTAL_STAGES=0

_STAGE_INDEX=0
ENV_FILE="${ENV_FILE:-.env}"
WRITTEN_ENV=()    # KEYs written to ENV_FILE this run
WRITTEN_SECRET=() # secret NAMEs set this run
SKIPPED=()        # things we couldn't do (e.g. gh missing)

# _clear — wipe the terminal so only the current step is on screen. No-op when
# output isn't a terminal, so piped logs stay readable.
_clear() {
  [[ -t 1 ]] || return 0
  if command -v tput >/dev/null 2>&1; then tput clear; else printf '\033[2J\033[3J\033[H'; fi
}

# banner "Title" — opening frame: what this wizard does.
banner() {
  _clear
  printf '\n%s%s  %s%s\n' "$BOLD" "$BLUE" "$1" "$RESET"
  printf '%s  %s stages%s\n\n' "$DIM" "$TOTAL_STAGES" "$RESET"
  printf '%s  You drive the browser; this wizard tells you exactly what to do and\n' "$DIM"
  printf '  captures the values you copy back. Stop any time with Ctrl-C and re-run\n'
  printf '  later — it remembers values already saved.%s\n' "$RESET"
  pause "Ready to start?"
}

# stage "Name" — clear the screen, then announce a stage and show progress.
# Clearing keeps only the current step on screen.
stage() {
  _clear
  _STAGE_INDEX=$((_STAGE_INDEX + 1))
  printf '\n%s%s▸ Stage %s/%s · %s%s\n' \
    "$BOLD" "$BLUE" "$_STAGE_INDEX" "$TOTAL_STAGES" "$1" "$RESET"
}

# say "..." — a plain instruction line.
say()  { printf '  %s\n' "$1"; }
# step "..." — a numbered-feeling action the human takes in the browser.
step() { printf '  %s•%s %s\n' "$BLUE" "$RESET" "$1"; }
note() { printf '  %s%s%s\n' "$DIM" "$1" "$RESET"; }
warn() { printf '  %s⚠ %s%s\n' "$YELLOW" "$1" "$RESET"; }

# open_url URL — open in the human's browser, cross-platform incl. WSL.
open_url() {
  local url="$1"
  printf '  %s↗ opening%s %s\n' "$GREEN" "$RESET" "$url"
  { if   command -v wslview     >/dev/null 2>&1; then wslview "$url"
    elif command -v explorer.exe >/dev/null 2>&1; then explorer.exe "$url"
    elif command -v xdg-open    >/dev/null 2>&1; then xdg-open "$url"
    elif command -v open        >/dev/null 2>&1; then open "$url"
    else warn "couldn't open a browser — visit it manually: $url"; fi
  } >/dev/null 2>&1 || warn "couldn't open a browser — visit it manually: $url"
}

# pause "msg" — wait for the human to confirm they've done the manual part.
pause() {
  printf '  %s%s%s ' "$DIM" "${1:-Press Enter to continue}" "$RESET"
  read -r _ || true
}

# confirm "question" — y/N gate; returns success on yes.
confirm() {
  local reply=""
  printf '  %s? %s [y/N] ' "$YELLOW" "$1"
  read -r reply || true
  [[ "$reply" =~ ^[Yy] ]]
}

# _existing KEY — current value of KEY in ENV_FILE, if any.
_existing() {
  [[ -f "$ENV_FILE" ]] || return 1
  local line; line=$(grep -E "^${1}=" "$ENV_FILE" | tail -n1) || return 1
  printf '%s' "${line#*=}"
}

# ask KEY "Prompt" — read a value into $KEY. Offers the existing .env value as
# a default on re-runs (Enter keeps it). Visible input (non-secret).
ask() {
  local key="$1" prompt="$2" current input
  current=$(_existing "$key" || true)
  if [[ -n "$current" ]]; then
    printf '  %s%s%s %s[Enter keeps current]%s ' "$BOLD" "$prompt" "$RESET" "$DIM" "$RESET"
  else
    printf '  %s%s%s ' "$BOLD" "$prompt" "$RESET"
  fi
  read -r input || true
  [[ -z "$input" && -n "$current" ]] && input="$current"
  printf -v "$key" '%s' "$input"
}

# ask_secret KEY "Prompt" — like ask, but input is hidden.
ask_secret() {
  local key="$1" prompt="$2" current input
  current=$(_existing "$key" || true)
  if [[ -n "$current" ]]; then
    printf '  %s%s%s %s[Enter keeps current]%s ' "$BOLD" "$prompt" "$RESET" "$DIM" "$RESET"
  else
    printf '  %s%s%s ' "$BOLD" "$prompt" "$RESET"
  fi
  read -rs input || true
  printf '\n'
  [[ -z "$input" && -n "$current" ]] && input="$current"
  printf -v "$key" '%s' "$input"
}

# write_env KEY VALUE — upsert KEY=VALUE into ENV_FILE (creates it; replaces
# any existing line). Idempotent.
write_env() {
  local key="$1" value="$2" tmp
  touch "$ENV_FILE"
  tmp=$(mktemp)
  grep -vE "^${key}=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  mv "$tmp" "$ENV_FILE"
  WRITTEN_ENV+=("$key")
  printf '  %s✓ wrote%s %s → %s\n' "$GREEN" "$RESET" "$key" "$ENV_FILE"
}

# set_secret NAME VALUE — set a GitHub Actions repo secret via gh. Falls back
# to a warning (and records it) if gh is unavailable or unauthenticated.
set_secret() {
  local name="$1" value="$2"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if printf '%s' "$value" | gh secret set "$name" >/dev/null 2>&1; then
      WRITTEN_SECRET+=("$name")
      printf '  %s✓ set%s GitHub secret %s\n' "$GREEN" "$RESET" "$name"
      return
    fi
  fi
  SKIPPED+=("GitHub secret $name (set it manually: gh secret set $name)")
  warn "skipped GitHub secret $name — gh not ready; set it later"
}

# set_var NAME VALUE — set a GitHub Actions repo variable (non-secret).
set_var() {
  local name="$1" value="$2"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if gh variable set "$name" --body "$value" >/dev/null 2>&1; then
      printf '  %s✓ set%s GitHub variable %s\n' "$GREEN" "$RESET" "$name"
      return
    fi
  fi
  SKIPPED+=("GitHub variable $name")
  warn "skipped GitHub variable $name — gh not ready; set it later"
}

# finish — clear, then a closing summary of everything configured.
finish() {
  _clear
  printf '\n%s%s  ✓ Setup complete%s\n' "$BOLD" "$GREEN" "$RESET"
  (( ${#WRITTEN_ENV[@]} ))    && note "wrote ${#WRITTEN_ENV[@]} value(s) to $ENV_FILE: ${WRITTEN_ENV[*]}"
  (( ${#WRITTEN_SECRET[@]} )) && note "set ${#WRITTEN_SECRET[@]} GitHub secret(s): ${WRITTEN_SECRET[*]}"
  if (( ${#SKIPPED[@]} )); then
    printf '\n'; warn "still to do by hand:"
    for s in "${SKIPPED[@]}"; do note "  - $s"; done
  fi
  printf '\n'
}

# ──────────────────────────────────────────────────────────────────────────
# STAGES
# ──────────────────────────────────────────────────────────────────────────

TOTAL_STAGES=5

# Where the keystore and the memory of previous answers live. Outside the repository on
# purpose: `*.jks` is gitignored, but a keystore inside a checkout is one `git add -f` from
# being public. ENV_FILE is the library's per-run memory; it carries no passphrase.
KEY_DIR="${CADENCE_RELEASE_KEY_DIR:-$HOME/.cadence-release}"
mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
ENV_FILE="$KEY_DIR/release.env"

banner "Primico — release signing and sync endpoints"

# ── 1 · prerequisites ─────────────────────────────────────────────────────
stage "Prerequisites"
say "Checking the tools this wizard needs."
missing=0
for tool in keytool gh base64; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$tool"
  else
    printf '  %s✗%s %s\n' "$RED" "$RESET" "$tool"; missing=1
  fi
done
if (( missing )); then
  warn "Install what is missing (keytool comes with the JDK) and re-run."
  exit 1
fi
if gh auth status >/dev/null 2>&1; then
  repo="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo '?')"
  printf '  %s✓%s gh is signed in; secrets will be set on %s%s%s\n' "$GREEN" "$RESET" "$BOLD" "$repo" "$RESET"
else
  warn "gh is not signed in. Secrets will be skipped and listed at the end; run 'gh auth login' first to avoid that."
fi
note "Keystore directory: $KEY_DIR"
pause

# ── 2 · the keystore ──────────────────────────────────────────────────────
stage "Release keystore"
say "Android installs an update only over an app signed with the SAME key. This key therefore"
say "has to outlive every laptop and every runner: it lives in $KEY_DIR, and you back it up."
say ""
ask CADENCE_KEYSTORE "Keystore file [$KEY_DIR/cadence-release.jks]:"
CADENCE_KEYSTORE="${CADENCE_KEYSTORE:-$KEY_DIR/cadence-release.jks}"
ask CADENCE_KEY_ALIAS "Key alias [cadence]:"
CADENCE_KEY_ALIAS="${CADENCE_KEY_ALIAS:-cadence}"
write_env CADENCE_KEYSTORE "$CADENCE_KEYSTORE"
write_env CADENCE_KEY_ALIAS "$CADENCE_KEY_ALIAS"

if [[ -f "$CADENCE_KEYSTORE" ]]; then
  say ""
  say "Found an existing keystore — reusing it. (Delete the file first if you really mean a new key;"
  say "a new key means every existing install must be uninstalled once.)"
  ask_secret CADENCE_KEYSTORE_PASSWORD "Keystore password:"
  ask_secret CADENCE_KEY_PASSWORD "Key password (Enter if it is the same as the keystore's):"
  CADENCE_KEY_PASSWORD="${CADENCE_KEY_PASSWORD:-$CADENCE_KEYSTORE_PASSWORD}"
else
  say ""
  say "Minting a new RSA-4096 key valid for 50 years. Pick a passphrase you will keep in a"
  say "password manager; it is never written to disk by this wizard."
  while :; do
    ask_secret CADENCE_KEYSTORE_PASSWORD "New keystore password (12+ characters):"
    if (( ${#CADENCE_KEYSTORE_PASSWORD} < 12 )); then warn "Too short."; continue; fi
    ask_secret confirm_pw "Repeat it:"
    [[ "$confirm_pw" == "$CADENCE_KEYSTORE_PASSWORD" ]] && break
    warn "They differ — again."
  done
  # One passphrase for store and key: PKCS12 keystores treat them as one anyway, and two values
  # that must match is one more thing to lose.
  CADENCE_KEY_PASSWORD="$CADENCE_KEYSTORE_PASSWORD"
  ask DNAME_CN "Your name for the certificate (CN) [Andreas Sander]:"
  DNAME_CN="${DNAME_CN:-Andreas Sander}"
  write_env DNAME_CN "$DNAME_CN"
  keytool -genkeypair \
    -keystore "$CADENCE_KEYSTORE" -storetype PKCS12 \
    -alias "$CADENCE_KEY_ALIAS" \
    -keyalg RSA -keysize 4096 -validity 18250 \
    -dname "CN=$DNAME_CN, O=Primico" \
    -storepass "$CADENCE_KEYSTORE_PASSWORD" -keypass "$CADENCE_KEY_PASSWORD"
  chmod 600 "$CADENCE_KEYSTORE"
  printf '  %s✓ minted%s %s\n' "$GREEN" "$RESET" "$CADENCE_KEYSTORE"
fi

say ""
say "Verifying the passphrase opens the keystore before anything is uploaded…"
if fingerprint="$(keytool -list -v -keystore "$CADENCE_KEYSTORE" -alias "$CADENCE_KEY_ALIAS" \
      -storepass "$CADENCE_KEYSTORE_PASSWORD" 2>/dev/null | grep -m1 'SHA256:' | awk '{print $NF}')"; then
  printf '  %s✓%s SHA-256 %s\n' "$GREEN" "$RESET" "$fingerprint"
else
  warn "keytool could not open the keystore with that password/alias. Nothing was uploaded."
  exit 1
fi
pause

# ── 3 · signing secrets ───────────────────────────────────────────────────
stage "GitHub secrets — signing"
say "The release workflow decodes CADENCE_KEYSTORE_BASE64 into a file on the runner and hands"
say "the other three to Gradle (app-android/build.gradle.kts). Setting them now."
set_secret CADENCE_KEYSTORE_BASE64 "$(base64 -w0 "$CADENCE_KEYSTORE" 2>/dev/null || base64 "$CADENCE_KEYSTORE" | tr -d '\n')"
set_secret CADENCE_KEYSTORE_PASSWORD "$CADENCE_KEYSTORE_PASSWORD"
set_secret CADENCE_KEY_ALIAS "$CADENCE_KEY_ALIAS"
set_secret CADENCE_KEY_PASSWORD "$CADENCE_KEY_PASSWORD"
pause

# ── 4 · sync endpoints ────────────────────────────────────────────────────
stage "GitHub secrets — sync endpoints"
say "The published builds sync against YOUR Neon project. Both URLs are compiled in at build"
say "time (NeonConfig.fromBuild); a build without them offers no sync at all."
say ""
say "In the Neon console open your project, then:"
open_url "https://console.neon.tech/"
step "Branches → your branch → the 'Data API' tab → copy the endpoint URL."
step "It ends in /rest/v1 and looks like https://<endpoint>.apirest.<region>.aws.neon.tech/neondb/rest/v1"
ask CADENCE_NEON_DATA_API_URL "Data API URL:"
step "Then the 'Auth' tab → copy the Neon Auth base URL."
step "It ends in /auth and looks like https://<endpoint>.neonauth.<region>.aws.neon.tech/neondb/auth"
ask CADENCE_NEON_AUTH_URL "Neon Auth URL:"
for v in CADENCE_NEON_DATA_API_URL CADENCE_NEON_AUTH_URL; do
  if [[ ! "${!v}" =~ ^https:// ]]; then warn "$v does not start with https:// — re-run and paste the full URL."; exit 1; fi
done
write_env CADENCE_NEON_DATA_API_URL "$CADENCE_NEON_DATA_API_URL"
write_env CADENCE_NEON_AUTH_URL "$CADENCE_NEON_AUTH_URL"
set_secret CADENCE_NEON_DATA_API_URL "$CADENCE_NEON_DATA_API_URL"
set_secret CADENCE_NEON_AUTH_URL "$CADENCE_NEON_AUTH_URL"
pause

# ── 5 · back it up ────────────────────────────────────────────────────────
stage "Back up the key"
say "Losing this file, or its passphrase, means no future build can update an existing"
say "install — every user would uninstall once. Put both somewhere that outlives this machine:"
say ""
step "Copy $CADENCE_KEYSTORE to a password manager attachment or an encrypted drive."
step "Store the passphrase in the same password manager, under the same entry."
say ""
say "SHA-256 of the key, for the record:"
say "  $fingerprint"
say ""
say "To build a release-signed APK on this laptop later:"
note "  set -a; source $ENV_FILE; set +a"
note "  CADENCE_KEYSTORE_PASSWORD=… CADENCE_KEY_PASSWORD=… bash .github/scripts/build.sh apk"
confirm "Backed up (or you accept the risk for now)?" || warn "Do it before the first public release."

finish
