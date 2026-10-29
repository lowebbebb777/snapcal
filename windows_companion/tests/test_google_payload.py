"""Validate Google Calendar payloads entirely with mocks (no API calls)."""
from datetime import datetime
from unittest import TestCase
from unittest.mock import Mock, patch

from core import Event
from windows_app import add_to_google_calendar


class CalendarPayloadTests(TestCase):
    def setUp(self):
        self.start = datetime(2026, 11, 12, 14, 10)
        self.end = datetime(2026, 11, 12, 16, 45)
        self.service = Mock()
        self.service.events.return_value.insert.return_value.execute.return_value = {}
        self.patches = [
            patch('windows_app.credentials', return_value=Mock()),
            patch('windows_app.build', return_value=self.service),
            patch('windows_app._already_registered', return_value=False),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def _body(self):
        return self.service.events.return_value.insert.call_args.kwargs['body']

    def test_location_and_notes_are_sent_only_for_explicit_fields(self):
        event = Event('定例会議', self.start, self.end,
                      location='福岡支店3階', description='資料持参')
        self.assertTrue(add_to_google_calendar(event))
        body = self._body()
        self.assertEqual(body['summary'], '定例会議')
        self.assertEqual(body['location'], '福岡支店3階')
        self.assertEqual(body['description'], '資料持参')
        self.assertIn('16:45', body['end']['dateTime'])

    def test_absent_details_are_not_synthesized(self):
        event = Event('定例会議', self.start, self.end)
        self.assertTrue(add_to_google_calendar(event))
        self.assertNotIn('description', self._body())
        self.assertNotIn('location', self._body())

    def test_duplicate_does_not_overwrite_existing_event(self):
        event = Event('定例会議', self.start, self.end, location='福岡支店')
        with patch('windows_app._already_registered', return_value=True):
            self.assertFalse(add_to_google_calendar(event))
        self.service.events.return_value.insert.assert_not_called()
        self.service.events.return_value.update.assert_not_called()


if __name__ == '__main__':
    import unittest
    unittest.main()
