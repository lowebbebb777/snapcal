"""Deterministic local Japanese OCR-to-event parser for Windows SnapCal.

Only candidates with an explicit date and start time are offered. Unknown
fields are never silently invented. Images and raw OCR aren't persisted.
"""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, timedelta
import re
import unicodedata

DATE = re.compile(r'(?:(?P<year>20\d{2})[年/.-])?\s*(?P<month>1[0-2]|0?[1-9])[月/]\s*(?P<day>3[01]|[12]\d|0?[1-9])日?')
TIME = re.compile(r'(?<!\d)(?P<hour>[01]?\d|2[0-3]):(?P<minute>[0-5]\d)(?!\d)')
WEEKDAY = re.compile(r'[（(][月火水木金土日定][）)]')
IGNORE = ('営業時間', '受付時間', '有効期限', '販売期間', '締切', '受付終了')
GENERIC = {'日時', '時間', '開催日時', '開催日', '詳細', 'お知らせ', '予約する', '申し込む', '戻る', '閉じる'}

@dataclass(frozen=True)
class Event:
    title: str
    start: datetime
    end: datetime

    def __post_init__(self):
        if not self.title or self.end <= self.start:
            raise ValueError('invalid event')


def normalize(text: str) -> str:
    return unicodedata.normalize('NFKC', text).strip()


def _clean_title(text: str) -> str:
    text = DATE.sub(' ', text)
    text = TIME.sub(' ', text)
    text = WEEKDAY.sub(' ', text)
    text = re.sub(r'^(?:タイトル|予定|イベント名|公演名)\s*[:：]', '', text)
    return re.sub(r'\s+', ' ', text).strip(' ,:：|｜-〜~')


def _good_title(text: str) -> bool:
    if not 2 <= len(text) <= 120 or text in GENERIC:
        return False
    if text.startswith(('https:', 'http:')) or any(k in text for k in IGNORE):
        return False
    return any(ch.isalpha() for ch in text)


def extract(lines: list[str], now: datetime | None = None) -> list[Event]:
    """Parse explicit independent events; no speculative date/time cross-products."""
    now = now or datetime.now()
    normalized = [normalize(t) for t in lines if normalize(t)]
    items: list[Event] = []
    for idx, text in enumerate(normalized):
        if any(word in text for word in IGNORE):
            continue
        dates = list(DATE.finditer(text))
        times = list(TIME.finditer(text))
        # OCR often splits the date and time over consecutive lines.
        date_row = text
        if not dates and len(times) == 1 and idx > 0:
            previous = normalized[idx - 1]
            if not any(word in previous for word in IGNORE) and not TIME.search(previous):
                prev_dates = list(DATE.finditer(previous))
                if len(prev_dates) == 1:
                    date_row, dates = previous, prev_dates
        if len(dates) != 1 or len(times) != 1:
            continue
        match = dates[0]
        hour, minute = int(times[0]['hour']), int(times[0]['minute'])
        year = int(match['year']) if match['year'] else now.year
        try:
            start = datetime(year, int(match['month']), int(match['day']), hour, minute)
            if match['year'] is None and start <= now:
                start = start.replace(year=start.year + 1)
        except ValueError:
            continue
        if start <= now or start > now + timedelta(days=400):
            continue
        title = _clean_title(text)
        if date_row is not text:
            title = ''  # Date-only and time-only rows don't supply a title.
        if not _good_title(title):
            for prev in reversed(normalized[max(0, idx - 3):idx]):
                candidate = _clean_title(prev)
                if _good_title(candidate) and not DATE.search(prev) and not TIME.search(prev):
                    title = candidate
                    break
        if not _good_title(title):
            continue
        items.append(Event(title=title, start=start, end=start + timedelta(hours=1)))
    return list(dict.fromkeys(items))[:8]
