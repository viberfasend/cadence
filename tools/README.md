# tools

Scripts that run beside the app rather than inside it. Python 3.9+, standard library only —
nothing to install.

## `todoist_import.py` — Todoist CSV export → Primico backup

Todoist's export gives you one CSV per project. This turns a whole export into a single
`cadence.backup` file, which the app imports under **Settings → Backup → Import**. Importing
*merges*, so it adds to what is already on the device rather than replacing it.

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
python3 tools/todoist_import.py export/*.csv -o primico-backup.json
python3 tools/todoist_import.py export/ --split       # one JSON per project, into a folder
python3 tools/todoist_import.py export/ --dry-run     # parse and report, write nothing
python3 tools/todoist_import.py --self-test           # the parser's own tests
```

### One file, or one per project

By default the whole export becomes a single `primico-backup.json`. `--split` writes
`cadence-<project>.json` per Todoist project into a folder (`primico-import/` unless `-o` names
another), skipping projects that hold no tasks:

```
$ python3 tools/todoist_import.py "Todoist backup 2026-08-12 2248 UTC" --split
19 file(s) -> 16 project(s), 14 section(s), 145 task(s), 4 subtask(s), 7 note(s)
  cadence-inbox.json: 21 task(s)
  cadence-garten.json: 18 task(s)
  …
wrote 16 file(s) to primico-import/ — import them under Settings -> Backup, one, several or all at once
```

The app's importer takes several files in one go — the picker is multi-select on both Android
and the desktop — and every split file carries the *same* staging project, so importing them in
any order or any grouping lands everything in one pile. Splitting is what to reach for when you
would rather bring a project across at a time than the whole of Todoist at once.

It prints what it found and warns — on stderr, one line each — about any date it could not
read; those are kept verbatim in the task's notes rather than dropped, so nothing goes missing
silently.

### The export does not contain every due date — pass a token

A recurring task exports as its **rule**, never as its next occurrence: "Duschkabine
Schutzbehandlung" leaves Todoist with `DATE` set to `jährlich` and nothing else, even though the
app shows it due on 23 December. No amount of parsing recovers that date, so from the CSV alone
a recurring task can only land on today.

Give the script an API token and it reads the real date and time of every task instead:

```bash
python3 tools/todoist_import.py "Todoist backup …" --todoist-token 0123456789abcdef
TODOIST_API_TOKEN=0123… python3 tools/todoist_import.py "Todoist backup …"
```

The token is in Todoist → Settings → Integrations → Developer. Tasks are matched by project,
**section** and title, the recurrence rule still comes from the CSV, and the report says how
many dates came back exactly:

```
Todoist API: 149 task(s), 128 with a due date
19 file(s) -> 17 project(s), …
  77 recurring, 33 dated, 128 dated exactly from the Todoist API
```

Times come across the same way: a task due at 09:30 Berlin time arrives at 09:30, whether the
API reported it in UTC or not. Without a token nothing breaks — dated tasks still get their
dates from the CSV, and only recurring ones fall back to "today".

The section is part of the match because titles repeat: "Gießen" under *Beet* and "Gießen" under
*Gewächshaus* are two tasks with two dates, and matching on the project alone handed every one
of them whichever date Todoist happened to list last. A title that repeats *inside* one section
takes its dates in Todoist's own order, and a title the API cannot pin down — the same name in
two sections when the CSV row is in neither — keeps the date the CSV implied rather than
borrowing a wrong one.

### Everything arrives in one staging project

An import is a pile to sort, not a merge. All of it lands under a single project named
**`Import <today>`** — nothing appears in the Inbox, and nothing appears beside the projects
already on the device until you move it there. Filing a task into your own system is the
deliberate act of dragging it out of the pile; when the pile is empty, delete it.

Because Primico nests projects exactly one level and the staging project has taken that level,
a Todoist section becomes a *sibling* of its own project rather than a child, carrying the
project's name: `Garten` and `Garten · August ☀️` sit next to each other under
`Import 2026-08-13`. No grouping is lost, and both disappear once you have emptied them.

`--import-project NAME` renames the pile; `--no-import-project` skips it and imports straight
into top-level projects and the Inbox, the way a merge would.

### How a Todoist row lands in Primico

| Todoist | Primico |
| --- | --- |
| file `Name [id].csv` | a subproject of the staging project — the `Inbox` file included, so there is an "Inbox" to open rather than tasks loose in the pile |
| `section` row | a sibling subproject named `Project · Section`; empty sections are skipped |
| `task` row, `INDENT 1` | a task in that project or section |
| `task` row, `INDENT ≥ 2` | a subtask of the last `INDENT 1` task (deeper levels flatten onto it) |
| `note` row | appended to the task above it, prefixed with the note's date |
| `DESCRIPTION` | the task's notes |
| `PRIORITY` 1…4 | P1…P4 — the same numbering |
| `DATE`, a repeat phrase | a recurrence rule + the next date it lands on |
| `DATE`, a date | the due date (and the time, when it carries one) |
| `DEADLINE` | the due date when `DATE` left none, otherwise a `Deadline: …` note |
| `DURATION` | a note line — Primico has no duration field |
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
| `--todoist-token` | read exact due dates and times from the Todoist API (or `TODOIST_API_TOKEN`) |
| `--dry-run` | parse and report, write nothing |
| `--split` | one JSON per Todoist project instead of one for the whole export |
| `--import-project NAME` | rename the staging project (default `Import <today>`) |
| `--no-import-project` | no staging project: import straight into projects and the Inbox |
| `--strip-labels` | move `@labels` out of the title into the notes |
| `--bare-year next-occurrence` | read a year-less date (`15 Mar`) as the *upcoming* one rather than this year's — use it if you would rather not import overdue tasks |
| `--recurring-due none` | do not give recurring tasks a due date |
| `--invert-priority` | read `PRIORITY 4` as P1, for an export that numbers them the API's way round |
| `--inbox-name` | the file whose tasks go to the Primico Inbox (default `Inbox`) |
| `--today` | the reference date for relative dates, for a reproducible run |

### Tests

`python3 tools/todoist_import.py --self-test` covers the date and repeat-phrase parsing and the
row→task conversion. The other half of the contract — that the app still reads what this writes
— is `core`'s `TodoistImportFixtureTest`, which decodes a copy of the script's output through
`BackupCodec`.
