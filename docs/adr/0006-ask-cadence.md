# ADR 0006 — Ask Cadence: questions about the list, answered by Claude

**Status:** accepted
**Date:** 2026-09-05
**Amends:** ROADMAP's "not proposed: AI features" — one AI feature is proposed, and it is this
one only. Nothing in ADR 0001–0005 changes: the local database stays the source of truth, sync
carries no new column, the wire and the backup file keep their shape.

## Context

The list remembers more than the screens show. Completing a recurring task keeps the finished
row and inserts the next occurrence (CLAUDE.md, "Completing a recurring task"), so a year of
*Clean the kitchen* is a year of rows with `completedAt` set — and no screen answers "when did I
last clean the kitchen?", because Inbox and projects deliberately hide superseded occurrences
and Search finds titles, not dates. The same goes for "what is due this week in Home?" or "how
often did I actually go running?": every answer is in the database, and getting at it means
scrolling.

A question in your own words, typed or spoken, is the natural interface for that, and a language
model with read access to the list is the natural way to answer it. The constraints are the
app's own: local-first (nothing leaves the device unasked), no analytics, no accounts the app
creates, and one shared `:ui` for both shells.

## Decisions

1. **One feature, one endpoint, read-only.** *Ask Cadence* is a screen with a transcript and a
   text field. A question is answered by `POST /v1/messages` on the Anthropic API with tool
   use; the tools read a snapshot of the lists and nothing else. The model cannot add, complete,
   move or delete a task, and the system prompt says so — the app itself does those, with undo
   and a snackbar and a sync push, and none of that should be reachable through a sentence
   whose meaning a model guessed. Writing through the assistant is left for a later ADR, if
   ever.

2. **The user's own API key, stored per device, entered in Settings.** There is no Cadence
   backend to proxy through and no plan for one; a key in the app is the only way the request
   can be made at all, and it makes the cost visible to the one person paying it. The key lives
   in `CadenceSettings.claudeApiKey` — `SharedPreferences` on Android, the JSON file on the
   desktop — beside the theme, and is deliberately **not synced**: it is the user's account,
   entered where it is used, and the sync wire otherwise carries only tasks. Neither store
   encrypts anything; that is the standing the sync session already has in `syncStateRow`, and
   the same trade (a private app directory, a per-user file) is accepted here. The Settings
   field is a password field and the stored key is never read back into it. Without a key the
   screen explains itself and points at Settings; nothing is sent.

3. **Tools, not context.** The model gets five tools — `search_tasks`, `list_tasks`, `get_task`,
   `list_projects`, `list_tags` (`domain/assistant/AssistantTools.kt`) — and no task text up
   front. A list with years of completed occurrences is thousands of rows, a question touches a
   handful, and a prompt that carried the whole list would send everything the user has ever
   written to answer "what is due today". The toolbox is pure Kotlin over an
   `AssistantSnapshot` (the ViewModel's current `tasks`/`projects`/`tags`/`sections`, pending
   deletes already hidden), resolves every date against an injected clock, and is unit-tested
   on the JVM like `QuickAddParser`. `search_tasks` includes completed rows on purpose — that
   is the whole point — and `get_task` walks the recurrence chain both ways, so "how often" is
   one call.

4. **Hand-rolled Ktor, one class, in `:core`** — `data/assistant/ClaudeAssistant.kt`, the same
   shape and reasoning as `CadenceSyncEngine` (ADR 0005): HTTPS and JSON are not a platform
   difference, the wire is one endpoint, and the official Java SDK would bring Jackson and an
   Android runtime requirement into a module whose tests run on the plain JVM. The request is
   `claude-opus-5`, adaptive thinking left at its default, `effort: medium`, a cached system
   prompt with the date in a separate block after it, and `fallbacks: "default"` under its beta
   header so a safety classifier that misfires on a chore is rerun on another model inside the
   same call rather than surfaced as a refusal. Model, version and beta flag are constants in
   the companion. The loop is the documented one: echo the assistant turn unchanged (thinking
   blocks included), answer every `tool_use` in one user message, at most eight rounds.
   Failures map to five reasons the screen can word — offline, key rejected, rate limited,
   server, refused — and the API's own error text goes nowhere near the UI.

5. **Voice is a port, and the desktop answers "no".** `ui/platform/Ports.kt` gains `VoiceInput`
   with an `isAvailable` flag. Android implements it with `RecognizerIntent` — the system
   listening dialog, which holds `RECORD_AUDIO` itself, so the app declares no microphone
   permission and shows no prompt; only a `<queries>` entry for package visibility. The JVM has
   no speech recogniser, so the desktop passes `NoVoiceInput` and the microphone is not drawn;
   every desktop OS ships dictation that types into the field. A spoken question is asked at
   once rather than put in the field to be read back, because otherwise the microphone is
   slower than typing. This is the feature's one platform difference, and it is stated where
   the others are.

6. **The transcript is scratch, not a record.** `CadenceViewModel.assistantState` is a second
   `StateFlow` beside `state`, not a field in `CadenceUiState`: nothing stores or syncs it, the
   `combine` is at its five-flow limit, and no other screen reads it. Only text turns are
   replayed on the next question (the last twenty), never earlier tool calls — a follow-up
   question re-reads the list as it is now, not an earlier answer's evidence. *New
   conversation* forgets it, an unanswered question included.

7. **Where it is reached from.** The Today header, beside search, on both shells — both are
   ways to *find* something, and Today is the screen the app opens on. On the desktop also the
   sidebar, the collapsed rail, the command palette and `Ctrl`/`Cmd`+`J`. Deliberately not a
   fifth bottom-bar destination on Android: it is a way to ask about work, not a place work
   lives — the same rule tags follow (ADR 0004).

## Consequences

- A new screen (`ui/assistant/`), a new port, a new setting, a new `:core` package and
  `ClaudeAssistant` in each shell's `AppContainer` and in the ViewModel's constructor. Every
  `CadenceViewModel` construction site — both shells, three tests — names it.
- Questions and the tool results that answer them reach Anthropic. The Settings text and the
  footnote under the field both say so, in plain words, and the empty state says it before the
  first question. Signed out of sync and without a key the app still makes no request at all.
- The answer can be wrong, and says on screen that it can. The toolbox hands the model facts
  with dates; the prompt tells it never to guess one; the tests pin the facts, not the prose.
- Cost is the user's and per question. Opus-tier at medium effort with a cached prompt is cents
  per question; a cheaper model was considered and rejected because an answer that names the
  wrong date is worse than no answer, and a chat this short does not amortise the difference.
- The beta header and the model id will need moving eventually. Both are one line in one
  companion object, and `ClaudeAssistantTest` pins the wire so the move is visible.
