"""Fail-closed clipboard decision layer, shared by parsing and Windows UI.

Unrelated text may have dates/times. Those are not calendar instructions.
Only compact, clearly intended appointments with a unique interpretation may
be auto-added. Everything else is either review-only or ignored.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
import re

from core import DATE, TIME, Event, normalize, parse_text
from details import attach_details, has_explicit_details, normalize_fields, without_fields

AUTO_CUES = (
    '会議', '打ち合わせ', '打合せ', 'ミーティング', '面談', '面接',
    '予約', '診察', '受診', '検診', '通院', '病院', '医院', '歯科', 'クリニック',
    '商談', '説明会', '研修', 'セミナー', '試験', 'ライブ', 'イベント',
    '美容院', '訪問', '食事会', '懇親会', '飲み会',
)
# If a cancellation, hypothetical scenario, or instruction is copied, do not
# register it as an actual event, even if it also includes "会議".
DO_NOT_REGISTER = (
    '中止', 'キャンセル', '取り消し', '取消', '延期', '変更前',
    '予定なし', '予約なし', '未確定', '仮の予定', '仮予定',
    '例えば', '例として', 'サンプル文章', 'ダミー', 'かもしれない',
    'かもしれません', 'いつですか', '何時ですか', 'どうすれば',
    '登録しない', '削除してください',
)
PROSE_HINTS = ('記事', 'ニュース', '資料', 'マニュアル', '記録によると', 'と書いた', 'という説明')
WEEKDAYS = '月火水木金土日'
RELATIVE = re.compile(r'(来週[月火水木金土日]曜(?:日)?|今日|明日|明後日)')
START_TIME = re.compile(
    r'(?<!\d)(?:(午前|午後)\s*)?([01]?\d|2[0-3])'
    r'(?::([0-5]\d)|時\s*([0-5]?\d)?\s*分?)(?!\d)'
)
LINE_LIMIT = 80
CHAR_LIMIT = 5000


@dataclass(frozen=True)
class Decision:
    mode: str                         # auto | review | ignore
    events: tuple[Event, ...] = ()
    reason: str = ''                   # no private clipboard content


def _relative_candidates(text: str, now: datetime) -> list[Event]:
    if DATE.search(text):
        return []  # Never combine relative and explicit date evidence.
    day_matches = list(RELATIVE.finditer(text))
    times = list(START_TIME.finditer(text))
    if len(day_matches) != 1 or len(times) != 1:
        return []
    day_match, time_match = day_matches[0], times[0]
    if abs(time_match.start() - day_match.end()) > 12:
        return []
    marker, raw_hour, mm, jp_mm = time_match.groups()
    hour, minute = int(raw_hour), int(mm or jp_mm or '0')
    if marker:
        if not 1 <= hour <= 12:
            return []
        hour = hour % 12 if marker == '午前' else (hour % 12) + 12
    word = day_match.group()
    if word == '今日':
        target = now.date()
    elif word == '明日':
        target = (now + timedelta(days=1)).date()
    elif word == '明後日':
        target = (now + timedelta(days=2)).date()
    else:
        # Monday of NEXT calendar week, not the next occurrence of that weekday.
        next_monday = now.date() + timedelta(days=7 - now.weekday())
        target = next_monday + timedelta(days=WEEKDAYS.index(word[2]))
    start = datetime(target.year, target.month, target.day, hour, minute)
    if start <= now or start > now + timedelta(days=400):
        return []

    # Require a recognizable appointment phrase on the time's trailing side.
    # Disallow generic narrative text such as "明日の14時に何かあった".
    trailing = normalize(text[time_match.end():])
    trailing = re.sub(r'^(?:に|の|から|より|〜|～|、|\s)+', '', trailing)
    trailing = re.sub(r'(?:です|予定です|になります)[。.!！]*$', '', trailing).strip()
    if not trailing or not any(cue in trailing for cue in AUTO_CUES):
        return []
    if len(trailing) > 90:
        return []
    return [Event(trailing, start, start + timedelta(hours=1))]


def decide(text: str, now: datetime | None = None) -> Decision:
    now = now or datetime.now()
    if not text or len(text) > CHAR_LIMIT or text.count('\n') > LINE_LIMIT:
        return Decision('ignore', reason='no_short_text')
    s = normalize(text)
    if any(word in s for word in DO_NOT_REGISTER):
        return Decision('ignore', reason='cancelled_or_hypothetical')
    if s.count('http') > 1:
        return Decision('ignore', reason='link_collection')

    prepared = normalize_fields(s)
    candidate_text = without_fields(prepared)
    parsed = parse_text(candidate_text, now=now)
    if not parsed:
        # Relative weekdays are per-event lines; labeled details are not part
        # of the title. Do not concatenate a whole clipboard into one title.
        parsed = [
            item for line in candidate_text.splitlines()
            for item in _relative_candidates(line, now)
        ]
    parsed = attach_details(prepared, parsed)
    # Entire copied paragraphs are not treated as implied events.
    events = tuple(dict.fromkeys(parsed))
    if not events:
        return Decision('ignore', reason='no_complete_candidate')
    if len(events) > 1:
        return Decision('review', events, 'multiple_events')

    event = events[0]
    # Notes can include sensitive information. Always preview them before
    # uploading to Google Calendar; never silently ignore malformed fields.
    detail_was_present = has_explicit_details(prepared)
    if (event.description or event.needs_review or (
        detail_was_present and not event.location
        and event.end == event.start + timedelta(hours=1)
    )):
        return Decision('review', events, 'details_confirmation')
    has_auto_cue = any(cue in event.title for cue in AUTO_CUES)
    short_text = len(s) <= 220 and s.count('\n') <= 3
    prose = any(hint in s for hint in PROSE_HINTS)
    if has_auto_cue and short_text and not prose:
        return Decision('auto', events, 'high_confidence')
    # One plausible event with uncertain intent gets a one-click confirmation.
    return Decision('review', events, 'uncertain_intent')
