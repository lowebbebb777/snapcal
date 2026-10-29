"""Windows event detail tests. All examples are synthetic, no Google access."""
from datetime import datetime
import unittest

from decision import decide
from core import Event
from details import normalize_fields, without_fields, attach_details

NOW = datetime(2026, 10, 29, 12, 0)


class DetailsTests(unittest.TestCase):
    def test_location_inline_stripped_from_title(self):
        d = decide('定例会議 2026/11/12 14:10 場所: 福岡支店3階', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(len(d.events), 1)
        self.assertEqual(d.events[0].title, '定例会議')
        self.assertEqual(d.events[0].location, '福岡支店3階')
        self.assertEqual(d.events[0].description, '')

    def test_location_on_second_line(self):
        d = decide('面談 2026/11/12 14:10\n会場：福岡支店', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].title, '面談')
        self.assertEqual(d.events[0].location, '福岡支店')

    def test_note_requires_explicit_confirmation(self):
        d = decide('定例会議 2026/11/12 14:10\nメモ: 開始までに資料を確認', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.reason, 'details_confirmation')
        self.assertEqual(d.events[0].description, '開始までに資料を確認')

    def test_location_and_note_are_separate(self):
        d = decide(
            '定例会議 2026/11/12 14:10〜15:30\n'
            '場所: 会議室A\n'
            'メモ: 事前資料を持参',
            NOW,
        )
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.events[0].title, '定例会議')
        self.assertEqual(d.events[0].start.hour, 14)
        self.assertEqual(d.events[0].end.hour, 15)
        self.assertEqual(d.events[0].end.minute, 30)
        self.assertEqual(d.events[0].location, '会議室A')
        self.assertEqual(d.events[0].description, '事前資料を持参')

    def test_two_events_have_independent_details(self):
        d = decide(
            '定例会議 2026/11/12 14:10\n'
            '場所: 福岡支店A\n'
            'メモ: 資料A\n'
            '面談 2026/11/13 15:20\n'
            '場所: 佐賀支店B\n'
            '備考: 資料B',
            NOW,
        )
        self.assertEqual(d.mode, 'review')
        self.assertEqual(len(d.events), 2)
        self.assertEqual(d.events[0].title, '定例会議')
        self.assertEqual(d.events[0].location, '福岡支店A')
        self.assertEqual(d.events[0].description, '資料A')
        self.assertEqual(d.events[1].title, '面談')
        self.assertEqual(d.events[1].location, '佐賀支店B')
        self.assertEqual(d.events[1].description, '備考: 資料B')

    def test_detail_not_attached_to_previous_when_next_title_begins(self):
        d = decide(
            '定例会議 2026/11/12 14:10\n'
            '面談\n'
            '2026/11/13 15:20\n'
            '場所: 佐賀支店',
            NOW,
        )
        self.assertEqual(len(d.events), 2)
        self.assertEqual(d.events[0].location, '')
        self.assertEqual(d.events[1].location, '佐賀支店')

    def test_relative_natural_language_with_details(self):
        d = decide('来週火曜14時から打ち合わせ\n場所：福岡支店', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].start, datetime(2026, 11, 3, 14))
        self.assertEqual(d.events[0].title, '打ち合わせ')
        self.assertEqual(d.events[0].location, '福岡支店')

    def test_relative_natural_language_note_requires_preview(self):
        d = decide('明日午後3時から面談\nメモ：履歴書持参', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.events[0].title, '面談')
        self.assertEqual(d.events[0].description, '履歴書持参')

    def test_note_containing_another_date_does_not_create_fake_second_event(self):
        d = decide('定例会議 2026/11/12 14:10\nメモ: 次回は2026/11/13 15:20', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(len(d.events), 1)
        self.assertEqual(d.events[0].description, '次回は2026/11/13 15:20')

    def test_unknown_field_is_not_fabricated(self):
        d = decide('定例会議 2026/11/12 14:10\n会議室Aがいいらしい', NOW)
        self.assertEqual(len(d.events), 1)
        self.assertEqual(d.events[0].location, '')
        self.assertEqual(d.events[0].description, '')

    def test_conflicting_locations_do_not_auto_register(self):
        d = decide(
            '定例会議 2026/11/12 14:10\n場所: A室\n場所: B室',
            NOW,
        )
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.events[0].location, '')

    def test_empty_location_field_triggers_review(self):
        d = decide('定例会議 2026/11/12 14:10\n場所: ', NOW)
        self.assertEqual(d.mode, 'review')

    def test_oversized_location_is_not_persisted(self):
        d = decide('定例会議 2026/11/12 14:10\n場所: ' + ('A' * 130), NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.events[0].location, '')

    def test_unanchored_detail_not_attached_to_event(self):
        d = decide('場所: 福岡支店\n定例会議 2026/11/12 14:10', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertEqual(d.events[0].location, '')

    def test_location_does_not_become_title(self):
        d = decide('2026/11/12 14:10\n場所: 福岡支店', NOW)
        self.assertEqual(d.mode, 'ignore')

    def test_explicit_end_time_is_reflected_in_event(self):
        d = decide('定例会議 2026/11/12 14:10\n終了時刻: 16:45', NOW)
        self.assertEqual(d.mode, 'auto')
        self.assertEqual(d.events[0].end, datetime(2026, 11, 12, 16, 45))

    def test_invalid_explicit_end_time_forces_confirmation(self):
        d = decide('定例会議 2026/11/12 14:10\n終了時刻: 13:00', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertTrue(d.events[0].needs_review)

    def test_non_time_end_value_forces_confirmation(self):
        d = decide('定例会議 2026/11/12 14:10\n終了時刻: 未定', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertTrue(d.events[0].needs_review)

    def test_two_different_end_times_are_not_guessed(self):
        d = decide('定例会議 2026/11/12 14:10\n終了: 15:45\n終了: 16:45', NOW)
        self.assertEqual(d.mode, 'review')
        self.assertTrue(d.events[0].needs_review)

    def test_old_three_field_event_remains_valid(self):
        a = Event('会議', datetime(2026, 11, 12, 14), datetime(2026, 11, 12, 15))
        self.assertEqual(a.location, '')
        self.assertEqual(a.description, '')

    def test_dates_inside_explicit_notes_stripped_before_title_parsing(self):
        p = normalize_fields('会議 2026/11/12 14:10\n備考: 2026/11/13 15:20')
        self.assertNotIn('備考', without_fields(p))


if __name__ == '__main__':
    unittest.main()
