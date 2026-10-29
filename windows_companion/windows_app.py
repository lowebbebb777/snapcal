"""SnapCal Windows companion: Win+Shift+S screenshot -> local OCR -> Google Calendar."""
from __future__ import annotations

import ctypes
from datetime import datetime
import os
from pathlib import Path
from threading import Lock, Thread
import tkinter as tk
from tkinter import ttk

from PIL import ImageGrab, Image, ImageDraw
import pytesseract
from pytesseract import Output
import pystray
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

from core import Event, extract

SCOPE = ['https://www.googleapis.com/auth/calendar.events']
ROOT = Path(os.getenv('LOCALAPPDATA', str(Path.home()))) / 'SnapCalWindows'
CLIENT_FILE = ROOT / 'credentials.json'
TOKEN_FILE = ROOT / 'token.json'
CALENDAR_ID = os.getenv('SNAPCAL_CALENDAR_ID', 'primary')


def recognize_lines(image: Image.Image) -> list[str]:
    if path := os.getenv('TESSERACT_CMD'):
        pytesseract.pytesseract.tesseract_cmd = path
    data = pytesseract.image_to_data(
        image.convert('RGB'), lang='jpn+eng', output_type=Output.DICT,
        config='--psm 11',
    )
    grouped: dict[tuple[int, int, int], list[tuple[int, str]]] = {}
    for i, word in enumerate(data['text']):
        if not word.strip():
            continue
        group = (data['block_num'][i], data['par_num'][i], data['line_num'][i])
        grouped.setdefault(group, []).append((data['left'][i], word))
    return [' '.join(word for _, word in sorted(words)) for words in grouped.values()]


def credentials() -> Credentials:
    ROOT.mkdir(parents=True, exist_ok=True)
    saved = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPE) if TOKEN_FILE.exists() else None
    if saved and saved.valid:
        return saved
    if saved and saved.expired and saved.refresh_token:
        saved.refresh(Request())
    else:
        if not CLIENT_FILE.is_file():
            raise FileNotFoundError('Google OAuth Desktop アプリの credentials.json が見つかりません: ' + str(CLIENT_FILE))
        saved = InstalledAppFlow.from_client_secrets_file(str(CLIENT_FILE), SCOPE).run_local_server(port=0)
    TOKEN_FILE.write_text(saved.to_json(), encoding='utf-8')
    return saved


def add_to_google_calendar(event: Event) -> None:
    calendar = build('calendar', 'v3', credentials=credentials(), cache_discovery=False)
    body = {
        'summary': event.title,
        'start': {'dateTime': event.start.astimezone().isoformat()},
        'end': {'dateTime': event.end.astimezone().isoformat()},
        'reminders': {'useDefault': True},
    }
    calendar.events().insert(calendarId=CALENDAR_ID, body=body).execute()


class SnapCalWindows:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title('SnapCal Windows')
        self.last_clipboard_version = ctypes.windll.user32.GetClipboardSequenceNumber()
        self.processing = Lock()
        self.active = True
        self.icon = pystray.Icon(
            'snapcal_windows', self._tray_icon(), 'SnapCal Windows - 監視中',
            menu=pystray.Menu(
                pystray.MenuItem('画面全体を登録', self.capture_screen),
                pystray.MenuItem('監視オン/オフ', self.toggle),
                pystray.MenuItem('終了', self.quit),
            ),
        )

    @staticmethod
    def _tray_icon() -> Image.Image:
        img = Image.new('RGB', (64, 64), '#10241F')
        pen = ImageDraw.Draw(img)
        pen.rounded_rectangle((6, 6, 58, 58), radius=14, outline='#54F0BA', width=4)
        pen.line((18, 33, 28, 43, 47, 22), fill='#54F0BA', width=5, joint='curve')
        return img

    def notify(self, message: str) -> None:
        try:
            self.icon.notify(message, 'SnapCal Windows')
        except Exception:
            pass

    def check_clipboard(self) -> None:
        if self.active:
            seq = ctypes.windll.user32.GetClipboardSequenceNumber()
            if seq != self.last_clipboard_version:
                self.last_clipboard_version = seq
                try:
                    image = ImageGrab.grabclipboard()
                    if isinstance(image, Image.Image):
                        self.dispatch(image.copy())
                except (OSError, ValueError):
                    pass
        self.root.after(650, self.check_clipboard)

    def dispatch(self, image: Image.Image) -> None:
        if not self.processing.acquire(blocking=False):
            return
        Thread(target=self._process, args=(image,), daemon=True).start()

    def _process(self, image: Image.Image) -> None:
        try:
            options = extract(recognize_lines(image), now=datetime.now())
            if not options:
                return
            if len(options) == 1:
                self._commit(options[0])
            else:
                self.root.after(0, lambda: self.choose(options))
        except Exception as exc:
            self.notify('解析できませんでした。Tesseractの日本語データなどを確認してください。')
            print(type(exc).__name__, str(exc))
        finally:
            self.processing.release()

    def _commit(self, event: Event) -> None:
        try:
            add_to_google_calendar(event)
            self.notify('Googleカレンダーに予定を追加しました。')
        except Exception as exc:
            self.notify('予定を登録できませんでした。OAuth設定を確認してください。')
            print(type(exc).__name__, str(exc))

    def choose(self, options: list[Event]) -> None:
        window = tk.Toplevel(self.root)
        window.title('登録する予定を選ぶ')
        window.geometry('430x' + str(min(620, 130 + len(options) * 88)))
        window.attributes('-topmost', True)
        ttk.Label(window, text='登録したい予定を1件クリック', font=('Yu Gothic UI', 13, 'bold')).pack(pady=18)
        for event in options:
            label = f'{event.title}\n{event.start:%Y/%m/%d %H:%M} - {event.end:%H:%M}'
            def selected(e=event):
                window.destroy()
                Thread(target=self._commit, args=(e,), daemon=True).start()
            ttk.Button(window, text=label, command=selected).pack(fill='x', padx=16, pady=5)
        ttk.Button(window, text='登録せず閉じる', command=window.destroy).pack(pady=12)

    def capture_screen(self, *_args: object) -> None:
        self.dispatch(ImageGrab.grab(all_screens=True))

    def toggle(self, *_args: object) -> None:
        self.active = not self.active
        self.icon.title = 'SnapCal Windows - ' + ('監視中' if self.active else '停止中')
        self.notify('スクリーンショット監視' + ('を開始しました' if self.active else 'を停止しました'))

    def quit(self, *_args: object) -> None:
        self.active = False
        self.icon.stop()
        self.root.after(0, self.root.quit)

    def run(self) -> None:
        Thread(target=self.icon.run, daemon=True).start()
        self.root.after(650, self.check_clipboard)
        self.root.mainloop()


if __name__ == '__main__':
    SnapCalWindows().run()
