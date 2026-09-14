# Security Policy

## Supported versions

Security fixes are applied to the latest release and the `main` branch. Please make sure you're
on the most recent version before reporting an issue.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, report them privately using one of these channels:

- **GitHub Security Advisories** — preferred. Go to the
  [Security tab](../../security/advisories/new) and click *Report a vulnerability*.
- **Email** — write to **mail@andi1984.de** with the details.

Please include as much of the following as you can:

- A description of the vulnerability and its impact
- Steps to reproduce, or a proof of concept
- The version / commit of Cadence and your platform (Android version, or desktop OS)
- Any suggested remediation, if you have one

You can expect an acknowledgement within a few days. We'll keep you updated on progress and let
you know when a fix is released. We ask that you give us a reasonable window to address the
issue before any public disclosure, and we're happy to credit you once it's resolved (unless
you'd prefer to remain anonymous).

## Security model & scope

Cadence is a **local-first** application. A few aspects of its design are worth knowing when
assessing security:

- **Local data** — tasks, projects and attachments are stored in a SQLite database and a blob
  directory in the app's private storage (Android) or the user's data directory (desktop). On
  Android, attachment bytes are handed to other apps only through a `FileProvider` scoped to the
  blob directory, never the database.
- **Sync is optional and self-hosted.** Signed out, the app makes no network request at all.
  Signed in, it talks HTTPS to a [Neon](https://neon.tech) Data API and Neon Auth endpoint
  that whoever built the binary configured. There is no API key in the design: the credential is
  the account, and Postgres row-level security (`neon/migrations/`) is what keeps one account's
  rows from another. The session token lives in the local database next to the sync cursors.
- **The published Android release APK is signed with a key only the maintainer holds.** The
  debug APK is signed with the debug key committed at `app-android/debug.keystore`, which is
  public by construction: anyone can produce a build that installs *over* a debug-signed install.
  Use the release APK from the [releases page](../../releases/latest) unless you are developing.
- **Backup files and imports are trusted input from the user.** The codec repairs dangling
  links and refuses unknown versions, but a backup file is a person asking for its contents.

Reports about any of the above — session handling, the sync engine's merge, the RLS policies,
import parsing, attachment handling, the signing pipeline — are all in scope and appreciated.
