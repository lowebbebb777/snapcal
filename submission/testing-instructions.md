# SnapCal evaluator instructions

## Test package

- APK: `dist/app-demo-release.apk`
- Minimum Android version: Android 10 / API 29
- Final verification target: Android 16 / API 36
- Network: not required; airplane mode is supported
- Account: no SnapCal account or API key is required

Verify the APK before installing:

```bash
cd dist
sha256sum -c app-demo-release.apk.sha256
adb install -r app-demo-release.apk
```

## Device prerequisite

The Android device or emulator must expose at least one writable calendar that supports reminders. A local calendar is sufficient; a Google account is not required. If a synced calendar is selected, its provider may sync the event using the device's normal calendar behavior.

## Fast path: share one screenshot

1. Open SnapCal.
2. Tap **カレンダーを選ぶ** and grant read/write calendar access.
3. Select a writable calendar.
4. From Gallery or another Android app, share one PNG or JPEG to **SnapCal**.
5. Tap **カレンダー権限を許可して処理** if shown.
6. Use an image containing a single clear event, for example:

   ```text
   SnapCalデモ
   7月21日 19:00
   ```

7. Confirm that the result says **カレンダーへ追加しました** and that the calendar contains a one-hour event with a 30-minute reminder.
8. Use **取り消す** in the result notification and confirm the event is deleted.

## Persistent screenshot path

1. In SnapCal, tap **常駐監視を開始**.
2. Grant calendar, photo, notification, and overlay access when Android requests them.
3. Return to SnapCal and confirm **常駐監視中** plus the small edge bubble.
4. Take a screenshot containing one clear future event.
5. Confirm the bubble changes during processing and the result notification appears.
6. Tap **監視を停止** after the test.

On Android 14 or later, limited photo access intentionally disables automatic monitoring; the one-image share path remains available.

## Expected safe rejections

SnapCal should not create an event when an image has multiple titles/dates/times, multiple separated event regions, an incomplete event, a past explicit year, or phrases such as `営業時間`, `受付時間`, `販売期間`, `有効期限`, or `締切`.

## Privacy checks

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt dump permissions dist/app-demo-release.apk
```

The output must not contain `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`.
