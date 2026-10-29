"""No real Google API, OAuth credential, browser or user data in these tests."""
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import Mock, patch

from google.auth.exceptions import RefreshError
import windows_app


class CredentialRecoveryTests(TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.client = root / 'credentials.json'
        self.client.write_text('{}', encoding='utf-8')
        self.token = root / 'token.json'
        self.token.write_text('old_token_placeholder', encoding='utf-8')
        self.p1 = patch.object(windows_app, 'ROOT', root)
        self.p2 = patch.object(windows_app, 'CLIENT_FILE', self.client)
        self.p3 = patch.object(windows_app, 'TOKEN_FILE', self.token)
        self.p1.start(); self.p2.start(); self.p3.start()
        self.addCleanup(self.p1.stop); self.addCleanup(self.p2.stop); self.addCleanup(self.p3.stop)

    def fake_credentials(self, valid, expired, can_refresh):
        c = Mock()
        c.valid = valid
        c.expired = expired
        c.refresh_token = 'sample_refresh' if can_refresh else None
        c.to_json.return_value = '{"token":"safe_mock"}'
        return c

    def test_expired_refresh_token_opens_browser_and_replaces_only_on_success(self):
        old = self.fake_credentials(False, True, True)
        old.refresh.side_effect = RefreshError('invalid_grant')
        new = self.fake_credentials(True, False, True)
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', return_value=old):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                flow.return_value.run_local_server.return_value = new
                result = windows_app.credentials()
        self.assertIs(result, new)
        self.assertEqual(self.token.read_text(encoding='utf-8'), '{"token":"safe_mock"}')
        flow.return_value.run_local_server.assert_called_once()

    def test_successful_refresh_does_not_open_browser(self):
        old = self.fake_credentials(False, True, True)
        def refresh(_request):
            old.valid = True
        old.refresh.side_effect = refresh
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', return_value=old):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                result = windows_app.credentials()
        self.assertIs(result, old)
        flow.assert_not_called()

    def test_valid_token_does_not_open_browser(self):
        saved = self.fake_credentials(True, False, True)
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', return_value=saved):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                self.assertIs(windows_app.credentials(), saved)
        flow.assert_not_called()

    def test_manual_reauthorization_does_not_require_new_json_file(self):
        saved = self.fake_credentials(True, False, True)
        new = self.fake_credentials(True, False, True)
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', return_value=saved):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                flow.return_value.run_local_server.return_value = new
                self.assertIs(windows_app.credentials(force_consent=True), new)
        flow.return_value.run_local_server.assert_called_once()

    def test_cancelled_browser_authorization_keeps_old_token(self):
        old = self.fake_credentials(False, True, True)
        old.refresh.side_effect = RefreshError('invalid_grant')
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', return_value=old):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                flow.return_value.run_local_server.side_effect = RuntimeError('user cancelled')
                with self.assertRaises(RuntimeError):
                    windows_app.credentials()
        self.assertEqual(self.token.read_text(encoding='utf-8'), 'old_token_placeholder')

    def test_bad_token_can_be_recovered(self):
        new = self.fake_credentials(True, False, True)
        with patch.object(windows_app.Credentials, 'from_authorized_user_file', side_effect=ValueError()):
            with patch.object(windows_app.InstalledAppFlow, 'from_client_secrets_file') as flow:
                flow.return_value.run_local_server.return_value = new
                self.assertIs(windows_app.credentials(), new)
