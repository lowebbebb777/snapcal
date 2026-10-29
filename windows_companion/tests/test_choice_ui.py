"""Widget-level one-click choice tests; no desktop, calendar, or real dialogs."""
from datetime import datetime, timedelta
from unittest import TestCase
from unittest.mock import Mock, patch

from core import Event
import windows_app


class FakeWidget:
    def __init__(self, **kwargs):
        self.command = kwargs.get('command')
        self.text = kwargs.get('text')
        self.geometry_value = None
        self.protocols = {}
        self.destroyed = False
        self.lifted = False
        self.focused = False

    def lift(self):
        self.lifted = True

    def focus_force(self):
        self.focused = True

    def pack(self, **kwargs):
        pass

    def title(self, value):
        self.title_value = value

    def geometry(self, value):
        self.geometry_value = value

    def attributes(self, *args):
        pass

    def protocol(self, name, callback):
        self.protocols[name] = callback

    def destroy(self):
        self.destroyed = True


class ChoiceUiTests(TestCase):
    def setUp(self):
        self.app = object.__new__(windows_app.SnapCalWindows)
        self.app.root = Mock()
        self.app.choosing = False
        self.app._enqueue_write = Mock()
        first = datetime(2026, 11, 3, 14)
        self.events = [
            Event('定例会議', first, first + timedelta(hours=1)),
            Event('青空クリニック', first + timedelta(days=1), first + timedelta(days=1, hours=1)),
        ]
        self.buttons = []
        self.window = FakeWidget()
        self.to_patch = [
            patch.object(windows_app.tk, 'Toplevel', return_value=self.window),
            patch.object(windows_app.ttk, 'Label', side_effect=lambda *a, **kw: FakeWidget(**kw)),
            patch.object(windows_app.ttk, 'Button', side_effect=self.make_button),
        ]
        for item in self.to_patch:
            item.start()
            self.addCleanup(item.stop)

    def make_button(self, *args, **kwargs):
        widget = FakeWidget(**kwargs)
        self.buttons.append(widget)
        return widget

    def test_second_candidate_one_click_adds_exactly_selected_event(self):
        self.app.choose(self.events)
        self.assertEqual(len(self.buttons), 3)
        self.assertFalse(self.window.destroyed)
        self.assertTrue(self.window.lifted)
        self.assertTrue(self.window.focused)
        self.buttons[1].command()
        self.app._enqueue_write.assert_called_once_with(self.events[1])
        self.assertTrue(self.window.destroyed)
        self.assertFalse(self.app.choosing)

    def test_cancel_button_registers_nothing(self):
        self.app.choose(self.events)
        self.buttons[2].command()
        self.app._enqueue_write.assert_not_called()
        self.assertFalse(self.app.choosing)

    def test_one_uncertain_candidate_is_confirmed_not_auto_added(self):
        self.app.choose(self.events[:1], reason='uncertain_intent')
        self.assertEqual(len(self.buttons), 2)
        self.app._enqueue_write.assert_not_called()
        self.buttons[0].command()
        self.app._enqueue_write.assert_called_once_with(self.events[0])

    def test_open_dialog_does_not_spawn_second_choice(self):
        self.app.choose(self.events)
        self.app.choose(self.events)
        self.assertEqual(len(self.buttons), 3)

    def test_window_close_registers_nothing(self):
        self.app.choose(self.events)
        self.window.protocols['WM_DELETE_WINDOW']()
        self.app._enqueue_write.assert_not_called()
        self.assertFalse(self.app.choosing)
