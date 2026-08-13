# tools

Scripts that run beside the app rather than inside it. Python 3.9+, standard library only —
nothing to install.

## `todoist_import.py` — Todoist CSV export → Cadence backup

Todoist's export gives you one CSV per project. This turns a whole export into a single
`cadence.backup` file, which the app imports under **Settings → Backup → Import**. Importing
*merges*, so it adds to what is already on the device rather than replacing it.

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
python3 tools/todoist_import.py export/*.csv -o cadence-backup.json
python3 tools/todoist_import.py export/ --dry-run     # parse and report, write nothing
python3 tools/todoist_import.py --self-test           # the parser's own tests
```

It prints what it found and warns — on stderr, one line each — about any date it could not
read; those are kept verbatim in the task's notes rather than dropped, so nothing goes missing
silently.

### How a Todoist row lands in Cadence

| Todoist | Cadence |
| --- | --- |
| file `Name [id].csv` | a project (`Inbox` goes to the Cadence Inbox instead) |
| `section` row | a subproject — both apps nest one level; empty sections are skipped |
| `task` row, `INDENT 1` | a task in that project or section |
| `task` row, `INDENT ≥ 2` | a subtask of the last `INDENT 1` task (deeper levels flatten onto it) |
| `note` row | appended to the task above it, prefixed with the note's date |
| `DESCRIPTION` | the task's notes |
| `PRIORITY` 1…4 | P1…P4 — the same numbering |
| `DATE`, a repeat phrase | a recurrence rule + the next date it lands on |
| `DATE`, a date | the due date (and the time, when it carries one) |
| `DEADLINE` | the due date when `DATE` left none, otherwise a `Deadline: …` note |
| `DURATION` | a note line — Cadence has no duration field |
| `@label` | left in the title, unless `--strip-labels` moves it to the notes |

Repeat phrases parse in German and English: `jeden Monat`, `alle 2 Wochen`, `alle vier Tage`,
`jedes Quartal`, `jeden Donnerstag`, `jeden 15. des Monats`, `every 4 months`, `every other
week`, `every last day of the month`, `every 2nd tuesday`, `jeden Werktag`. Todoist's
`every! 3 months` — "three months after I tick it off" — becomes an `AFTER_COMPLETION` rule,
the one phrase a calendar rule cannot express.

A recurring task also gets a due date, because the export carries the rule but not the next
occurrence: the next date the rule matches, from today. `--recurring-due none` leaves it undated
instead.

### Re-running it

Ids are derived from the source file and row (UUIDv5), so converting the same export twice
produces the same ids. Adding tasks in Todoist, exporting again and importing again therefore
updates the rows it wrote before instead of duplicating them — the import merges on id, newest
`updatedAt` wins.

### Options worth knowing

| Flag | What it does |
| --- | --- |
| `--dry-run` | parse and report, write nothing |
| `--strip-labels` | move `@labels` out of the title into the notes |
| `--bare-year next-occurrence` | read a year-less date (`15 Mar`) as the *upcoming* one rather than this year's — use it if you would rather not import overdue tasks |
| `--recurring-due none` | do not give recurring tasks a due date |
| `--invert-priority` | read `PRIORITY 4` as P1, for an export that numbers them the API's way round |
| `--inbox-name` | the file whose tasks go to the Cadence Inbox (default `Inbox`) |
| `--today` | the reference date for relative dates, for a reproducible run |

### Tests

`python3 tools/todoist_import.py --self-test` covers the date and repeat-phrase parsing and the
row→task conversion. The other half of the contract — that the app still reads what this writes
— is `core`'s `TodoistImportFixtureTest`, which decodes a copy of the script's output through
`BackupCodec`.
