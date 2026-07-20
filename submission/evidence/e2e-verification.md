# SnapCal API 36 end-to-end verification

Verified on 2026-07-20 with the signed demo release APK on an Android 16 / API 36 AOSP x86_64 emulator. Hardware acceleration was unavailable, so the emulator used software CPU rendering; this affects speed, not the exercised Android APIs.

## Share path

1. Installed `app-demo-release.apk` and granted Calendar and notification access.
2. Sent `snapcal-demo-event.png` to `ShareReceiverActivity` as an Android `ACTION_SEND` content URI.
3. Confirmed bundled Japanese OCR completed and the UI reported **カレンダーへ追加しました**.
4. Queried `CalendarContract.Events` and verified exactly one event named `SnapCalデモ発表会` at the extracted future time.
5. Expanded the private result notification and used **取り消す**.
6. Queried `CalendarContract.Events` again and verified the event was absent; the notification changed to **予定を取り消しました**.

## Always-on screenshot path

1. Started the user-visible foreground monitoring service with overlay access.
2. Verified the main screen displayed **常駐監視中**, the foreground-service notification was active, and the edge bubble was visible.
3. Published a new image named `Screenshot_bubble.png` under `Pictures/Screenshots/` through MediaStore.
4. Verified the observer accepted the new screenshot, OCR completed locally, and the bubble changed from `S` to `✓`.
5. Queried `CalendarContract.Events` and verified `SnapCalバブル検証会` was added at the extracted future time.

## Independent Undo for multiple events

1. Published two distinct future-event screenshots while monitoring was active.
2. Verified Calendar contained both `SnapCal通知検証A` and `SnapCal通知検証B`.
3. Verified NotificationManager held two result notifications with distinct content-hash tags and independent **取り消す** actions.
4. Used the first action and verified only event B was deleted while event A remained.
5. Used the remaining action and verified event A was then deleted. Both notification records changed independently to **予定を取り消しました**.

## Automated release evidence

- `./gradlew clean releaseGate`: successful, 174 actionable tasks.
- Unit tests: 69 demo + 69 production = 138 tests; 0 failures/errors.
- `lintVitalDemoRelease` and `lintVitalProductionRelease`: successful.
- Final APK inspection: neither release contains `INTERNET` nor `ACCESS_NETWORK_STATE`.
- Dependency inspection: the CCT network backend and known HTTP clients are absent.
- APK signatures: v2 verified, one signer for each hackathon APK.
- `sha256sum -c`: both `dist` checksums verified.

Screenshots are in `submission/screenshots/` and include the share flow, processing/result states, Undo notification, monitoring bubble, automatic-add bubble, and multiple independent result notifications.
