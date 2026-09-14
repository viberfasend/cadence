# AGENTS.md

The guide for working in this repository is [`CLAUDE.md`](CLAUDE.md) — one file, read by every
agent and every human alike. It holds the architecture, the invariants that have cost real time
to learn, the commands, and the reasons behind all of it. Nothing here duplicates it, because a
second copy drifts.

Three things to know before reading it:

- **Verify locally with the one command at the top of its *Commands* section** — the same one
  `.github/workflows/ci.yml` runs on every pull request.
- **Decisions live in `docs/adr/`.** A change that contradicts one should say so and why, in the
  same pull request.
- **Contribution rules are in [`CONTRIBUTING.md`](CONTRIBUTING.md)** — Conventional Commits drive
  the version number, and no user-visible string belongs in Kotlin.
