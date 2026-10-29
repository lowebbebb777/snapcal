from datetime import datetime
import unittest
from core import extract

REF = datetime(2026, 10, 29, 17, 0)


class ParserTests(unittest.TestCase):
    def test_two_separate_events(self):
        x = extract(['青空クリニック', '11月2日（月）16:50', '会議', '11月3日 18:30'], REF)
        self.assertEqual([(a.title, a.start.day) for a in x],
                         [('青空クリニック', 2), ('会議', 3)])

    def test_ignore_other_dates(self):
        x = extract(['有効期限11月30日', '青空クリニック', '11月2日（月）16:50'], REF)
        self.assertEqual(len(x), 1)
        self.assertEqual(x[0].start.day, 2)

    def test_no_false_choice_between_two_dates(self):
        self.assertEqual(extract(['11月2日または11月3日 16:50'], REF), [])

    def test_date_and_time_separate_lines(self):
        x = extract(['打ち合わせ', '11月4日（水）', '14:30'], REF)
        self.assertEqual((x[0].title, x[0].start.hour), ('打ち合わせ', 14))

    def test_no_opening_hours(self):
        self.assertEqual(extract(['営業時間 11月2日 16:50'], REF), [])

    def test_no_past_explicit(self):
        self.assertEqual(extract(['会議 2024年11月2日 16:50'], REF), [])


if __name__ == '__main__':
    unittest.main()
