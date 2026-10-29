"""Conservative extraction of explicitly labeled calendar details.

Only labels clearly supplied by the user may be sent to Google Calendar.
Never infer an address or note from unrelated emails or attached appointments.
"""
from __future__ import annotations

from dataclasses import replace
from datetime import timedelta
import re

from core import DATE, TIME, Event, _clock, normalize

LOCATION_LABEL = r'場所|会場|開催場所|所在地|住所'
NOTE_LABEL = r'メモ|備考|内容|説明|詳細|持ち物|連絡事項|補足'
END_LABEL = r'終了時刻|終了時間|終了'
FIELD = re.compile(
    r'^(?P<label>(?:' + LOCATION_LABEL + '|' + NOTE_LABEL + '|' + END_LABEL + r'))\s*[:：]\s*(?P<value>.*)$'
)
INLINE_BOUNDARY = re.compile(
    r'[ \t]+(?=(?:' + LOCATION_LABEL + '|' + NOTE_LABEL + '|' + END_LABEL + r')\s*[:：])'
)
LOCATION_NAMES = {'場所', '会場', '開催場所', '所在地', '住所'}
NOTE_NAMES = {'メモ', '備考', '内容', '説明', '詳細', '持ち物', '連絡事項', '補足'}
END_NAMES = {'終了時刻', '終了時間', '終了'}
MAX_LOCATION = 120
MAX_NOTE = 200


def normalize_fields(text: str) -> str:
    """Turn inline location/end/memo labels into separate lines."""
    return INLINE_BOUNDARY.sub('\n', text)


def without_fields(preprocessed_text: str) -> str:
    """Labeled notes containing dates must not generate phantom appointments."""
    return '\n'.join(
        line for line in preprocessed_text.splitlines()
        if not FIELD.match(normalize(line))
    )


def _event_anchors(lines: list[str]) -> list[int]:
    anchors: list[int] = []
    for idx, line in enumerate(lines):
        if FIELD.match(line) or not TIME.search(line):
            continue
        if DATE.search(line):
            anchors.append(idx)
        elif idx > 0 and DATE.search(lines[idx - 1]) and not FIELD.match(lines[idx - 1]):
            anchors.append(idx)
        elif re.search(r'(?:来週[月火水木金土日]曜(?:日)?|今日|明日|明後日)', line):
            anchors.append(idx)
    return anchors


def attach_details(preprocessed_text: str, events: list[Event]) -> list[Event]:
    """Associate only adjacent labels with the corresponding event in order.

    If anchors cannot be aligned 1:1, do not associate ungrounded fields.
    """
    if not events:
        return events
    lines = [normalize(line) for line in preprocessed_text.splitlines() if normalize(line)]
    anchors = _event_anchors(lines)
    if len(anchors) != len(events):
        # Do not auto-send details that could belong to another appointment.
        unclear = has_explicit_details(preprocessed_text)
        return [replace(event, needs_review=unclear) for event in events]

    enriched: list[Event] = []
    for pos, event in enumerate(events):
        anchor = anchors[pos]
        next_anchor = anchors[pos + 1] if pos + 1 < len(anchors) else len(lines)
        location = ''
        notes: list[str] = []
        end = event.end
        seen_end = False
        uncertain = False

        for line in lines[anchor + 1:next_anchor]:
            match = FIELD.match(line)
            if not match:
                # Never carry details over the next title or unrelated prose.
                break
            label, value = match.group('label'), match.group('value').strip()
            if not value:
                uncertain = True
                continue

            if label in LOCATION_NAMES:
                if len(value) > MAX_LOCATION or (location and location != value):
                    location = ''
                    uncertain = True
                elif not uncertain:
                    location = value

            elif label in NOTE_NAMES:
                note = value if label == 'メモ' else f'{label}: {value}'
                if len(note) > MAX_NOTE or sum(map(len, notes)) + len(note) > MAX_NOTE:
                    notes = []
                    uncertain = True
                elif not uncertain:
                    notes.append(note)

            elif label in END_NAMES:
                match_time = TIME.fullmatch(value)
                clock = _clock(match_time) if match_time else None
                if clock is None:
                    uncertain = True
                    continue
                proposed = event.start.replace(hour=clock[0], minute=clock[1])
                if proposed <= event.start or proposed - event.start > timedelta(hours=24):
                    uncertain = True
                elif seen_end and proposed != end:
                    uncertain = True
                elif not uncertain:
                    end = proposed
                    seen_end = True

        enriched.append(replace(
            event, end=end, location=location,
            description='\n'.join(notes), needs_review=uncertain,
        ))
    return enriched


def has_explicit_details(text: str) -> bool:
    return any(
        FIELD.match(normalize(line))
        for line in normalize_fields(text).splitlines()
    )
