"""The same copied text must be processed on every Windows clipboard update."""
import unittest
from queue import Queue
from unittest.mock import Mock, patch

import windows_app


class ClipboardDetectionTests(unittest.TestCase):
    def setUp(self):
        self.app = object.__new__(windows_app.SnapCalWindows)
        self.app.root = Mock()
        self.app.commands = Queue()
        self.app.active = True
        self.app.choosing = False
        self.app.last_clipboard = '会議 2026/11/12 14:10'
        self.app.last_clipboard_seq = 100
        self.app.last_decision = '監視待機中'
        self.app._handle_text = Mock()
        self.app._read_clipboard = Mock(return_value='会議 2026/11/12 14:10')
        self.app._clipboard_sequence = Mock(side_effect=[101, 102])

    def test_identical_text_copied_twice_is_processed_twice(self):
        self.app._poll()
        self.app._poll()
        self.assertEqual(2, self.app._handle_text.call_count)
        self.app.root.after.assert_called_with(self.app.POLL_MS, self.app._poll)

    def test_clipboard_same_sequence_does_not_reprocess(self):
        self.app._clipboard_sequence = Mock(side_effect=[100, 100])
        self.app._poll()
        self.app._poll()
        self.app._handle_text.assert_not_called()

    def test_clipboard_fallback_with_no_sequence_uses_content_change(self):
        self.app._clipboard_sequence = Mock(return_value=0)
        self.app._read_clipboard = Mock(return_value='新しい会議 2026/11/12 14:10')
        self.app._poll()
        self.app._handle_text.assert_called_once()

    def test_monitoring_off_leaves_copy_unprocessed(self):
        self.app.active = False
        self.app._poll()
        self.app._handle_text.assert_not_called()

    def test_parser_errors_do_not_stop_future_monitoring(self):
        self.app._handle_text.side_effect = ValueError('invalid input')
        self.app.notify = Mock()
        self.app._poll()
        self.app.notify.assert_called_once()
        self.app.root.after.assert_called_once()
        self.assertEqual(self.app.last_decision, '予定文の解析エラー')


if __name__ == '__main__':
    unittest.main()
