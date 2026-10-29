"""SnapCal: local, deterministic Japanese text -> calendar candidate extraction.

Clipboard contents are processed in memory only; neither the raw text nor
extracted event details are written to local logs or settings by this module.
No event is created without a trustworthy date, start time and title.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
import re
import unicodedata

# The Android app has its own Kotlin parser. Both platforms implement the
# same user policy: clear single candidate -> add; multiple -> ask; none -> ignore.
DATE = re.compile(
    r'(?<![\d/])(?:(?P<year>(?:19|20)\d{2})\s*[年/.\-])?\s*'
    r'(?P<month>1[0-2]|0?[1-9])\s*[月/.\-]\s*'
    r'(?P<day>3[01]|[12]\d|0?[1-9])\s*日?(?!\d)'
)
TIME = re.compile(
    r'(?<!\d)(?:(?P<marker>午前|午後)\s*)?'
    r'(?P<hour>[01]?\d|2[0-3])'
    r'(?::(?P<minute>[0-5]\d)|時\s*(?P<jminute>[0-5]?\d)?\s*分?)(?!\d)'
)
WEEKDAY = re.compile(r'[\(（][月火水木金土日定][\)）]')
RANGE = re.compile(r'^\s*(?:[-~〜～–—−]|から|→|to)\s*$', re.IGNORECASE)
IGNORE = ('営業時間', '受付時間', '有効期限', '販売期間', '締切', '受付終了', 'キャンセル期限')
GENERIC = {
    '日時', '時間', '開催日時', '開催日', '詳細', 'お知らせ', '予約する',
    '申し込む', '戻る', '閉じる', '診察券', '予約日時', '予約確認',
    '受付', '日時未定', '通知', '予約内容', '予定', 'カレンダー', '予約', '診療',
    'から', 'まで', '開始', '終了', '予約時間', '開始時刻', '終了時刻',
}
LABEL = re.compile(r'^(?:タイトル|予定名|イベント名|公演名|予約名|件名)\s*[:：]')
RELATIVE = re.compile(r'今日|明日|明後日')


@dataclass(frozen=True)
class Event:
    title: str
    start: datetime
    end: datetime

    def __post_init__(self) -> None:
        if not self.title or self.end <= self.start:
            raise ValueError('invalid event')


def normalize(text: str) -> str:
    return unicodedata.normalize('NFKC', text).strip()


def _clean_title(text: str) -> str:
    text = WEEKDAY.sub(' ', DATE.sub(' ', TIME.sub(' ', text)))
    text = LABEL.sub('', text)
    text = re.sub(r'\b(?:午前|午後|開始時間|開始時刻|開始日時|日時)\s*[:：]?', ' ', text)
    text = re.sub(r'\s+', ' ', text)
    return text.strip(' ,:：|｜-/〜~・。.')


def _good_title(text: str) -> bool:
    if not 2 <= len(text) <= 100 or text in GENERIC:
        return False
    if text.startswith(('https:', 'http:')) or any(k in text for k in IGNORE):
        return False
    if re.fullmatch(r'[0-9:：/\-.〜]+', text):
        return False
    return any(ch.isalpha() for ch in text)


def _clock(match: re.Match[str]) -> tuple[int, int] | None:
    hour = int(match['hour'])
    minute = int(match['minute'] or match['jminute'] or '0')
    marker = match['marker']
    if marker:
        if not 1 <= hour <= 12:
            return None
        hour = hour % 12 if marker == '午前' else (hour % 12) + 12
    return hour, minute


def _date(match: re.Match[str], now: datetime, start_clock: tuple[int, int]) -> datetime | None:
    year = int(match['year']) if match['year'] else now.year
    month, day = int(match['month']), int(match['day'])
    try:
        start = datetime(year, month, day, *start_clock)
        if match['year'] is None and start <= now:
            # February 29 must not silently roll to another day.
            start = datetime(year + 1, month, day, *start_clock)
    except ValueError:
        return None
    return start


def extract(lines: list[str], now: datetime | None = None) -> list[Event]:
    """Return only grounded, unique candidates; never Cartesian-product dates/times."""
    now = now or datetime.now()
    normalized = [normalize(t) for t in lines if normalize(t)]
    if len(normalized) > 80 or sum(map(len, normalized)) > 5000:
        return []
    events: list[Event] = []
    for idx, text in enumerate(normalized):
        if any(word in text for word in IGNORE):
            continue
        dates = list(DATE.finditer(text))
        times = list(TIME.finditer(text))
        if not times:
            continue
        date_line = text
        if not dates and idx > 0:
            previous = normalized[idx - 1]
            if not any(word in previous for word in IGNORE) and not TIME.search(previous):
                dates = list(DATE.finditer(previous))
                if len(dates) == 1:
                    date_line = previous
        if len(dates) != 1:
            continue
        # 2 clocks are accepted only if they express a start-end range.
        if len(times) not in (1, 2):
            continue
        if len(times) == 2:
            between = text[times[0].end():times[1].start()]
            if not RANGE.fullmatch(between):
                continue
        first = _clock(times[0])
        if first is None:
            continue
        start = _date(dates[0], now, first)
        if start is None or start <= now or start > now + timedelta(days=400):
            continue
        if len(times) == 2:
            last = _clock(times[1])
            if last is None:
                continue
            end = start.replace(hour=last[0], minute=last[1])
            if end <= start:
                end += timedelta(days=1)
        else:
            end = start + timedelta(hours=1)
        if end - start > timedelta(hours=24):
            continue
        # Remove only the known range delimiter BETWEEN the two time tokens.
        # Never modify the word "から" inside real titles such as "からだクリニック".
        title_text = text
        if len(times) == 2:
            title_text = text[:times[0].end()] + ' ' + text[times[1].start():]
        title = _clean_title(title_text) if date_line == text else ''
        if not _good_title(title):
            for prev in reversed(normalized[max(0, idx - 3):idx]):
                if prev == date_line or DATE.search(prev) or TIME.search(prev):
                    continue
                candidate = _clean_title(prev)
                if _good_title(candidate):
                    title = candidate
                    break
        if not _good_title(title) and idx + 1 < len(normalized):
            following = normalized[idx + 1]
            if not DATE.search(following) and not TIME.search(following):
                candidate = _clean_title(following)
                if _good_title(candidate):
                    title = candidate
        if not _good_title(title):
            continue
        events.append(Event(title, start, end))
    # Limit accidentally long copied threads and repeated duplicate rows.
    return list(dict.fromkeys(events))[:8]


def parse_text(text: str, now: datetime | None = None) -> list[Event]:
    """Public clipboard entrypoint. A single paragraph is one row."""
    return extract(text.splitlines(), now=now)
