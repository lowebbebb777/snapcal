"""SnapCal for Windows: copied text -> zero-click Google Calendar registration.

One notification-area process. No image watcher, OCR service or second app.
Copied text is processed in memory; only selected calendar event fields are
transmitted to Google after user-granted OAuth permission.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from hashlib import sha256
import json
import ctypes
import os
from pathlib import Path
from queue import Empty, Queue
import shutil
from threading import Lock, Thread
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from PIL import Image, ImageDraw
import pystray
from google.auth.transport.requests import Request
from google.auth.exceptions import RefreshError
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

from core import Event
from decision import decide

SCOPE = ['https://www.googleapis.com/auth/calendar.events']
ROOT = Path(os.getenv('LOCALAPPDATA', str(Path.home()))) / 'SnapCal'
CLIENT_FILE = ROOT / 'credentials.json'
TOKEN_FILE = ROOT / 'token.json'
CALENDAR_ID = os.getenv('SNAPCAL_CALENDAR_ID', 'primary')
APP_VERSION = '2026.10.29-diagnostic2'


def credentials(force_consent: bool = False) -> Credentials:
    """Recover expired/invalid tokens through Google's real browser OAuth flow."""
    ROOT.mkdir(parents=True, exist_ok=True)
    try:
        saved = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPE) if TOKEN_FILE.exists() else None
    except (ValueError, OSError):
        saved = None
    if force_consent:
        saved = None
    if saved and saved.valid:
        return saved
    if saved and saved.expired and saved.refresh_token:
        try:
            saved.refresh(Request())
        except RefreshError:
            # Google Testing OAuth refresh tokens usually expire after 7 days.
            # A new browser consent is mandatory; never silently claim renewal.
            saved = None
    if not saved or not saved.valid:
        if not CLIENT_FILE.is_file():
            raise FileNotFoundError('Google Calendar OAuth 設定が必要です。トレイから設定してください。')
        saved = InstalledAppFlow.from_client_secrets_file(
            str(CLIENT_FILE), SCOPE,
        ).run_local_server(port=0)
    # Replace atomically only after successful renewal or new browser consent.
    temporary = TOKEN_FILE.with_suffix('.tmp')
    temporary.write_text(saved.to_json(), encoding='utf-8')
    os.replace(temporary, TOKEN_FILE)
    return saved


def _already_registered(service, event: Event) -> bool:
    """Read before write, including events created on Android or other PCs."""
    start = event.start.astimezone()
    items = service.events().list(
        calendarId=CALENDAR_ID,
        timeMin=(start - timedelta(seconds=1)).isoformat(),
        timeMax=(start + timedelta(minutes=1)).isoformat(),
        singleEvents=True,
        maxResults=100,
    ).execute().get('items', [])
    for candidate in items:
        raw = candidate.get('start', {}).get('dateTime')
        if not raw or candidate.get('summary') != event.title:
            continue
        try:
            existing = datetime.fromisoformat(raw.replace('Z', '+00:00'))
        except ValueError:
            continue
        if existing.astimezone() == start:
            return True
    return False


def add_to_google_calendar(event: Event) -> bool:
    """Return False for an already-present event, rather than duplicating it."""
    service = build('calendar', 'v3', credentials=credentials(), cache_discovery=False)
    if _already_registered(service, event):
        return False
    body = {
        'summary': event.title,
        'start': {'dateTime': event.start.astimezone().isoformat()},
        'end': {'dateTime': event.end.astimezone().isoformat()},
        'reminders': {'useDefault': True},
    }
    service.events().insert(calendarId=CALENDAR_ID, body=body).execute()
    return True


class SnapCalWindows:
    POLL_MS = 700

    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title('SnapCal')
        self.commands: Queue[tuple[str, object]] = Queue()
        self.processing = Lock()
        self.active = CLIENT_FILE.is_file() or TOKEN_FILE.is_file()
        self.last_clipboard = self._read_clipboard()
        self.last_clipboard_seq = self._clipboard_sequence()
        self.last_decision = '監視待機中。コピーすると予定かどうか判定します'
        self.last_submitted: dict[str, float] = {}
        self.choosing = False
        self.icon = pystray.Icon(
            'snapcal', self._tray_icon(), 'SnapCal - テキストから予定を登録',
            menu=pystray.Menu(
                pystray.MenuItem('監視オン／オフ', lambda *_: self.commands.put(('toggle', None))),
                pystray.MenuItem('現在の状態・直近の判定', lambda *_: self.commands.put(('status', None))),
                pystray.MenuItem('Google認証を更新', lambda *_: self.commands.put(('reauthorize', None))),
                pystray.MenuItem('Google設定ファイルを変更', lambda *_: self.commands.put(('setup', None))),
                pystray.MenuItem('終了', lambda *_: self.commands.put(('quit', None))),
            ),
        )

    @staticmethod
    def _tray_icon() -> Image.Image:
        img = Image.new('RGB', (64, 64), '#10241F')
        draw = ImageDraw.Draw(img)
        draw.rounded_rectangle((6, 6, 58, 58), radius=12, outline='#54F0BA', width=4)
        draw.line((17, 33, 28, 43, 47, 20), fill='#54F0BA', width=6)
        return img

    @staticmethod
    def _clipboard_sequence() -> int:
        """Windows increments this counter on *every* copy, even identical text."""
        try:
            return int(ctypes.windll.user32.GetClipboardSequenceNumber())
        except (AttributeError, OSError):
            return 0

    def _read_clipboard(self) -> str:
        try:
            value = self.root.clipboard_get()
        except (tk.TclError, UnicodeError):
            return ''
        return value if isinstance(value, str) and len(value) <= 5000 else ''

    def notify(self, message: str) -> None:
        try:
            self.icon.notify(message, 'SnapCal')
        except Exception:
            pass

    def _poll(self) -> None:
        while True:
            try:
                action, payload = self.commands.get_nowait()
            except Empty:
                break
            if action == 'setup':
                self.setup()
            elif action == 'status':
                messagebox.showinfo(
                    'SnapCal の状態',
                    'バージョン: ' + APP_VERSION + '\n'
                    + ('監視中' if self.active else '監視停止中')
                    + '\n\n直近の判定: ' + self.last_decision
                    + '\n\nコピーした文章の本文は保存していません。',
                )
            elif action == 'reauthorize':
                self.reauthorize()
            elif action == 'toggle':
                self.active = not self.active
                if self.active and not (CLIENT_FILE.is_file() or TOKEN_FILE.is_file()):
                    self.active = False
                    self.setup()
                else:
                    self.last_decision = '監視中' if self.active else '監視停止中'
                    self.notify('コピー監視' + ('を開始しました' if self.active else 'を停止しました'))
            elif action == 'choose':
                self.choose(payload)
            elif action == 'notify':
                self.notify(str(payload))
            elif action == 'failed':
                self.last_submitted.pop(str(payload), None)
            elif action == 'quit':
                self._quit()

        sequence = self._clipboard_sequence()
        # Windows explicitly records clipboard *writes*, including when the
        # user copies exactly the same text a second time. Previous versions
        # only checked for different text, making repeated tests appear dead.
        if sequence:
            changed = sequence != self.last_clipboard_seq
            self.last_clipboard_seq = sequence
        else:
            # Fallback for environments where the Windows sequence API fails.
            current = self._read_clipboard()
            changed = current != self.last_clipboard
        if changed:
            value = self._read_clipboard()
            self.last_clipboard = value
            if self.active and value:
                try:
                    self._handle_text(value)
                except Exception:
                    self.last_decision = '予定文の解析エラー'
                    self.notify('コピー文の解析でエラーが発生しました')
            elif not self.active:
                self.last_decision = '監視停止中'
            else:
                self.last_decision = 'テキストではないコピー、または空文字'
        self.root.after(self.POLL_MS, self._poll)

    def _handle_text(self, text: str) -> None:
        # Parse synchronously: pure deterministic rules, no OCR/LLM/network.
        decision = decide(text, now=datetime.now())
        self.last_decision = {
            'auto': '明確な予定を1件検出。カレンダーへ登録処理を開始',
            'review': '予定候補を検出。候補選択画面を表示',
            'ignore': '予定として特定できないため登録しません',
        }.get(decision.mode, '判定できませんでした')
        if decision.mode == 'auto':
            self._enqueue_write(decision.events[0])
        elif decision.mode == 'review':
            self.choose(list(decision.events), reason=decision.reason)
        # Ordinary copied prose is silently ignored. No Google network call.

    def _event_key(self, event: Event) -> str:
        raw = f'{event.title}\0{event.start.isoformat()}\0{event.end.isoformat()}'
        return sha256(raw.encode('utf-8')).hexdigest()

    def _enqueue_write(self, event: Event) -> None:
        key = self._event_key(event)
        if key in self.last_submitted:
            self.notify('同じ予定は今回すでに処理しています')
            return
        if not self.processing.acquire(blocking=False):
            self.notify('処理中です。次の予定は処理終了後にコピーしてください')
            return
        self.last_submitted[key] = time.monotonic()
        if len(self.last_submitted) > 200:
            oldest = min(self.last_submitted, key=self.last_submitted.get)
            self.last_submitted.pop(oldest, None)
        Thread(target=self._write, args=(event, key), daemon=True).start()

    def _write(self, event: Event, key: str) -> None:
        try:
            added = add_to_google_calendar(event)
            msg = 'Googleカレンダーに予定を追加しました' if added else '同じ予定が登録済みです'
            self.commands.put(('notify', msg))
        except Exception:
            # Clear the local key to permit re-trying a temporary auth/network failure.
            self.commands.put(('failed', key))
            self.commands.put(('notify', '登録できませんでした。Google認証・接続を確認してください'))
        finally:
            self.processing.release()

    def choose(self, events: list[Event], reason: str = 'multiple_events') -> None:
        if self.choosing:
            return
        self.choosing = True
        self.last_decision = '候補選択画面を開きました（自動登録はしていません）'
        window = tk.Toplevel(self.root)
        window.title('SnapCal — どの予定ですか？')
        window.geometry(f'450x{min(640, 120 + len(events) * 85)}')
        window.attributes('-topmost', True)
        # A hidden root and a tray icon can leave a child behind the browser.
        # Explicitly raise the single review window without showing a bubble.
        window.lift()
        window.focus_force()
        heading = (
            '予定として登録しますか？（内容を確認）'
            if reason == 'uncertain_intent'
            else '登録する予定を1件選択'
        )
        ttk.Label(window, text=heading, font=('Yu Gothic UI', 13, 'bold')).pack(pady=14)

        def close() -> None:
            self.choosing = False
            window.destroy()

        window.protocol('WM_DELETE_WINDOW', close)
        for event in events:
            label = f'{event.title}\n{event.start:%Y/%m/%d %H:%M} – {event.end:%H:%M}'
            def selected(e=event) -> None:
                close()
                self._enqueue_write(e)
            ttk.Button(window, text=label, command=selected).pack(fill='x', padx=12, pady=5)
        ttk.Button(window, text='登録しない', command=close).pack(pady=6)

    def reauthorize(self) -> None:
        if not CLIENT_FILE.is_file():
            self.setup()
            return
        if not self.processing.acquire(blocking=False):
            self.notify('登録処理中です。完了後に再認証してください')
            return

        def authorize() -> None:
            try:
                credentials(force_consent=True)
                self.commands.put(('notify', 'Google認証が更新されました'))
            except Exception:
                self.commands.put(('notify', '再認証に失敗しました。接続・Google設定を確認してください'))
            finally:
                self.processing.release()

        Thread(target=authorize, daemon=True).start()

    def setup(self) -> None:
        messagebox.showinfo(
            'SnapCal 初期設定',
            'Google Calendar API の「デスクトップアプリ」用 OAuth '
            'credentials.json を選択してください。\n'
            '認証後はコピーした予定文章を自動登録します。',
        )
        path = filedialog.askopenfilename(
            title='Google OAuth 設定ファイルを選ぶ',
            filetypes=[('Google OAuth JSON', '*.json')],
        )
        if not path:
            return
        try:
            with open(path, 'r', encoding='utf-8') as stream:
                obj = json.load(stream)
            if 'installed' not in obj or not obj['installed'].get('client_id'):
                raise ValueError('Google Desktop 用 OAuth JSON ではありません')
            ROOT.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, CLIENT_FILE)
            TOKEN_FILE.unlink(missing_ok=True)
            self.active = True
            self.notify('初回設定が完了しました。次の予定をコピーすると認証が始まります')
        except (OSError, ValueError, json.JSONDecodeError):
            messagebox.showerror('SnapCal', 'OAuthの設定ファイルを読み込めませんでした')

    def _quit(self) -> None:
        self.active = False
        self.icon.stop()
        self.root.quit()

    def run(self) -> None:
        Thread(target=self.icon.run, daemon=True).start()
        if not self.active:
            self.root.after(400, self.setup)
        self.root.after(self.POLL_MS, self._poll)
        self.root.mainloop()


if __name__ == '__main__':
    # Prevent two SnapCal copies from monitoring the same clipboard and
    # competing to write the same calendar event.
    kernel32 = ctypes.windll.kernel32
    kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    singleton = kernel32.CreateMutexW(None, True, 'Local\\SnapCalWindowsSingleInstance')
    if singleton and kernel32.GetLastError() != 183:  # ERROR_ALREADY_EXISTS
        SnapCalWindows().run()
    else:
        # Never fail silently when users try an updated EXE while the old
        # version is still running: this was indistinguishable from a hang.
        ctypes.windll.user32.MessageBoxW(
            None,
            'SnapCal は既に起動しています。\n'
            'タスクトレイのSnapCalから「終了」してから新しい版を起動してください。',
            'SnapCal — 既に実行中',
            0x40,
        )
