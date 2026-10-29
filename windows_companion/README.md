# SnapCal for Windows — 文字をコピーするだけで予定登録

Windows 10/11の **SnapCal.exe 1つ** に統合しました。画像OCR・Tesseract・Pythonのインストールは不要です。タスクトレイで静かに動作し、`Ctrl+C` などでコピーした文章から予定候補を読み取ります。

- **確実な予定が1件：** 自動でGoogleカレンダーに登録。コピー以外の操作は不要。
- **候補が2件以上：** SnapCalの候補選択画面が出るので1件だけクリック。
- **日時・タイトルが不明：** 何も登録しない（誤登録を防止）。
- **同じ予定が登録済み：** Googleカレンダーを照会して重複登録を避ける。
- **画面上の邪魔なバブル：** なし。Windowsの通知領域アイコンだけ。
- **画像：** Windows版では扱わない。通常の文章コピーに集中。
- **Android：** 同じSnapCal製品のAndroid APKはスクショ自動登録に対応。

## Windows版の入手・起動

1. リポジトリのGitHub Actionsで **Build SnapCal Windows portable EXE** の成功した実行結果を開く。
2. Artifacts の `SnapCal-Windows-EXE` をダウンロードしてZIPを展開する。
3. `SnapCal.exe` を起動。Windowsに別の常駐アプリをインストールする必要はありません。
4. 初回のみGoogle認証を設定。下記参照。設定完了後は「予定文章をコピー」するだけです。

## 初回のみ必要なGoogle認証

Googleカレンダーへ**操作ゼロで書き込む**には、Google Calendar APIへのOAuth認証が必須です。SnapCalはユーザーのGoogleパスワードを扱わず、ブラウザでGoogleから許可を得ます。

現時点の試作版では次の1回限りの設定が必要です。

1. Google Cloud Consoleでプロジェクトを作成し、**Google Calendar API** を有効化。
2. OAuth同意画面（Google Auth Platform）を設定し、**デスクトップアプリ** 用OAuthクライアントを作成。アプリがテスト状態の場合は自分のGoogleアカウントをテストユーザーへ追加。
3. ダウンロードした `credentials.json` をSnapCal起動時のファイル選択画面で指定する（手動でコピー先を探す必要はありません）。
4. 次に予定をコピーするとブラウザが開くので、Googleカレンダーへのアクセスを許可する。

設定は `%LOCALAPPDATA%\SnapCal\` に保存。OAuthのJSON・トークンを他人に渡したりGitへコミットしたりしないでください。Google認証を設定しない間は自動登録されません。

## 文章例

```text
青空クリニック 11月2日（月）16:50
```

```text
会議
11月4日（水）
14:30
```

```text
青空クリニック
11月2日（月）16:50
定例ミーティング
11月3日（火）18:30
```

最初の2例は自動登録、最後は候補選択。終了時刻がない場合は1時間後に設定します。

## プライバシーと制限

- コピー内容はメモリ内だけで判定。元の文章・個人情報・コピー履歴をファイル保存しません。
- 日時とタイトルが明確な場合のみ、予定のタイトル・日時がGoogle Calendar APIに送られます。
- Googleカレンダーへの登録・重複確認にはネットワーク接続が必要です。
- さまざまな文章の文脈解釈をAIで行うものではなく、現時点では明示的な日時だけを解析します。
- Windows GUIの実機操作とGoogle OAuthの実際の接続は**未検証**。GitHub Actionsでは構文・単体テスト・実行ファイル生成を確認します。
- exeのコード署名は未実施。Windows SmartScreenの注意表示が出る場合があります。
- 以前の `windows_companion` 画像OCR試作を実行している場合は終了し、**SnapCal.exeだけ**を起動してください。

## ソースからの検証（開発者向け・一般ユーザーには不要）

```powershell
cd windows_companion
py -m pip install -r requirements.txt
py -m unittest discover -s tests -v
py windows_app.py
```

元コードのMITライセンスと著作権表示は同梱の `LICENSE` を参照してください。今回の個人用追加開発は本家PRに提出しません。
