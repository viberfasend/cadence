#!/usr/bin/env python3
"""Turn a Todoist CSV export into a Cadence backup file.

Todoist exports one CSV per project ("Garten [6g62GH268rQjFQ92].csv"). This script reads any
number of those files — or a whole export directory — and writes a single
`{"format":"cadence.backup","version":2,...}` document, the published contract
`core/.../domain/backup/BackupCodec.kt` reads. Import it in the app under Settings -> Backup;
importing merges, so nothing already on the device is lost.

How a Todoist row lands in Cadence:

    file "Name [id].csv"  -> a project (the "Inbox" file goes to the Cadence Inbox instead)
    section row           -> a subproject of that project (Cadence nests one level, like Todoist)
    task row, INDENT 1    -> a task in the current project or section
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
    python3 tools/todoist_import.py export/*.csv -o cadence-backup.json
    python3 tools/todoist_import.py export/ --dry-run      # parse and report, write nothing
    python3 tools/todoist_import.py --self-test            # the parser's unit tests
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
import uuid
from dataclasses import dataclass, field
from typing import Iterable, Sequence

# The version BackupCodec.VERSION carries. A reader refuses a *higher* version, so this must
# track the app rather than run ahead of it.
BACKUP_FORMAT = "cadence.backup"
BACKUP_VERSION = 2

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
    def __init__(self, options: argparse.Namespace, now: dt.datetime, ref: dt.date):
        self.options = options
        self.now_iso = now.replace(microsecond=(now.microsecond // 1000) * 1000).isoformat().replace("+00:00", "Z")
        self.ref = ref
        self.projects: list[dict] = []
        self.tasks: list[dict] = []
        self.stats = Stats()

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

    def convert_file(self, path: str, index: int) -> None:
        with open(path, newline="", encoding="utf-8-sig") as handle:
            rows = list(csv.DictReader(handle))

        name = project_name(path)
        is_inbox = name.strip().lower() == self.options.inbox_name.strip().lower()
        color = PROJECT_COLORS[index % len(PROJECT_COLORS)]
        root_id: str | None = None
        if not is_inbox:
            root_id = stable_id("project", name)

        pending_sections: list[tuple[str, str]] = []   # (id, name), created on first task
        section_id: str | None = None
        section_name: str | None = None
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
                pending_sections.append((section_id, content))
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
            # as an empty subproject, and Todoist exports plenty of those.
            if root_id and not created_root:
                self.add_project(root_id, name, None, len(self.projects), color)
                created_root = True
                self.stats.projects += 1
            if pending_sections and section_id:
                for pending_id, pending_name in pending_sections:
                    if pending_id == section_id:
                        parent = root_id
                        self.add_project(pending_id, pending_name, parent, len(self.projects), color)
                        self.stats.sections += 1
                pending_sections = [s for s in pending_sections if s[0] != section_id]

            task = self.convert_task(row, row_index, name, section_id or root_id, order)
            order += 1
            indent = int((row.get("INDENT") or "1").strip() or 1)
            if indent >= 2 and current_parent is not None:
                task["parentId"] = current_parent["id"]
                # A subtask inherits its parent's project: Cadence moves the two together.
                task["projectId"] = current_parent["projectId"]
                self.stats.subtasks += 1
            else:
                current_parent = task
                self.stats.tasks += 1
            self.tasks.append(task)
            last_task = task

        _ = section_name  # kept for readability of the section handling above

    def convert_task(self, row: dict, row_index: int, file_name: str,
                     project_id: str | None, order: int) -> dict:
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


def convert(paths: Sequence[str], options: argparse.Namespace,
            now: dt.datetime, ref: dt.date) -> tuple[dict, Stats]:
    converter = Converter(options, now, ref)
    # The Inbox first, so its tasks keep the lowest sort orders in the Cadence Inbox.
    ordered = sorted(paths, key=lambda p: (project_name(p).lower() != options.inbox_name.lower(),
                                           project_name(p).lower()))
    for index, path in enumerate(ordered):
        converter.convert_file(path, index)
    return converter.document(), converter.stats


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a Todoist CSV export into a Cadence backup file.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("inputs", nargs="*", help="CSV files, or a directory of them")
    parser.add_argument("-o", "--out", default="cadence-backup.json", help="output file")
    parser.add_argument("--dry-run", action="store_true", help="parse and report, write nothing")
    parser.add_argument("--inbox-name", default="Inbox",
                        help="the export file whose tasks go to the Cadence Inbox (default: Inbox)")
    parser.add_argument("--strip-labels", action="store_true",
                        help="move Todoist @labels out of the title into the notes")
    parser.add_argument("--invert-priority", action="store_true",
                        help="read PRIORITY 4 as P1 (use when your export numbers them the API way)")
    parser.add_argument("--bare-year", choices=("current", "next-occurrence"), default="current",
                        help="how to year a date exported without one (default: current year)")
    parser.add_argument("--recurring-due", choices=("next", "none"), default="next",
                        help="whether a recurring task gets its next date as a due date")
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
    document, stats = convert(paths, options, now, ref)

    print(f"{len(paths)} file(s) -> {stats.projects} project(s), {stats.sections} section(s), "
          f"{stats.tasks} task(s), {stats.subtasks} subtask(s), {stats.notes} note(s)")
    print(f"  {stats.recurring} recurring, {stats.dated} dated")
    for warning in stats.unparsed:
        print(f"  ! unreadable date, kept in the notes — {warning}", file=sys.stderr)

    if options.dry_run:
        print("dry run: nothing written")
        return 0

    with open(options.out, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"wrote {options.out} — import it under Settings -> Backup")
    return 0


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


def _options(**overrides) -> argparse.Namespace:
    base = dict(inbox_name="Inbox", strip_labels=False, invert_priority=False,
                bare_year="current", recurring_due="next")
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

    def test_project_name_drops_the_todoist_id(self):
        self.assertEqual(project_name(self.path), "wohnung")
        self.assertEqual([p["name"] for p in self.document["projects"]], ["wohnung", "Ofen"])

    def test_empty_sections_are_not_imported(self):
        self.assertNotIn("Leer", [p["name"] for p in self.document["projects"]])

    def test_section_is_a_subproject_of_its_file(self):
        root = next(p for p in self.document["projects"] if p["name"] == "wohnung")
        ofen = next(p for p in self.document["projects"] if p["name"] == "Ofen")
        self.assertEqual(ofen["parentId"], root["id"])
        self.assertEqual(self.task("reinigen")["projectId"], ofen["id"])

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

    def test_inbox_file_has_no_project(self):
        inbox = os.path.join(self.dir, "Inbox [6Crg8jQ8644Gg3r6].csv")
        with open(inbox, "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        document, _ = convert([inbox], _options(),
                              dt.datetime(2026, 8, 13, tzinfo=dt.timezone.utc), REF)
        task = next(t for t in document["tasks"] if t["title"].startswith("Fenster"))
        self.assertIsNone(task["projectId"])

    def test_ids_are_uuids_and_stable_across_runs(self):
        again, _ = convert([self.path], _options(),
                           dt.datetime(2027, 1, 1, tzinfo=dt.timezone.utc), REF)
        self.assertEqual([t["id"] for t in again["tasks"]],
                         [t["id"] for t in self.document["tasks"]])
        for task in self.document["tasks"]:
            uuid.UUID(task["id"])  # raises when it is not a UUID — sync's columns are `uuid`

    def test_document_is_the_published_backup_shape(self):
        self.assertEqual(self.document["format"], "cadence.backup")
        self.assertEqual(self.document["version"], BACKUP_VERSION)
        self.assertTrue(self.document["exportedAt"].endswith("Z"))


if __name__ == "__main__":
    sys.exit(main())
