"""Regression gates for automatic clipboard registration; no live Google writes."""
from datetime import datetime
import unittest

from decision import decide

NOW = datetime(2026, 10, 29, 12, 0)


class DecisionTests(unittest.TestCase):
    def test_existing_known_good_screenshotless_appointment_is_auto(self):
        d = decide('テスト会議 2026/11/5 16:50', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].title, 'テスト会議')

    def test_clinic_booking_is_auto(self):
        self.assertEqual(decide('青空クリニック\n11月2日（月）16:50', NOW).mode, 'auto')

    def test_plain_numbered_article_goes_to_confirmation(self):
        d = decide('2026/11/5 16:50 新着ニュース', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(len(d.events), 1)

    def test_pure_date_time_is_silent_ignore(self):
        self.assertEqual(decide('2026/11/5 16:50', NOW).mode, 'ignore')

    def test_generic_non_event_clipboard_is_silent_ignore(self):
        self.assertEqual(decide('先週の売上は前年比120%でした', NOW).mode, 'ignore')

    def test_cancelled_event_does_not_register(self):
        self.assertEqual(decide('会議を中止 2026/11/5 16:50', NOW).mode, 'ignore')

    def test_hypothetical_example_does_not_register(self):
        self.assertEqual(decide('例えば会議 2026/11/5 16:50', NOW).mode, 'ignore')

    def test_news_and_meeting_article_not_auto(self):
        d = decide('会議の資料には2026/11/5 16:50と書いた', NOW)
        self.assertNotEqual(d.mode, 'auto')

    def test_two_distinct_valid_events_require_review(self):
        d = decide('青空クリニック\n11月2日16:50\n定例会議\n11月3日17:30', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(len(d.events), 2)
        self.assertEqual([x.start.day for x in d.events], [2, 3])

    def test_user_report_two_test_events_require_visible_two_choice_dialog(self):
        copied = 'テスト会議A 2026/11/12 14:10\nテスト面談B 2026/11/13 15:20'
        d = decide(copied, NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual([event.title for event in d.events], ['テスト会議A', 'テスト面談B'])
        self.assertEqual([event.start.day for event in d.events], [12, 13])

    def test_one_uncertain_event_requires_one_click(self):
        d = decide('新着ニュース 2026/11/5 16:50', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(len(d.events), 1)

    def test_long_mail_never_auto(self):
        d = decide('議事録\n' * 4 + '会議 2026/11/5 16:50', NOW)
        self.assertNotEqual(d.mode, 'auto')

    def test_more_than_limit_never_auto(self):
        self.assertEqual(decide('X' * 5001 + ' 会議 2026/11/5 16:50', NOW).mode, 'ignore')

    def test_next_week_tuesday_meeting(self):
        d = decide('来週火曜14時から打ち合わせ', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].start, datetime(2026, 11, 3, 14, 0))

    def test_next_week_wednesday_meeting(self):
        d = decide('来週水曜日の14:30から会議', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].start, datetime(2026, 11, 4, 14, 30))

    def test_tomorrow_afternoon(self):
        d = decide('明日午後3時から面談', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].start, datetime(2026, 10, 30, 15, 0))

    def test_today_past_is_not_auto(self):
        self.assertEqual(decide('今日10時から会議', NOW).mode, 'ignore')

    def test_unrecognized_relative_time_is_not_guessed(self):
        self.assertEqual(decide('今週火曜14時から会議', NOW).mode, 'ignore')

    def test_two_times_not_guessed(self):
        self.assertEqual(decide('来週火曜14時か15時の会議', NOW).mode, 'ignore')

    def test_two_relatives_not_guessed(self):
        self.assertEqual(decide('明日か明後日の14時から会議', NOW).mode, 'ignore')

    def test_relative_narrative_not_guessed(self):
        self.assertEqual(decide('明日14時に荷物が届くらしい', NOW).mode, 'ignore')

    def test_calendar_event_with_time_range(self):
        d = decide('会議 11月2日 16:00〜17:00', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].end, datetime(2026, 11, 2, 17, 0))

    def test_unknown_title_wont_auto(self):
        self.assertEqual(decide('2026/11/5 16:50 何か', NOW).mode, 'review')

    def test_duplicated_event_rows_are_unique(self):
        d = decide('青空クリニック 11月2日 16:50\n青空クリニック 11月2日 16:50', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(len(d.events), 1)


if __name__ == '__main__':
    unittest.main()
