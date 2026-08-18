#!/usr/bin/env python3
"""Turn a Todoist CSV export into a Cadence backup file.

Todoist exports one CSV per project ("Garten [6g62GH268rQjFQ92].csv"). This script reads any
number of those files — or a whole export directory — and writes a single
`{"format":"cadence.backup","version":2,...}` document, the published contract
`core/.../domain/backup/BackupCodec.kt` reads. Import it in the app under Settings -> Backup;
importing merges, so nothing already on the device is lost.

**Everything lands in one staging project, "Import <today>".** An import is a pile to sort, not
a merge: nothing appears in the Inbox or beside the projects already on the device until the
user moves it there deliberately. `--import-project NAME` renames the pile and
`--no-import-project` skips it.

How a Todoist row lands in Cadence:

    file "Name [id].csv"  -> a subproject of the staging project, the "Inbox" file included
    section row           -> a section of that project — a heading inside its task list, which is
                             what Cadence's own sections are; the tasks under it stay the
                             project's rather than moving into a project of their own
    task row, INDENT 1    -> a task in the current project, carrying the current section
    task row, INDENT >= 2 -> a subtask of the last INDENT 1 task (deeper levels flatten onto it,
                             because Cadence nests subtasks exactly one level)
    note row              -> appended to the task above it, prefixed with the note's date
    DESCRIPTION           -> the task's notes
    PRIORITY 1..4         -> Cadence P1..P4 (same numbering; --invert-priority if yours differs)
    DATE                  -> a due date, or a recurrence rule when it is a Todoist repeat phrase
                             ("jeden Monat", "every 2 weeks", "every! 3 months", ...)
    DEADLINE              -> the due date when DATE left none, otherwise a "Deadline: ..." note
    DURATION              -> a note line; Cadence has no duration field

Ids are deterministic (UUIDv5 over the source file and row), so re-running this after adding a
task in Todoist and re-importing updates the rows it already wrote instead of duplicating them.

Usage:
    python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
    python3 tools/todoist_import.py export/ --todoist-token 0123…    # exact dates from Todoist
    python3 tools/todoist_import.py export/*.csv -o cadence-backup.json
    python3 tools/todoist_import.py export/ --split        # one JSON per project, into a folder
    python3 tools/todoist_import.py export/ --dry-run      # parse and report, write nothing
    python3 tools/todoist_import.py --self-test            # the parser's unit tests

**The export does not contain every due date.** A recurring task exports as its *rule* — `DATE`
reads "jährlich", not "23 Dec 2026" — so the next occurrence Todoist shows you is nowhere in the
file, and the rule alone can only put the task on today. Pass `--todoist-token` (or set
`TODOIST_API_TOKEN`) and the exact date and time of every task are read from the API and used
instead; the token comes from Todoist -> Settings -> Integrations -> Developer. Those dates are
matched by project, section and title — titles repeat across sections, and matching on the
project alone gave every copy of one whichever date Todoist listed last.

`--split` writes `cadence-<project>.json` per Todoist project rather than one file for the
whole export, so the import can be done a project at a time — the app's importer takes several
files in one go, and every split file carries the same staging project, so importing them in
any order or any grouping lands them all in the same pile.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import re
import sys
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Iterable, Sequence

# The version BackupCodec.VERSION carries. A reader refuses a *higher* version, so this must
# track the app rather than run ahead of it.
BACKUP_FORMAT = "cadence.backup"
BACKUP_VERSION = 2

DEFAULT_OUT = "cadence-backup.json"
DEFAULT_SPLIT_OUT = "cadence-import"

# ui/projects/ProjectDialogs.kt's PROJECT_COLORS, cycled so imported projects are not all teal.
PROJECT_COLORS = ["#006A60", "#3E6373", "#A1560A", "#7D5260", "#6F7976", "#BA1A1A"]

# Any UUID would do; a fixed one is what makes a re-import land on the same rows.
ID_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "https://github.com/andi1984/todo/todoist-import")

WEEKDAYS = {
    "monday": 1, "mon": 1, "mo": 1, "montag": 1, "montags": 1,
    "tuesday": 2, "tue": 2, "tues": 2, "di": 2, "dienstag": 2, "dienstags": 2,
    "wednesday": 3, "wed": 3, "mi": 3, "mittwoch": 3, "mittwochs": 3,
    "thursday": 4, "thu": 4, "thur": 4, "thurs": 4, "do": 4, "donnerstag": 4, "donnerstags": 4,
    "friday": 5, "fri": 5, "fr": 5, "freitag": 5, "freitags": 5,
    "saturday": 6, "sat": 6, "sa": 6, "samstag": 6, "samstags": 6, "sonnabend": 6,
    "sunday": 7, "sun": 7, "so": 7, "sonntag": 7, "sonntags": 7,
}

DAY_NAMES = {
    1: "MONDAY", 2: "TUESDAY", 3: "WEDNESDAY", 4: "THURSDAY",
    5: "FRIDAY", 6: "SATURDAY", 7: "SUNDAY",
}

MONTHS = {
    "jan": 1, "january": 1, "januar": 1, "jaenner": 1,
    "feb": 2, "february": 2, "februar": 2,
    "mar": 3, "march": 3, "maer": 3, "maerz": 3, "mrz": 3,
    "apr": 4, "april": 4,
    "may": 5, "mai": 5,
    "jun": 6, "june": 6, "juni": 6,
    "jul": 7, "july": 7, "juli": 7,
    "aug": 8, "august": 8,
    "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "okt": 10, "october": 10, "oktober": 10,
    "nov": 11, "november": 11,
    "dec": 12, "dez": 12, "december": 12, "dezember": 12,
}

# Spelled-out counts are vocabulary, the same way QuickAddLexicon treats them: "alle vier Tage"
# has to read as 4 or that task silently becomes a daily one.
NUMBER_WORDS = {
    "one": 1, "a": 1, "an": 1, "ein": 1, "eine": 1, "einen": 1, "einem": 1, "erste": 1,
    "erst": 1, "ersten": 1, "first": 1, "1st": 1,
    "two": 2, "other": 2, "zwei": 2, "zweite": 2, "zweiten": 2, "second": 2, "2nd": 2,
    "three": 3, "drei": 3, "dritte": 3, "dritten": 3, "third": 3, "3rd": 3,
    "four": 4, "vier": 4, "vierte": 4, "vierten": 4, "fourth": 4, "4th": 4,
    "five": 5, "fuenf": 5, "fuenfte": 5, "fuenften": 5, "fifth": 5, "5th": 5,
    "six": 6, "sechs": 6, "sechste": 6, "sechsten": 6, "sixth": 6,
    "seven": 7, "sieben": 7, "siebte": 7, "siebten": 7, "seventh": 7,
    "eight": 8, "acht": 8, "achte": 8, "achten": 8, "eighth": 8,
    "nine": 9, "neun": 9, "neunte": 9, "neunten": 9, "ninth": 9,
    "ten": 10, "zehn": 10, "zehnte": 10, "zehnten": 10, "tenth": 10,
    "eleven": 11, "elf": 11, "twelve": 12, "zwoelf": 12,
}

RELATIVE_DAYS = {
    "today": 0, "heute": 0,
    "tomorrow": 1, "morgen": 1,
    "overmorrow": 2, "uebermorgen": 2,
    "yesterday": -1, "gestern": -1,
}

UNIT_WORDS = {
    "day": ("DAY", 1), "days": ("DAY", 1), "daily": ("DAY", 1),
    "tag": ("DAY", 1), "tage": ("DAY", 1), "tagen": ("DAY", 1), "tages": ("DAY", 1),
    "taeglich": ("DAY", 1),
    "week": ("WEEK", 1), "weeks": ("WEEK", 1), "weekly": ("WEEK", 1),
    "woche": ("WEEK", 1), "wochen": ("WEEK", 1), "woechentlich": ("WEEK", 1),
    "month": ("MONTH", 1), "months": ("MONTH", 1), "monthly": ("MONTH", 1),
    "monat": ("MONTH", 1), "monate": ("MONTH", 1), "monaten": ("MONTH", 1),
    "monats": ("MONTH", 1), "monatlich": ("MONTH", 1),
    # A quarter and a half-year are month rules with an interval, not units of their own.
    "quarter": ("MONTH", 3), "quarterly": ("MONTH", 3), "quartal": ("MONTH", 3),
    "quartalsweise": ("MONTH", 3), "vierteljaehrlich": ("MONTH", 3),
    "halbjaehrlich": ("MONTH", 6), "semiannually": ("MONTH", 6),
    "year": ("YEAR", 1), "years": ("YEAR", 1), "yearly": ("YEAR", 1), "annually": ("YEAR", 1),
    "jahr": ("YEAR", 1), "jahre": ("YEAR", 1), "jahren": ("YEAR", 1), "jahres": ("YEAR", 1),
    "jaehrlich": ("YEAR", 1),
}

WORKDAY_WORDS = {"workday", "workdays", "weekday", "weekdays", "werktag", "werktags",
                 "werktage", "wochentag", "wochentags"}
WEEKEND_WORDS = {"weekend", "weekends", "wochenende", "wochenenden"}
LAST_WORDS = {"last", "letzte", "letzten", "letzter", "letztes"}
RECURRENCE_LEADS = {"every", "each", "jeden", "jede", "jedes", "jeder", "alle", "all",
                    "repeat", "wiederholen"}


def fold(text: str) -> str:
    """Lower-cases and folds the umlauts, so one lexicon entry covers "März" and "maerz"."""
    lowered = text.lower()
    for src, dst in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss"), ("é", "e")):
        lowered = lowered.replace(src, dst)
    return lowered


def tokenize(text: str) -> list[str]:
    return re.findall(r"\d+|[^\W\d_]+", fold(text), re.UNICODE)


@dataclass
class Recurrence:
    """A parsed repeat phrase: the rule, plus the first date it should land on."""

    rule: dict
    anchor: dt.date | None = None
    time: str | None = None


@dataclass
class ParsedDate:
    date: dt.date | None = None
    time: str | None = None


@dataclass
class Stats:
    projects: int = 0
    sections: int = 0
    tasks: int = 0
    subtasks: int = 0
    notes: int = 0
    recurring: int = 0
    dated: int = 0
    exact: int = 0
    staging: str | None = None
    unparsed: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------------------- dates


def extract_time(text: str) -> tuple[str | None, str]:
    """Pulls a clock time out of a date or repeat phrase, returning it and what is left."""
    match = re.search(r"\b(?:um|at|@)?\s*(\d{1,2})[:.](\d{2})\s*(am|pm|uhr)?\b", text, re.I)
    if match:
        hour, minute = int(match.group(1)), int(match.group(2))
        meridiem = (match.group(3) or "").lower()
        if meridiem == "pm" and hour < 12:
            hour += 12
        if meridiem == "am" and hour == 12:
            hour = 0
        if hour <= 23 and minute <= 59:
            rest = text[: match.start()] + " " + text[match.end():]
            return f"{hour:02d}:{minute:02d}", rest
    match = re.search(r"\b(?:um|at|@)?\s*(\d{1,2})\s*(am|pm)\b", text, re.I)
    if match:
        hour = int(match.group(1)) % 12
        if match.group(2).lower() == "pm":
            hour += 12
        rest = text[: match.start()] + " " + text[match.end():]
        return f"{hour:02d}:00", rest
    return None, text


def next_weekday(ref: dt.date, weekday: int, inclusive: bool = True) -> dt.date:
    """The next date falling on [weekday] (1 = Monday), counting [ref] itself when inclusive."""
    delta = (weekday - ref.isoweekday()) % 7
    if delta == 0 and not inclusive:
        delta = 7
    return ref + dt.timedelta(days=delta)


def day_in_month(year: int, month: int, day: int) -> dt.date:
    """Clamps to the month's length, so "the 31st" in February is the 28th/29th."""
    if month == 12:
        last = 31
    else:
        last = (dt.date(year, month + 1, 1) - dt.timedelta(days=1)).day
    return dt.date(year, month, min(day, last))


def next_day_of_month(ref: dt.date, day: int) -> dt.date:
    candidate = day_in_month(ref.year, ref.month, day)
    if candidate >= ref:
        return candidate
    year, month = (ref.year + 1, 1) if ref.month == 12 else (ref.year, ref.month + 1)
    return day_in_month(year, month, day)


def parse_absolute_date(text: str, ref: dt.date, bare_year: str = "current") -> ParsedDate | None:
    """A Todoist due date: "15 Mar", "8. Jul", "1 Sep. 2033", "2026-07-19", "morgen 09:00"."""
    if not text or not text.strip():
        return None
    time, rest = extract_time(text)

    iso = re.search(r"\b(\d{4})-(\d{2})-(\d{2})\b", rest)
    if iso:
        try:
            return ParsedDate(dt.date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3))), time)
        except ValueError:
            return None

    tokens = tokenize(rest)
    if not tokens:
        return ParsedDate(None, time) if time else None

    for token in tokens:
        if token in RELATIVE_DAYS:
            return ParsedDate(ref + dt.timedelta(days=RELATIVE_DAYS[token]), time)

    month = None
    for token in tokens:
        if token in MONTHS:
            month = MONTHS[token]
            break
    numbers = [int(t) for t in tokens if t.isdigit()]
    day = next((n for n in numbers if 1 <= n <= 31), None)
    year = next((n for n in numbers if n >= 1000), None)

    if month is not None and day is not None:
        if year is None:
            # Todoist leaves the year off when it is the current one, so that is the reading —
            # an imported date may well be overdue, which is honest. --bare-year next-occurrence
            # pushes those into the future instead.
            year = ref.year
            if bare_year == "next-occurrence" and day_in_month(year, month, day) < ref:
                year += 1
        return ParsedDate(day_in_month(year, month, day), time)

    for token in tokens:
        if token in WEEKDAYS:
            return ParsedDate(next_weekday(ref, WEEKDAYS[token]), time)

    if month is None and day is not None and len(numbers) == 1 and len(tokens) == 1:
        return ParsedDate(next_day_of_month(ref, day), time)

    return ParsedDate(None, time) if time else None


# ---------------------------------------------------------------------------------- recurrence


ORDINAL_SUFFIXES = {"st", "nd", "rd", "th"}
FILLER_WORDS = {"and", "und", "the", "der", "die", "das"} | ORDINAL_SUFFIXES


def _interval_before(tokens: Sequence[str], index: int) -> int | None:
    """The count a unit is qualified by: "alle 2 Wochen", "alle vier Tage", "every other week"."""
    for token in reversed(tokens[:index]):
        if token.isdigit():
            return max(1, int(token))
        if token in NUMBER_WORDS:
            return NUMBER_WORDS[token]
        if token in RECURRENCE_LEADS or token in FILLER_WORDS:
            continue
        break
    return None


def _ordinal_day(tokens: Sequence[str], folded: str) -> int | None:
    """"jeden 15. des Monats" / "every 15th of the month" — the day a monthly rule lands on.

    An ordinal that is followed by the unit itself ("every 3rd month") or by a weekday
    ("every 2nd tuesday") counts something else, and is left to the interval and nth-weekday
    handling rather than read as a day of the month.
    """
    for index, token in enumerate(tokens):
        if not token.isdigit():
            continue
        follower = tokens[index + 1] if index + 1 < len(tokens) else ""
        is_ordinal = follower in ORDINAL_SUFFIXES or re.search(rf"\b{token}\s*\.", folded)
        if not is_ordinal or not 1 <= int(token) <= 31:
            continue
        rest = [t for t in tokens[index + 1:] if t not in ORDINAL_SUFFIXES]
        if rest and (rest[0] in UNIT_WORDS or rest[0] in WEEKDAYS):
            return None
        return int(token)
    return None


def parse_recurrence(text: str, ref: dt.date) -> Recurrence | None:
    """A Todoist repeat phrase, in German or English, or None when this is not one."""
    if not text or not text.strip():
        return None
    time, rest = extract_time(text)
    folded = fold(rest)
    tokens = tokenize(rest)
    if not tokens:
        return None

    # Todoist's "every! 3 months" means "3 months after I tick it off" — Cadence's
    # AFTER_COMPLETION mode, the one thing a plain calendar rule cannot express.
    after_completion = bool(re.search(r"(every|alle|jede[nrs]?)\s*!", folded))

    is_repeat = tokens[0] in RECURRENCE_LEADS or any(
        token in UNIT_WORDS and UNIT_WORDS[token][0] and token.endswith(("lich", "ly"))
        for token in tokens
    )
    if not is_repeat:
        return None

    rule = {
        "mode": "AFTER_COMPLETION" if after_completion else "SCHEDULE",
        "interval": 1,
        "unit": "WEEK",
        "daysOfWeek": [],
        "monthlyMode": "DAY_OF_MONTH",
        "dayOfMonth": None,
        "nthWeek": None,
        "nthDayOfWeek": None,
        "keepMissed": True,
    }

    days = sorted({WEEKDAYS[t] for t in tokens if t in WEEKDAYS})
    if any(t in WORKDAY_WORDS for t in tokens):
        days = [1, 2, 3, 4, 5]
    elif any(t in WEEKEND_WORDS for t in tokens):
        days = [6, 7]

    has_last = any(t in LAST_WORDS for t in tokens)
    hits = [(index, *UNIT_WORDS[token]) for index, token in enumerate(tokens)
            if token in UNIT_WORDS]
    # "every last day of the month" names two units, and the second one is the rule: the first
    # is what "last" qualifies. Without this the phrase reads as a daily task.
    if has_last:
        hit = next((h for h in hits if h[1] in ("MONTH", "YEAR")), None) or (0, "MONTH", 1)
    else:
        hit = hits[0] if hits else None
    unit = hit[1] if hit else None
    interval = (_interval_before(tokens, hit[0]) or 1) * hit[2] if hit else None
    ordinal_day = _ordinal_day(tokens, folded)

    if days and unit in (None, "WEEK"):
        rule["unit"] = "WEEK"
        rule["interval"] = interval or 1
        rule["daysOfWeek"] = [DAY_NAMES[d] for d in days]
        anchor = min((next_weekday(ref, d) for d in days), default=ref)
        return Recurrence(rule, anchor, time)

    if unit is None:
        # "jeden Montag" was handled above; a bare "every 3" has no unit to work with.
        return None

    rule["unit"] = unit
    rule["interval"] = interval or 1

    if unit in ("MONTH", "YEAR"):
        if has_last and days:
            rule["monthlyMode"] = "LAST_WEEKDAY"
            rule["nthDayOfWeek"] = DAY_NAMES[days[0]]
        elif has_last:
            rule["monthlyMode"] = "LAST_DAY"
        elif days:
            # "every 2nd tuesday of the month"
            nth = None
            for index, token in enumerate(tokens):
                if token in WEEKDAYS:
                    nth = _interval_before(tokens, index)
                    break
            rule["monthlyMode"] = "NTH_WEEKDAY"
            rule["nthWeek"] = nth or 1
            rule["nthDayOfWeek"] = DAY_NAMES[days[0]]
        elif ordinal_day:
            rule["dayOfMonth"] = ordinal_day

    anchor = ref
    if rule["monthlyMode"] == "DAY_OF_MONTH" and rule["dayOfMonth"]:
        anchor = next_day_of_month(ref, rule["dayOfMonth"])
    return Recurrence(rule, anchor, time)


def parse_date_field(text: str, ref: dt.date, bare_year: str) -> tuple[Recurrence | None, ParsedDate | None]:
    """Todoist's one DATE column is either a repeat phrase or a date; this decides which."""
    recurrence = parse_recurrence(text, ref)
    if recurrence is not None:
        return recurrence, None
    return None, parse_absolute_date(text, ref, bare_year)


# ------------------------------------------------------------------------------------ the rows


# ------------------------------------------------------------------- exact dates, from the API


TODOIST_V1 = "https://api.todoist.com/api/v1"
TODOIST_V2 = "https://api.todoist.com/rest/v2"


@dataclass
class DueEntry:
    """One API task's due date, with the position Todoist lists it at."""

    date: dt.date
    time: str | None
    order: int

    @property
    def value(self) -> tuple[dt.date, str | None]:
        return self.date, self.time


@dataclass
class DueIndex:
    """The due dates Todoist knows and its CSV export does not.

    A recurring row exports as its *rule* — `DATE` reads "jährlich", never "23 Dec 2026" — so a
    yearly task's actual next occurrence exists only inside Todoist. Reading it back needs the
    API, which is what `--todoist-token` is for; without one the rule alone decides the date and
    a yearly task lands on today.

    The match is on the title, and **the section is part of the key**: "Gießen" under "Beet" and
    "Gießen" under "Gewächshaus" are two tasks with two dates, and a project-wide index kept only
    whichever the API listed last — every copy then imported carrying that one date. Each key
    holds every task that answers to it, so a title repeated inside one section is handed its
    dates in Todoist's own order rather than collapsing the same way.
    """

    by_section: dict[tuple[str, str, str], list[DueEntry]] = field(default_factory=dict)
    by_project: dict[tuple[str, str], list[DueEntry]] = field(default_factory=dict)
    by_content: dict[str, list[DueEntry]] = field(default_factory=dict)
    _cursors: dict[object, int] = field(default_factory=dict)

    def lookup(self, project: str, title: str,
               section: str | None = None) -> tuple[dt.date, str | None] | None:
        # The CSV writes labels into the title and the API keeps them apart, so both sides of
        # the match drop them.
        key = fold(split_labels(title.strip())[0])
        project_key = fold(project.strip())
        candidates = (
            (self.by_section, (project_key, fold((section or "").strip()), key), True),
            (self.by_project, (project_key, key), False),
            (self.by_content, key, False),
        )
        for table, table_key, ordered in candidates:
            entries = table.get(table_key)
            if not entries:
                continue
            hit = self._take(table_key, entries, ordered)
            if hit is not None:
                return hit
        return None

    def _take(self, key: object, entries: list[DueEntry],
              ordered: bool) -> tuple[dt.date, str | None] | None:
        """The date this key means — handing them out one by one only where the key is exact.

        A key every entry agrees on answers every time. Where they disagree, a *section* key is
        specific enough to hand them out one per row in Todoist's order; a project-wide or
        title-only key is not, and answering from one is what put one section's date on another
        section's task, so it answers nothing and the date the CSV implied stands.
        """
        values = {entry.value for entry in entries}
        if len(values) == 1:
            return entries[0].value
        if not ordered:
            return None
        used = self._cursors.get(key, 0)
        if used >= len(entries):
            return None
        self._cursors[key] = used + 1
        return entries[used].value

    def __len__(self) -> int:
        return sum(len(entries) for entries in self.by_content.values())


def parse_api_due(due: dict) -> tuple[dt.date, str | None] | None:
    """Todoist's `due` object: `date` is a date or a datetime, and a `Z` means UTC."""
    raw = (due.get("date") or "").strip() or (due.get("datetime") or "").strip()
    if not raw:
        return None
    if "T" not in raw:
        try:
            return dt.date.fromisoformat(raw[:10]), None
        except ValueError:
            return None
    try:
        stamp = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is not None:
        # A timed task is stored in UTC with the zone it was written in beside it; showing it in
        # that zone is what the user set, not what the wire says.
        zone = due.get("timezone") or ""
        try:
            from zoneinfo import ZoneInfo

            stamp = stamp.astimezone(ZoneInfo(zone)) if zone and "/" in zone else stamp.astimezone()
        except Exception:
            stamp = stamp.astimezone()
    return stamp.date(), stamp.strftime("%H:%M")


def _api_order(task: dict, fallback: int) -> int:
    """Todoist's own ordering of a task inside its section (`child_order`, `order` on REST v2)."""
    for key in ("child_order", "order"):
        raw = task.get(key)
        if isinstance(raw, int):
            return raw
        if isinstance(raw, str) and raw.strip().lstrip("-").isdigit():
            return int(raw.strip())
    return fallback


def build_due_index(tasks: Iterable[dict], projects: Iterable[dict],
                    sections: Iterable[dict] = ()) -> DueIndex:
    """Indexes the API's tasks by project, section and title — what a CSV row also carries.

    Ids are no use as a key — the export does not contain them — so the match is on the title,
    with the labels stripped: the CSV writes them into `CONTENT` ("Mirabelle schneiden @Baum")
    and the API keeps them in a field of their own. Titles repeat, though, which is why the
    section is in the key and why a key keeps every task rather than the last one to be seen.
    """
    names = {str(project.get("id")): project.get("name") or "" for project in projects}
    section_names = {str(section.get("id")): section.get("name") or "" for section in sections}
    index = DueIndex()
    for position, task in enumerate(tasks):
        parsed = parse_api_due(task.get("due") or {})
        if parsed is None:
            continue
        title = fold(split_labels((task.get("content") or "").strip())[0])
        project = fold(names.get(str(task.get("project_id")), ""))
        section = fold(section_names.get(str(task.get("section_id") or ""), ""))
        entry = DueEntry(parsed[0], parsed[1], _api_order(task, position))
        index.by_section.setdefault((project, section, title), []).append(entry)
        index.by_project.setdefault((project, title), []).append(entry)
        index.by_content.setdefault(title, []).append(entry)
    # Rows are handed out in Todoist's order, not in whatever order the pages arrived in.
    for entries in index.by_section.values():
        entries.sort(key=lambda entry: entry.order)
    return index


def _api_get(url: str, token: str) -> object:
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise SystemExit("Todoist rejected the API token (401/403). Check it in Todoist -> "
                             "Settings -> Integrations -> Developer.") from error
        raise


def _api_list(base: str, path: str, token: str) -> list[dict]:
    """One collection, following `next_cursor` when the endpoint paginates."""
    items: list[dict] = []
    cursor = None
    while True:
        query = {"limit": "200"}
        if cursor:
            query["cursor"] = cursor
        payload = _api_get(f"{base}/{path}?{urllib.parse.urlencode(query)}", token)
        if isinstance(payload, list):          # REST v2 answers with the plain list
            items.extend(payload)
            return items
        items.extend(payload.get("results", []))
        cursor = payload.get("next_cursor")
        if not cursor:
            return items


def fetch_todoist(token: str) -> tuple[list[dict], list[dict], list[dict]]:
    """Active tasks, projects and sections, from whichever API version this token reaches.

    The sections are not optional: two tasks in one project may share a title and differ only by
    which section they sit in, and without their names the index cannot tell those two apart.
    """
    for base in (TODOIST_V1, TODOIST_V2):
        try:
            tasks = _api_list(base, "tasks", token)
        except urllib.error.HTTPError as error:
            if error.code == 404 and base is TODOIST_V1:
                continue                        # older token/endpoint: fall back to REST v2
            raise
        return tasks, _api_list(base, "projects", token), _api_list(base, "sections", token)
    return [], [], []


def stable_id(*parts: object) -> str:
    return str(uuid.uuid5(ID_NAMESPACE, "|".join(str(p) for p in parts)))


def project_name(path: str) -> str:
    """"Garten [6g62GH268rQjFQ92].csv" -> "Garten"."""
    stem = os.path.splitext(os.path.basename(path))[0]
    return re.sub(r"\s*\[[^\]]+\]\s*$", "", stem).strip() or stem


LABEL_PATTERN = re.compile(r"(?:^|\s)@([^\s@]+)")


def split_labels(title: str) -> tuple[str, list[str]]:
    labels = [m.group(1) for m in LABEL_PATTERN.finditer(title)]
    if not labels:
        return title, []
    return LABEL_PATTERN.sub(" ", title).strip(), labels


def join_notes(parts: Iterable[str]) -> str | None:
    kept = [p.strip() for p in parts if p and p.strip()]
    return "\n\n".join(kept) if kept else None


def note_line(text: str, when: str) -> str:
    """A Todoist comment, kept with its date — the app has no comments of its own."""
    stamp = when.strip()[:10] if when else ""
    return f"{stamp} · {text.strip()}" if stamp else text.strip()


class Converter:
    def __init__(self, options: argparse.Namespace, now: dt.datetime, ref: dt.date,
                 due_index: DueIndex | None = None):
        self.options = options
        self.due_index = due_index or DueIndex()
        self.now_iso = now.replace(microsecond=(now.microsecond // 1000) * 1000).isoformat().replace("+00:00", "Z")
        self.ref = ref
        self.projects: list[dict] = []
        self.sections: list[dict] = []
        self.tasks: list[dict] = []
        self.stats = Stats()
        # Everything imported is parked under one staging project — see staging_id(). Nothing
        # lands in the Inbox or beside the projects already on the device until the user moves
        # it there, which is the point: an import is a pile to sort, not a merge into the system.
        self.staging_name = options.import_project or f"Import {ref.isoformat()}"
        self.staging_created = False

    # -- assembling ------------------------------------------------------------------------

    def add_project(self, ident: str, name: str, parent_id: str | None, order: int, color: str) -> str:
        self.projects.append({
            "id": ident,
            "name": name,
            "colorHex": color,
            "parentId": parent_id,
            "sortOrder": order,
            "updatedAt": self.now_iso,
            "deletedAt": None,
        })
        return ident

    def add_section(self, ident: str, project_id: str, name: str, order: int) -> str:
        """A heading inside one project's list — never a project of its own.

        The importer drops a section whose `projectId` names no project in the same file, so this
        is only ever called once the project it belongs to has been written.
        """
        self.sections.append({
            "id": ident,
            "projectId": project_id,
            "name": name,
            "sortOrder": order,
            "updatedAt": self.now_iso,
            "deletedAt": None,
        })
        return ident

    def staging_id(self) -> str | None:
        """The "Import 2026-08-13" project everything hangs under, created on the first task."""
        if self.options.no_import_project:
            return None
        ident = stable_id("import-root", self.staging_name)
        if not self.staging_created:
            self.add_project(ident, self.staging_name, None, 0, PROJECT_COLORS[0])
            self.staging_created = True
            self.stats.projects += 1
            self.stats.staging = self.staging_name
        return ident

    def convert_file(self, path: str, index: int) -> None:
        with open(path, newline="", encoding="utf-8-sig") as handle:
            rows = list(csv.DictReader(handle))

        name = project_name(path)
        is_inbox = name.strip().lower() == self.options.inbox_name.strip().lower()
        color = PROJECT_COLORS[(index + 1) % len(PROJECT_COLORS)]
        # The Inbox gets a project of its own like every other file. It used to drop its tasks
        # straight into the staging project, where they were indistinguishable from the pile
        # itself — there was no "Inbox" to open. Without a staging project it stays the Inbox.
        root_id: str | None = None
        if not is_inbox or not self.options.no_import_project:
            root_id = stable_id("project", name)

        # id -> (name, position), created on the first task that lands in it. The position is
        # where the section row stands among the file's section rows, which is the order the
        # project's headings are drawn in; an empty section leaves a gap in it and nothing minds.
        pending_sections: dict[str, tuple[str, int]] = {}
        section_id: str | None = None
        section_name: str | None = None
        seen_sections = 0
        current_parent: dict | None = None             # last INDENT 1 task, for subtasks
        last_task: dict | None = None                  # last task of any level, for notes
        order = 0
        created_root = False

        for row_index, row in enumerate(rows):
            kind = (row.get("TYPE") or "").strip()
            content = (row.get("CONTENT") or "").strip()
            if kind == "section":
                section_name = content
                section_id = stable_id("section", name, content, row_index)
                pending_sections[section_id] = (content, seen_sections)
                seen_sections += 1
                current_parent = None
                last_task = None
                continue
            if kind == "note":
                if last_task is not None and content:
                    last_task["_notes"].append(note_line(content, row.get("DATE") or ""))
                    self.stats.notes += 1
                continue
            if kind != "task" or not content:
                continue

            # Projects and sections are created lazily: an empty section would otherwise import
            # as a heading with nothing under it, and Todoist exports plenty of those.
            staging = self.staging_id()
            if root_id and not created_root:
                self.add_project(root_id, name, staging, len(self.projects), color)
                created_root = True
                self.stats.projects += 1
            # A section groups the project's own list, so it needs a project to hang from. The
            # one row that has none is a task in the Todoist Inbox imported with
            # --no-import-project, which lands in Cadence's Inbox — and the Inbox is not a
            # project, so there is nothing there for a heading to belong to. Its tasks arrive
            # ungrouped rather than dragging a project-less section along.
            grouped_by: str | None = None
            if section_id and root_id:
                pending = pending_sections.pop(section_id, None)
                if pending is not None:
                    self.add_section(section_id, root_id, pending[0], pending[1])
                    self.stats.sections += 1
                grouped_by = section_id

            # The Inbox with no staging project is the only file whose rows have no project of
            # their own; everything else sits in the project its file became.
            task = self.convert_task(row, row_index, name, root_id or staging, grouped_by,
                                     order, section_name)
            order += 1
            indent = int((row.get("INDENT") or "1").strip() or 1)
            if indent >= 2 and current_parent is not None:
                task["parentId"] = current_parent["id"]
                # A subtask inherits its parent's project *and* its section: Cadence moves the
                # two together, and a step drawn under a different heading than its parent would
                # be a step nobody finds.
                task["projectId"] = current_parent["projectId"]
                task["sectionId"] = current_parent["sectionId"]
                self.stats.subtasks += 1
            else:
                current_parent = task
                self.stats.tasks += 1
            self.tasks.append(task)
            last_task = task

    def convert_task(self, row: dict, row_index: int, file_name: str,
                     project_id: str | None, section_id: str | None, order: int,
                     section_name: str | None = None) -> dict:
        title = (row.get("CONTENT") or "").strip()
        extra_notes: list[str] = []
        if self.options.strip_labels:
            title, labels = split_labels(title)
            if labels:
                extra_notes.append("@" + " @".join(labels))

        priority_raw = (row.get("PRIORITY") or "").strip()
        try:
            level = int(priority_raw)
        except ValueError:
            level = 4
        if self.options.invert_priority:
            level = 5 - level
        level = min(4, max(1, level))

        date_text = (row.get("DATE") or "").strip()
        recurrence, parsed = parse_date_field(date_text, self.ref, self.options.bare_year)
        due_date: dt.date | None = None
        due_time: str | None = None
        rule: dict | None = None
        if recurrence is not None:
            rule = recurrence.rule
            due_time = recurrence.time
            if self.options.recurring_due == "next":
                due_date = recurrence.anchor
            self.stats.recurring += 1
        elif parsed is not None:
            due_date = parsed.date
            due_time = parsed.time
            if due_date is not None:
                self.stats.dated += 1
        if date_text and recurrence is None and (parsed is None or parsed.date is None):
            self.stats.unparsed.append(f"{file_name}: {title!r} -> DATE {date_text!r}")
            extra_notes.append(f"Todoist: {date_text}")

        # Todoist itself is the authority on *when*: the export writes a recurring task's rule
        # and drops its next occurrence entirely, so a yearly task read from the CSV alone lands
        # on today rather than on the 23rd of December. Where the API answered, its date and
        # time replace whatever the phrase implied — the rule stays as parsed. The section goes
        # with the title, or two tasks named the same in two sections both take the second one's
        # date.
        exact = self.due_index.lookup(file_name, title, section_name)
        if exact is not None:
            due_date, due_time = exact[0], exact[1] or due_time
            self.stats.exact += 1

        deadline = (row.get("DEADLINE") or "").strip()
        if deadline:
            parsed_deadline = parse_absolute_date(deadline, self.ref, self.options.bare_year)
            if parsed_deadline and parsed_deadline.date:
                if due_date is None and rule is None:
                    due_date = parsed_deadline.date
                    self.stats.dated += 1
                else:
                    extra_notes.append(f"Deadline: {parsed_deadline.date.isoformat()}")
            else:
                extra_notes.append(f"Deadline: {deadline}")

        duration = (row.get("DURATION") or "").strip()
        if duration:
            unit = (row.get("DURATION_UNIT") or "").strip() or "minute"
            extra_notes.append(f"Duration: {duration} {unit}")

        task = {
            "id": stable_id("task", file_name, row_index, title),
            "title": title,
            "_notes": [(row.get("DESCRIPTION") or "").strip(), *extra_notes],
            "priority": level,
            "projectId": project_id,
            "sectionId": section_id,
            "parentId": None,
            "spawnedFromId": None,
            "dueDate": due_date.isoformat() if due_date else None,
            "dueTime": due_time,
            "reminderTime": None,
            "completedAt": None,
            "createdAt": self.now_iso,
            "sortOrder": order,
            "recurrence": rule,
            "updatedAt": self.now_iso,
            "deletedAt": None,
        }
        return task

    def document(self) -> dict:
        tasks = []
        for task in self.tasks:
            task = dict(task)
            task["notes"] = join_notes(task.pop("_notes"))
            tasks.append(task)
        return {
            "format": BACKUP_FORMAT,
            "version": BACKUP_VERSION,
            "exportedAt": self.now_iso,
            "projects": self.projects,
            "sections": self.sections,
            "tasks": tasks,
        }


def collect_csv_paths(inputs: Sequence[str]) -> list[str]:
    paths: list[str] = []
    for entry in inputs:
        if os.path.isdir(entry):
            paths.extend(
                os.path.join(entry, name)
                for name in sorted(os.listdir(entry))
                if name.lower().endswith(".csv")
            )
        elif entry.lower().endswith(".csv"):
            paths.append(entry)
        else:
            raise SystemExit(f"not a CSV file or directory: {entry}")
    if not paths:
        raise SystemExit("no CSV files found")
    return paths


def ordered_paths(paths: Sequence[str], options: argparse.Namespace) -> list[str]:
    """The Inbox first, so its tasks keep the lowest sort orders in the staging project."""
    return sorted(paths, key=lambda p: (project_name(p).lower() != options.inbox_name.lower(),
                                        project_name(p).lower()))


def convert(paths: Sequence[str], options: argparse.Namespace, now: dt.datetime, ref: dt.date,
            first_index: int = 0, due_index: DueIndex | None = None) -> tuple[dict, Stats]:
    converter = Converter(options, now, ref, due_index)
    for index, path in enumerate(ordered_paths(paths, options), start=first_index):
        converter.convert_file(path, index)
    return converter.document(), converter.stats


def convert_split(paths: Sequence[str], options: argparse.Namespace, now: dt.datetime,
                  ref: dt.date, due_index: DueIndex | None = None) -> list[tuple[str, dict, Stats]]:
    """One document per Todoist project, for importing them a few at a time.

    Every document carries the same staging project — its id is derived from the name, so the
    twentieth file merges into the "Import 2026-08-13" the first one created rather than making
    a second pile.
    """
    documents = []
    for index, path in enumerate(ordered_paths(paths, options)):
        document, stats = convert([path], options, now, ref, first_index=index,
                                  due_index=due_index)
        # Todoist exports a CSV for a project that holds nothing; a file whose only content
        # would be the staging project is not worth handing to the importer.
        if document["tasks"]:
            documents.append((file_slug(project_name(path)), document, stats))
    return documents


def file_slug(name: str) -> str:
    """"wöchentlich" -> "cadence-woechentlich.json", so the files sort the way the projects do."""
    slug = re.sub(r"[^a-z0-9]+", "-", fold(name)).strip("-")
    return f"cadence-{slug or 'export'}.json"


def write_document(path: str, document: dict) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a Todoist CSV export into a Cadence backup file.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("inputs", nargs="*", help="CSV files, or a directory of them")
    parser.add_argument("-o", "--out", default=DEFAULT_OUT,
                        help=f"output file, or the directory to fill with --split "
                             f"(default: {DEFAULT_OUT}, {DEFAULT_SPLIT_OUT}/ when splitting)")
    parser.add_argument("--split", action="store_true",
                        help="write one JSON per Todoist project instead of one for the whole "
                             "export — import them a few at a time")
    parser.add_argument("--dry-run", action="store_true", help="parse and report, write nothing")
    parser.add_argument("--inbox-name", default="Inbox",
                        help="the export file holding the Todoist Inbox (default: Inbox)")
    parser.add_argument("--import-project", metavar="NAME",
                        help="name of the staging project everything is parked under "
                             "(default: 'Import <today>')")
    parser.add_argument("--no-import-project", action="store_true",
                        help="import straight into projects and the Inbox, with no staging "
                             "project in between")
    parser.add_argument("--strip-labels", action="store_true",
                        help="move Todoist @labels out of the title into the notes")
    parser.add_argument("--invert-priority", action="store_true",
                        help="read PRIORITY 4 as P1 (use when your export numbers them the API way)")
    parser.add_argument("--bare-year", choices=("current", "next-occurrence"), default="current",
                        help="how to year a date exported without one (default: current year)")
    parser.add_argument("--recurring-due", choices=("next", "none"), default="next",
                        help="whether a recurring task gets its next date as a due date")
    parser.add_argument("--todoist-token", metavar="TOKEN",
                        help="Todoist API token (or set TODOIST_API_TOKEN). The export leaves a "
                             "recurring task's next occurrence out, so this is what makes the "
                             "dates and times exactly those Todoist shows")
    parser.add_argument("--today", help="reference date for relative dates, ISO (default: today)")
    parser.add_argument("--self-test", action="store_true", help="run the parser's unit tests")
    options = parser.parse_args(argv)

    if options.self_test:
        result = unittest.main(argv=["todoist_import"], exit=False, verbosity=2).result
        return 0 if result.wasSuccessful() else 1

    if not options.inputs:
        parser.error("give at least one CSV file or a directory (or --self-test)")

    ref = dt.date.fromisoformat(options.today) if options.today else dt.date.today()
    now = dt.datetime.now(dt.timezone.utc)
    paths = collect_csv_paths(options.inputs)

    token = options.todoist_token or os.environ.get("TODOIST_API_TOKEN")
    due_index = None
    if token:
        tasks, api_projects, api_sections = fetch_todoist(token)
        due_index = build_due_index(tasks, api_projects, api_sections)
        print(f"Todoist API: {len(tasks)} task(s), {len(due_index)} with a due date")

    if options.split:
        documents = convert_split(paths, options, now, ref, due_index)
        total = sum_stats(stats for _, _, stats in documents)
        report(len(paths), total)
        if options.dry_run:
            for name, _, stats in documents:
                print(f"  {name}: {stats.tasks + stats.subtasks} task(s)")
            print("dry run: nothing written")
            return 0
        directory = options.out if options.out != DEFAULT_OUT else DEFAULT_SPLIT_OUT
        os.makedirs(directory, exist_ok=True)
        for name, document, stats in documents:
            write_document(os.path.join(directory, name), document)
            print(f"  {name}: {stats.tasks + stats.subtasks} task(s)")
        print(f"wrote {len(documents)} file(s) to {directory}/ — import them under "
              f"Settings -> Backup, one, several or all at once")
        return 0

    document, stats = convert(paths, options, now, ref, due_index=due_index)
    report(len(paths), stats)
    if options.dry_run:
        print("dry run: nothing written")
        return 0

    write_document(options.out, document)
    print(f"wrote {options.out} — import it under Settings -> Backup")
    return 0


def sum_stats(many: Iterable[Stats]) -> Stats:
    parts = list(many)
    total = Stats()
    for stats in parts:
        total.projects += stats.projects
        total.sections += stats.sections
        total.tasks += stats.tasks
        total.subtasks += stats.subtasks
        total.notes += stats.notes
        total.recurring += stats.recurring
        total.dated += stats.dated
        total.exact += stats.exact
        total.staging = total.staging or stats.staging
        total.unparsed.extend(stats.unparsed)
    # Every split document repeats the one staging project, and the import merges those copies
    # back into one. Counting it once keeps the report about the export rather than the files.
    if total.staging:
        total.projects -= len([s for s in parts if s.staging]) - 1
    return total


def report(files: int, stats: Stats) -> None:
    print(f"{files} file(s) -> {stats.projects} project(s), {stats.sections} section(s), "
          f"{stats.tasks} task(s), {stats.subtasks} subtask(s), {stats.notes} note(s)")
    print(f"  {stats.recurring} recurring, {stats.dated} dated"
          + (f", {stats.exact} dated exactly from the Todoist API" if stats.exact else ""))
    if stats.staging:
        print(f"  all of it parked under the project {stats.staging!r} — move a task out of "
              f"there to file it into your own projects")
    for warning in stats.unparsed:
        print(f"  ! unreadable date, kept in the notes — {warning}", file=sys.stderr)


# ----------------------------------------------------------------------------------- self-test


REF = dt.date(2026, 8, 13)  # a Thursday


class RecurrenceTest(unittest.TestCase):
    def rule(self, text):
        parsed = parse_recurrence(text, REF)
        self.assertIsNotNone(parsed, f"{text!r} did not read as a repeat phrase")
        return parsed.rule

    def test_german_intervals(self):
        self.assertEqual(self.rule("jeden Tag")["unit"], "DAY")
        self.assertEqual(self.rule("täglich")["unit"], "DAY")
        self.assertEqual(self.rule("jeden Monat")["unit"], "MONTH")
        self.assertEqual(self.rule("jährlich")["unit"], "YEAR")
        self.assertEqual(self.rule("jedes Jahr")["unit"], "YEAR")
        self.assertEqual(self.rule("alle 2 Monate")["interval"], 2)
        self.assertEqual(self.rule("jedes 3 Monate")["interval"], 3)
        self.assertEqual(self.rule("alle 5 wochen"), dict(self.rule("alle 5 Wochen")))
        self.assertEqual(self.rule("alle vier Tage")["interval"], 4)
        self.assertEqual(self.rule("alle 2 Jahre")["interval"], 2)

    def test_english_intervals(self):
        self.assertEqual(self.rule("every 1 months")["unit"], "MONTH")
        self.assertEqual(self.rule("every 4 months")["interval"], 4)
        self.assertEqual(self.rule("every other week")["interval"], 2)
        self.assertEqual(self.rule("weekly")["unit"], "WEEK")

    def test_quarter_is_three_months(self):
        rule = self.rule("jedes Quartal")
        self.assertEqual((rule["unit"], rule["interval"]), ("MONTH", 3))
        self.assertEqual(self.rule("halbjährlich")["interval"], 6)

    def test_weekdays(self):
        rule = self.rule("jeden donnerstag")
        self.assertEqual(rule["unit"], "WEEK")
        self.assertEqual(rule["daysOfWeek"], ["THURSDAY"])
        self.assertEqual(self.rule("every thu")["daysOfWeek"], ["THURSDAY"])
        self.assertEqual(self.rule("jeden Sonntag")["daysOfWeek"], ["SUNDAY"])
        self.assertEqual(self.rule("every mon, wed")["daysOfWeek"], ["MONDAY", "WEDNESDAY"])
        self.assertEqual(self.rule("jeden Werktag")["daysOfWeek"],
                         ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"])

    def test_weekday_anchor_is_the_next_such_day(self):
        parsed = parse_recurrence("every thu", REF)          # REF is itself a Thursday
        self.assertEqual(parsed.anchor, REF)
        self.assertEqual(parse_recurrence("jeden Sonntag", REF).anchor, dt.date(2026, 8, 16))

    def test_after_completion(self):
        self.assertEqual(self.rule("every! 3 months")["mode"], "AFTER_COMPLETION")
        self.assertEqual(self.rule("every! 3 months")["interval"], 3)
        self.assertEqual(self.rule("jeden Monat")["mode"], "SCHEDULE")

    def test_day_of_month(self):
        rule = self.rule("jeden 15. des Monats")
        self.assertEqual((rule["unit"], rule["dayOfMonth"]), ("MONTH", 15))
        self.assertEqual(parse_recurrence("jeden 15. des Monats", REF).anchor, dt.date(2026, 8, 15))
        self.assertEqual(self.rule("every 3rd month")["interval"], 3)

    def test_last_day_and_nth_weekday(self):
        self.assertEqual(self.rule("every last day of the month")["monthlyMode"], "LAST_DAY")
        nth = self.rule("every 2nd tuesday of the month")
        self.assertEqual(nth["monthlyMode"], "NTH_WEEKDAY")
        self.assertEqual((nth["nthWeek"], nth["nthDayOfWeek"]), (2, "TUESDAY"))

    def test_time_of_day(self):
        parsed = parse_recurrence("jeden Tag um 9:30", REF)
        self.assertEqual(parsed.time, "09:30")
        self.assertEqual(parse_recurrence("every day at 7pm", REF).time, "19:00")

    def test_not_a_repeat_phrase(self):
        self.assertIsNone(parse_recurrence("15 Mar", REF))
        self.assertIsNone(parse_recurrence("23 Jun 2027", REF))
        self.assertIsNone(parse_recurrence("", REF))


class DateTest(unittest.TestCase):
    def date(self, text, bare_year="current"):
        parsed = parse_absolute_date(text, REF, bare_year)
        self.assertIsNotNone(parsed, f"{text!r} did not read as a date")
        return parsed.date

    def test_formats_seen_in_the_export(self):
        self.assertEqual(self.date("23 Jun 2027"), dt.date(2027, 6, 23))
        self.assertEqual(self.date("15 Mar"), dt.date(2026, 3, 15))
        self.assertEqual(self.date("15 Apr."), dt.date(2026, 4, 15))
        self.assertEqual(self.date("25 Juli"), dt.date(2026, 7, 25))
        self.assertEqual(self.date("25 Sept."), dt.date(2026, 9, 25))
        self.assertEqual(self.date("30 März"), dt.date(2026, 3, 30))
        self.assertEqual(self.date("8. Jul"), dt.date(2026, 7, 8))
        self.assertEqual(self.date("21. Aug"), dt.date(2026, 8, 21))
        self.assertEqual(self.date("1. Mai 2026"), dt.date(2026, 5, 1))
        self.assertEqual(self.date("1 Sep. 2033"), dt.date(2033, 9, 1))
        self.assertEqual(self.date("23 Dez."), dt.date(2026, 12, 23))
        self.assertEqual(self.date("2026-07-19"), dt.date(2026, 7, 19))

    def test_bare_year_can_roll_forward(self):
        self.assertEqual(self.date("15 Mar", "next-occurrence"), dt.date(2027, 3, 15))
        self.assertEqual(self.date("23 Dez.", "next-occurrence"), dt.date(2026, 12, 23))

    def test_relative_and_weekday(self):
        self.assertEqual(self.date("heute"), REF)
        self.assertEqual(self.date("morgen"), dt.date(2026, 8, 14))
        self.assertEqual(self.date("Montag"), dt.date(2026, 8, 17))

    def test_time_is_kept(self):
        parsed = parse_absolute_date("15 Mar 09:30", REF)
        self.assertEqual((parsed.date, parsed.time), (dt.date(2026, 3, 15), "09:30"))

    def test_clamps_to_the_month(self):
        self.assertEqual(self.date("31 Feb 2027"), dt.date(2027, 2, 28))

    def test_nonsense_is_not_a_date(self):
        self.assertIsNone(parse_absolute_date("", REF))
        self.assertIsNone(parse_absolute_date("irgendwann", REF))


class DueIndexTest(unittest.TestCase):
    """The half of the API path that is not a network call."""

    def test_a_plain_date_has_no_time(self):
        self.assertEqual(parse_api_due({"date": "2026-12-23"}), (dt.date(2026, 12, 23), None))

    def test_a_floating_datetime_keeps_its_clock_time(self):
        self.assertEqual(parse_api_due({"date": "2026-12-23T09:30:00"}),
                         (dt.date(2026, 12, 23), "09:30"))

    def test_a_utc_datetime_is_shown_in_the_tasks_own_zone(self):
        # 08:30 UTC is 09:30 in Berlin, and Berlin is what the user set the reminder in.
        parsed = parse_api_due({"date": "2026-12-23T08:30:00Z", "timezone": "Europe/Berlin"})
        self.assertEqual(parsed, (dt.date(2026, 12, 23), "09:30"))

    def test_no_due_is_not_a_date(self):
        self.assertIsNone(parse_api_due({}))
        self.assertIsNone(parse_api_due({"date": "not a date"}))

    def test_labels_are_stripped_before_matching_a_csv_title(self):
        index = build_due_index(
            [{"content": "Mirabelle schneiden", "project_id": "1", "due": {"date": "2027-03-15"}}],
            [{"id": "1", "name": "Garten"}],
        )
        self.assertEqual(index.lookup("Garten", "Mirabelle schneiden @Baum"),
                         (dt.date(2027, 3, 15), None))

    def test_a_title_meaning_two_dates_only_matches_with_its_project(self):
        index = build_due_index(
            [
                {"content": "Backup", "project_id": "1", "due": {"date": "2026-09-01"}},
                {"content": "Backup", "project_id": "2", "due": {"date": "2026-10-01"}},
            ],
            [{"id": "1", "name": "monatlich"}, {"id": "2", "name": "quartal"}],
        )
        self.assertEqual(index.lookup("quartal", "Backup"), (dt.date(2026, 10, 1), None))
        self.assertIsNone(index.lookup("woanders", "Backup"))

    def test_the_same_title_in_two_sections_keeps_both_dates(self):
        # The bug this replaced: a (project, title) key kept whichever task the API listed last,
        # so every "Gießen" in the project imported with the 15th of December.
        index = build_due_index(
            [
                {"content": "Gießen", "project_id": "1", "section_id": "10",
                 "due": {"date": "2026-09-01"}},
                {"content": "Gießen", "project_id": "1", "section_id": "11",
                 "due": {"date": "2026-12-15"}},
            ],
            [{"id": "1", "name": "Garten"}],
            [{"id": "10", "name": "Beet"}, {"id": "11", "name": "Gewächshaus"}],
        )
        self.assertEqual(index.lookup("Garten", "Gießen", "Beet"), (dt.date(2026, 9, 1), None))
        self.assertEqual(index.lookup("Garten", "Gießen", "Gewächshaus"),
                         (dt.date(2026, 12, 15), None))
        # With no section to go on, neither date is the one this row means.
        self.assertIsNone(index.lookup("Garten", "Gießen"))

    def test_a_title_repeated_inside_one_section_is_handed_out_in_todoists_order(self):
        index = build_due_index(
            [
                {"content": "Gießen", "project_id": "1", "section_id": "10", "child_order": 2,
                 "due": {"date": "2026-12-15"}},
                {"content": "Gießen", "project_id": "1", "section_id": "10", "child_order": 1,
                 "due": {"date": "2026-09-01"}},
            ],
            [{"id": "1", "name": "Garten"}],
            [{"id": "10", "name": "Beet"}],
        )
        self.assertEqual(index.lookup("Garten", "Gießen", "Beet"), (dt.date(2026, 9, 1), None))
        self.assertEqual(index.lookup("Garten", "Gießen", "Beet"), (dt.date(2026, 12, 15), None))
        # Three CSV rows against two API tasks: the extra one keeps what the CSV implied.
        self.assertIsNone(index.lookup("Garten", "Gießen", "Beet"))

    def test_one_date_answers_every_row_that_shares_the_title(self):
        index = build_due_index(
            [
                {"content": "Gießen", "project_id": "1", "section_id": "10",
                 "due": {"date": "2026-09-01"}},
                {"content": "Gießen", "project_id": "1", "section_id": "11",
                 "due": {"date": "2026-09-01"}},
            ],
            [{"id": "1", "name": "Garten"}],
            [{"id": "10", "name": "Beet"}, {"id": "11", "name": "Gewächshaus"}],
        )
        self.assertEqual(index.lookup("Garten", "Gießen"), (dt.date(2026, 9, 1), None))
        self.assertEqual(index.lookup("Garten", "Gießen", "Beet"), (dt.date(2026, 9, 1), None))


def _options(**overrides) -> argparse.Namespace:
    base = dict(inbox_name="Inbox", strip_labels=False, invert_priority=False,
                bare_year="current", recurring_due="next", import_project=None,
                no_import_project=False, split=False, todoist_token=None)
    base.update(overrides)
    return argparse.Namespace(**base)


SAMPLE = """TYPE,CONTENT,DESCRIPTION,IS_COLLAPSED,PRIORITY,INDENT,AUTHOR,RESPONSIBLE,DATE,DATE_LANG,TIMEZONE,DURATION,DURATION_UNIT,DEADLINE,DEADLINE_LANG
meta,view_style=list,,,,,,,,,,,,,
,,,,,,,,,,,,,,
task,Fenster ölen @Haus,Mit Leinöl,,2,1,,,jährlich,de,Europe/Berlin,,,,
task,Bad,,,4,2,Andreas (8908577),,,,Europe/Berlin,,,,
note,Ballistol benutzt,,,,,Andreas (8908577),,2018-06-08T07:02:05.000000Z,,,,,,
,,,,,,,,,,,,,,
section,Leer,,False,,,,,,,,,,,
section,Ofen,,False,,,,,,,,,,,
task,reinigen,,,1,1,Andreas (8908577),,,,Europe/Berlin,,,2026-07-19,de
"""


class ConversionTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "wohnung [6Crg8jQ886Wh4P5g].csv")
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        self.document, self.stats = convert(
            [self.path], _options(), dt.datetime(2026, 8, 13, 12, 0, tzinfo=dt.timezone.utc), REF
        )

    def task(self, title):
        return next(t for t in self.document["tasks"] if t["title"].startswith(title))

    def section(self, name):
        return next(s for s in self.document["sections"] if s["name"] == name)

    def test_project_name_drops_the_todoist_id(self):
        self.assertEqual(project_name(self.path), "wohnung")
        self.assertEqual([p["name"] for p in self.document["projects"]],
                         ["Import 2026-08-13", "wohnung"])

    def test_empty_sections_are_not_imported(self):
        # "Leer" is a section row no task follows, and Todoist exports plenty of those. A
        # heading with nothing under it is noise in the project's list, so it is never written —
        # neither as a section nor, as it once was, as an empty project.
        self.assertEqual([s["name"] for s in self.document["sections"]], ["Ofen"])
        self.assertNotIn("wohnung · Leer", [p["name"] for p in self.document["projects"]])

    def test_everything_is_parked_under_one_staging_project(self):
        staging = self.document["projects"][0]
        self.assertEqual(staging["name"], "Import 2026-08-13")
        self.assertIsNone(staging["parentId"])
        # Nothing lands beside the projects already on the device: every imported project is a
        # child of the staging one, and no task is left in the Inbox.
        self.assertTrue(all(p["parentId"] == staging["id"]
                            for p in self.document["projects"][1:]))
        self.assertTrue(all(t["projectId"] is not None for t in self.document["tasks"]))
        self.assertEqual(self.stats.staging, "Import 2026-08-13")

    def test_a_section_belongs_to_the_project_it_was_exported_from(self):
        # A Todoist section is a heading inside one project's list, and Cadence has exactly that
        # now. It used to arrive as a sibling project named "wohnung · Ofen", because the app had
        # no sections and the staging project had taken the one level of nesting projects allow.
        wohnung = next(p for p in self.document["projects"] if p["name"] == "wohnung")
        ofen = self.section("Ofen")
        self.assertEqual(ofen["projectId"], wohnung["id"])
        # "Leer" stands ahead of it in the file, so "Ofen" is the project's second heading.
        self.assertEqual(ofen["sortOrder"], 1)
        self.assertEqual(self.task("reinigen")["sectionId"], ofen["id"])

    def test_a_task_in_a_section_still_belongs_to_the_project(self):
        # The section groups the row; it does not own it. A task whose projectId named the
        # section instead would vanish from the project it was exported from.
        wohnung = next(p for p in self.document["projects"] if p["name"] == "wohnung")
        reinigen = self.task("reinigen")
        self.assertEqual(reinigen["projectId"], wohnung["id"])
        self.assertNotEqual(reinigen["projectId"], reinigen["sectionId"])
        # A row that stands above every section row is in the project's ungrouped band.
        self.assertIsNone(self.task("Fenster")["sectionId"])

    def test_the_staging_project_can_be_renamed_or_skipped(self):
        named, _ = convert([self.path], _options(import_project="Todoist"),
                           dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        self.assertEqual(named["projects"][0]["name"], "Todoist")

        flat, stats = convert([self.path], _options(no_import_project=True),
                              dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        self.assertEqual([p["name"] for p in flat["projects"]], ["wohnung"])
        self.assertIsNone(flat["projects"][0]["parentId"])
        # Without the staging project the section still hangs off "wohnung": nothing about the
        # pile decides where a heading belongs any more.
        self.assertEqual([(s["name"], s["projectId"]) for s in flat["sections"]],
                         [("Ofen", flat["projects"][0]["id"])])
        self.assertIsNone(stats.staging)

    def test_indent_two_is_a_subtask_sharing_the_parents_project(self):
        parent = self.task("Fenster")
        child = self.task("Bad")
        self.assertEqual(child["parentId"], parent["id"])
        self.assertEqual(child["projectId"], parent["projectId"])
        self.assertEqual(self.stats.subtasks, 1)

    def test_note_rows_land_in_the_task_above_them(self):
        self.assertIn("2018-06-08 · Ballistol benutzt", self.task("Bad")["notes"])

    def test_description_becomes_notes_and_priority_carries_over(self):
        task = self.task("Fenster")
        self.assertIn("Mit Leinöl", task["notes"])
        self.assertEqual(task["priority"], 2)
        self.assertEqual(task["recurrence"]["unit"], "YEAR")

    def test_deadline_fills_an_empty_due_date(self):
        self.assertEqual(self.task("reinigen")["dueDate"], "2026-07-19")

    def test_labels_stay_in_the_title_unless_asked(self):
        self.assertIn("@Haus", self.task("Fenster")["title"])
        stripped, _ = convert([self.path], _options(strip_labels=True),
                              dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        task = next(t for t in stripped["tasks"] if t["title"].startswith("Fenster"))
        self.assertEqual(task["title"], "Fenster ölen")
        self.assertIn("@Haus", task["notes"])

    def test_the_inbox_arrives_as_a_project_of_its_own(self):
        inbox = os.path.join(self.dir, "Inbox [6Crg8jQ8644Gg3r6].csv")
        with open(inbox, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        document, _ = convert([inbox], _options(),
                              dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)

        # Dropping these into the staging project itself left no "Inbox" to open — the tasks
        # were there and looked like the pile's own.
        staging, project = document["projects"][0], document["projects"][1]
        self.assertEqual(project["name"], "Inbox")
        self.assertEqual(project["parentId"], staging["id"])
        task = next(t for t in document["tasks"] if t["title"].startswith("Fenster"))
        self.assertEqual(task["projectId"], project["id"])

    def test_without_a_staging_project_the_inbox_is_the_inbox(self):
        inbox = os.path.join(self.dir, "Inbox [6Crg8jQ8644Gg3r6].csv")
        with open(inbox, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        flat, _ = convert([inbox], _options(no_import_project=True),
                          dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        loose = next(t for t in flat["tasks"] if t["title"].startswith("Fenster"))
        self.assertIsNone(loose["projectId"])
        # A section groups one project's list and the Inbox is not a project, so the file's
        # section rows have nothing to belong to: their tasks arrive ungrouped rather than
        # carrying a sectionId the importer would only drop again.
        self.assertEqual(flat["sections"], [])
        self.assertTrue(all(t["sectionId"] is None for t in flat["tasks"]))

    def test_the_api_supplies_the_date_the_export_left_out(self):
        # What the CSV says is "jährlich" and nothing more; Todoist knows it is due 23 December.
        api_tasks = [{
            "content": "Fenster ölen",
            "project_id": "220474322",
            "due": {"date": "2026-12-23", "string": "jährlich", "is_recurring": True},
        }]
        index = build_due_index(api_tasks, [{"id": "220474322", "name": "wohnung"}])
        document, stats = convert([self.path], _options(),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF,
                                  due_index=index)

        task = next(t for t in document["tasks"] if t["title"].startswith("Fenster"))
        self.assertEqual(task["dueDate"], "2026-12-23")
        self.assertEqual(task["recurrence"]["unit"], "YEAR")   # the rule still comes from the CSV
        self.assertEqual(stats.exact, 1)
        # A task the API did not answer for keeps what the CSV implied.
        self.assertEqual(self.task("reinigen")["dueDate"], "2026-07-19")

    def test_two_sections_sharing_a_task_title_get_their_own_dates(self):
        # Both rows are one project's tasks and differ only by the heading they sit under, so
        # the *dates* are what has to come apart: every "Gießen" in the project used to import
        # with the last one's date, because the index was keyed on the project alone.
        garten = os.path.join(self.dir, "garten [6g62GH268rQjFQ92].csv")
        with open(garten, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE.splitlines()[0] + "\n")
            handle.write("section,Beet,,False,,,,,,,,,,,\n")
            handle.write("task,Gießen,,,4,1,,,jährlich,de,Europe/Berlin,,,,\n")
            handle.write("section,Gewächshaus,,False,,,,,,,,,,,\n")
            handle.write("task,Gießen,,,4,1,,,jährlich,de,Europe/Berlin,,,,\n")

        index = build_due_index(
            [
                {"content": "Gießen", "project_id": "7", "section_id": "10",
                 "due": {"date": "2026-09-01", "is_recurring": True}},
                {"content": "Gießen", "project_id": "7", "section_id": "11",
                 "due": {"date": "2026-12-15", "is_recurring": True}},
            ],
            [{"id": "7", "name": "garten"}],
            [{"id": "10", "name": "Beet"}, {"id": "11", "name": "Gewächshaus"}],
        )
        document, stats = convert([garten], _options(),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF,
                                  due_index=index)

        sections = {s["id"]: s["name"] for s in document["sections"]}
        dates = {sections[t["sectionId"]]: t["dueDate"] for t in document["tasks"]}
        self.assertEqual(dates, {"Beet": "2026-09-01", "Gewächshaus": "2026-12-15"})
        self.assertEqual(stats.exact, 2)
        # Both rows stayed in "garten" — only the heading above them differs.
        garten = next(p for p in document["projects"] if p["name"] == "garten")
        self.assertEqual({t["projectId"] for t in document["tasks"]}, {garten["id"]})

    def test_ids_are_uuids_and_stable_across_runs(self):
        again, _ = convert([self.path], _options(),
                           dt.datetime(2027, 1, 1, tzinfo=dt.timezone.utc), REF)
        self.assertEqual([t["id"] for t in again["tasks"]],
                         [t["id"] for t in self.document["tasks"]])
        for task in self.document["tasks"]:
            uuid.UUID(task["id"])  # raises when it is not a UUID — sync's columns are `uuid`

    def test_split_writes_one_document_per_project(self):
        second = os.path.join(self.dir, "garten [6g62GH268rQjFQ92].csv")
        with open(second, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        documents = convert_split([self.path, second], _options(split=True),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)

        self.assertEqual([name for name, _, _ in documents],
                         ["cadence-garten.json", "cadence-wohnung.json"])
        for _, document, _ in documents:
            self.assertEqual(document["format"], "cadence.backup")
            self.assertEqual(len(document["tasks"]), 3)

    def test_split_skips_a_project_with_no_tasks(self):
        empty = os.path.join(self.dir, "leer [6f386p6864mQPgxH].csv")
        with open(empty, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE.splitlines()[0] + "\nmeta,view_style=list,,,,,,,,,,,,,\n")
        documents = convert_split([self.path, empty], _options(split=True),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        self.assertEqual([name for name, _, _ in documents], ["cadence-wohnung.json"])

    def test_every_split_document_carries_the_same_staging_project(self):
        second = os.path.join(self.dir, "garten [6g62GH268rQjFQ92].csv")
        with open(second, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        documents = convert_split([self.path, second], _options(split=True),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)

        # Same id, so importing the files one at a time merges them into one pile rather than
        # leaving an "Import 2026-08-13" per file.
        staging = {document["projects"][0]["id"] for _, document, _ in documents}
        self.assertEqual(len(staging), 1)
        self.assertEqual({document["projects"][0]["name"] for _, document, _ in documents},
                         {"Import 2026-08-13"})

    def test_the_summed_report_counts_the_staging_project_once(self):
        documents = convert_split([self.path, self.path], _options(split=True),
                                  dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        total = sum_stats(stats for _, _, stats in documents)
        self.assertEqual(total.projects, 3)   # one staging + "wohnung" twice
        self.assertEqual(total.sections, 2)

    def test_document_is_the_published_backup_shape(self):
        self.assertEqual(self.document["format"], "cadence.backup")
        self.assertEqual(self.document["version"], BACKUP_VERSION)
        self.assertTrue(self.document["exportedAt"].endswith("Z"))


if __name__ == "__main__":
    sys.exit(main())
