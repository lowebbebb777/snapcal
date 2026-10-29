"""Conservative, explicit-label calendar detail extraction.

Only fields clearly marked by the user are copied into Google Calendar. Do
not mine unrelated email bodies, phone numbers, addresses or links for details.
Never attach information from the following appointment to the previous one.
"""
from __future__ import annotations

from dataclasses import replace
import re

from core import DATE, TIME, Event, normalize

# No free-text inference. These labels deliberately require a colon.
LOCATION_LABEL = r'場所|会場|開催場所|所在地|住所'
NOTE_LABEL = r'メモ|備考|内容|持ち物|連絡事項|補足'
FIELD = re.compile(
    r'^(?P<label>' + LOCATION_LABEL + '|' + NOTE_LABEL + r')\s*[:：]\s*(?P<value>.*)$'
)
INLINE_BOUNDARY = re.compile(
    r'[ \t]+(?=(?:' + LOCATION_LABEL + '|' + NOTE_LABEL + r')\s*[:：])'
)
DATE_TIME_LABELS = re.compile(r'^(?:日時|日付|開始日時|終了日時)\s*[:：]\s*')
MAX_LOCATION = 120
MAX_NOTE = 200


def normalize_fields(text: str) -> str:
    """Put inline explicit fields on independent lines before main parsing.

    Example: "会議 11/2 14:00 場所: 3F" becomes two lines and cannot
    accidentally absorb the location text into the event title.
    """
    return INLINE_BOUNDARY.sub('\n', text)


def _event_anchors(lines: list[str]) -> list[int]:
    """Return actual event date/time lines (not dates inside detail fields)."""
    result: list[int] = []
    for idx, line in enumerate(lines):
        if FIELD.match(line):
            continue
        if not TIME.search(line):
            continue
        if DATE.search(line):
            result.append(idx)
        elif idx > 0 and DATE.search(lines[idx - 1]) and not FIELD.match(lines[idx - 1]):
            result.append(idx)
        elif re.search(r'(?:来週[月火水木金土日]曜(?:日)?|今日|明日|明後日)', line):
            result.append(idx)
    return result


def attach_details(preprocessed_text: str, events: list[Event]) -> list[Event]:
    """Attach only immediately following explicit fields to matching events.

    If anchors/events cannot be paired 1:1, refuse enrichment; this is safer
    than silently associating a location with the wrong meeting.
    """
    if not events:
        return events
    lines = [normalize(line) for line in preprocessed_text.splitlines() if normalize(line)]
    anchors = _event_anchors(lines)
    if len(anchors) != len(events):
        return events
    enriched: list[Event] = []
    for pos, event in enumerate(events):
        anchor = anchors[pos]
        next_anchor = anchors[pos + 1] if pos + 1 < len(anchors) else len(lines)
        location = ''
        notes: list[str] = []
        for line in lines[anchor + 1:next_anchor]:
            match = FIELD.match(line)
            if not match:
                # Do not cross into the following event's title or narrative.
                break
            label, value = match.group('label'), match.group('value').strip()
            if not value:
                continue
            if label in ('場所', '会場', '開催場所', '所在地', '住所'):
                if len(value) > MAX_LOCATION or (location and location != value):
                    location = ''
                    break
                location = value
            else:
                # Keep the meaning of e.g. "持ち物" rather than collapsing it
                # into a generic note without a label.
                note = value if label == 'メモ' else f'{label}: {value}'
                if len(note) > MAX_NOTE or sum(map(len, notes)) + len(note) > MAX_NOTE:
                    notes = []
                    break
                notes.append(note)
        enriched.append(replace(event, location=location, description='\n'.join(notes)))
    return enriched


def has_explicit_details(text: str) -> bool:
    return bool(FIELD.search(normalize_fields(text))) if '\n' not in text else any(
        FIELD.match(normalize(line)) for line in normalize_fields(text).splitlines()
    )
