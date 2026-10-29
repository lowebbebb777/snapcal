# SnapCal Windows companion (prototype)

Windows 10/11向け。Windows標準の **Win+Shift+S** で切り取った画像を、追加操作なしでローカルOCRしてGoogleカレンダーに登録する試作版です。日時の異なる予定が2件以上ある場合のみ選択画面が表示されます。タスクトレイのアイコンから監視停止・画面全体の解析・終了ができます。

> **ソースコード試作段階です。Windows実機での動作とGoogle OAuth接続は未検証です。** Android版とは異なりGoogle Calendar APIのOAuth初期設定が必要です。

## 初回準備

1. WindowsにPython 3.11以降とTesseract OCRをインストール。日本語 `jpn.traineddata` と英語 `eng.traineddata` が必要です。インストール先がPATHにない場合は `TESSERACT_CMD` 環境変数を設定します。
2. `py -m pip install -r requirements.txt`。
3. Google CloudでGoogle Calendar APIを有効化、OAuth同意画面を設定し、デスクトップアプリ用OAuthクライアントの `credentials.json` をダウンロードします。
4. `credentials.json` を `%LOCALAPPDATA%\SnapCalWindows\credentials.json` に置きます。
5. `pythonw.exe windows_app.py` を起動。最初の予定登録時にブラウザのGoogle認証画面が開きます。OAuthトークンは同フォルダの `token.json` に保存されます。ファイルの共有・GitHubへのPushは避けてください。

## 利用

- **Win+Shift+S**で範囲を選択 → 1件なら自動登録、複数件なら候補をクリックして登録。
- トレイアイコンから「画面全体を登録」も使用可能。
- 終了時刻が明示されていないときは開始1時間後を設定。
- OCRはローカル処理。予定のタイトルと日時のみ、登録時にGoogle Calendar APIへ送信します。
- 通知が出ない場合でもGoogleカレンダー上で登録結果を確認してください。

## 制限

- Windowsでの実機検証は未実施で、インストーラも未作成。
- OCRにはTesseractの日本語学習データ、Google登録には個別のOAuth初期設定が必須です。
- 重複登録防止、詳細画面に分散した情報の結合、タイトル選択の汎用性は今後改良が必要です。
- 本家のSnapCal PRには含めず、個人用ブランチに保管します。

## テスト

`py -m unittest discover -s tests -v`
