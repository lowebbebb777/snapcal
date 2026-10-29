"""Windows SnapCal text parser regression tests. Synthetic texts only."""
from datetime import datetime
import unittest
from core import extract, parse_text

REF = datetime(2026, 10, 29, 17, 0)


class ParserTests(unittest.TestCase):
    def test_two_separate_events_request_choice(self):
        x = extract(['青空クリニック', '11月2日（月）16:50', '会議', '11月3日 18:30'], REF)
        self.assertEqual([(a.title, a.start.day) for a in x],
                         [('青空クリニック', 2), ('会議', 3)])

    def test_ignore_unrelated_expiry_date(self):
        x = extract(['有効期限11月30日', '青空クリニック', '11月2日（月）16:50'], REF)
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0].start.day, 2)

    def test_multiple_dates_with_single_time_not_guessed(self):
        self.assertEqual(extract(['11月2日または11月3日 16:50'], REF), [])

    def test_date_and_time_separate_lines(self):
        x = extract(['打ち合わせ', '11月4日（水）', '14:30'], REF)
        self.assertEqual((x[0].title, x[0].start.hour), ('打ち合わせ', 14))

    def test_opening_hours_not_an_event(self):
        self.assertEqual(extract(['営業時間 11月2日 16:50'], REF), [])

    def test_past_explicit_year_not_an_event(self):
        self.assertEqual(extract(['会議 2024年11月2日 16:50'], REF), [])

    def test_slash_date_inline_title_no_extra_click(self):
        x = parse_text('2026/11/2 16:50 青空クリニック', REF)
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0].title, '青空クリニック')
        self.assertEqual((x[0].start.year, x[0].start.month, x[0].start.day), (2026, 11, 2))

    def test_japanese_hour_minute(self):
        x = parse_text('青空クリニック 11月2日 16時50分', REF)
        self.assertEqual((x[0].start.hour, x[0].start.minute), (16, 50))

    def test_time_range_keeps_title(self):
        x = parse_text('からだクリニック 11月2日 16:50から17:50', REF)
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0].title, 'からだクリニック')
        self.assertEqual((x[0].end.hour, x[0].end.minute), (17, 50))

    def test_two_times_without_range_are_not_auto_chosen(self):
        self.assertEqual(parse_text('11月2日 10:00 16:50 青空クリニック', REF), [])

    def test_date_time_without_title_is_ignored(self):
        self.assertEqual(parse_text('2026/11/2 16:50', REF), [])

    def test_generic_label_is_not_title(self):
        self.assertEqual(parse_text('予約日時 11月2日 16:50', REF), [])

    def test_long_clipboard_content_is_ignored(self):
        self.assertEqual(parse_text('X' * 5001 + '\n11月2日 16:50 青空クリニック', REF), [])

    def test_repeated_rows_are_deduplicated(self):
        x = parse_text('青空クリニック 11月2日 16:50\n青空クリニック 11月2日 16:50', REF)
        self.assertEqual(len(x), 1)

    def test_user_will_not_need_screenshot(self):
        x = parse_text('会議\n11月2日 16:50', REF)
        self.assertEqual(x[0].title, '会議')


if __name__ == '__main__':
    unittest.main()
