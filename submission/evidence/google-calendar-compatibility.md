# Google Calendar compatibility

Verified against the current SnapCal implementation and official Android/Google documentation on 2026-07-20.

## How the integration works

SnapCal intentionally has no Google OAuth flow, Google API key, Internet permission, or direct Google Calendar API client. Instead it:

1. Queries Android `CalendarContract.Calendars` for visible calendars with contributor-or-higher access.
2. Shows the calendar display name and account name to the user.
3. Writes the event and 30-minute reminder to the selected calendar ID using one `CalendarContract` batch.
4. Leaves server synchronization to the calendar account's Android sync adapter.

The Android documentation states that Calendar Provider supports multiple calendars associated with account types including Google Calendar and gives applications read/write access to calendar tables. Google's documentation states that its **Share Google Calendar data with other apps** setting controls Calendar Provider access, and that synced mobile events appear in Google Calendar on the computer.

## Required Google settings

- The Google account is added to the Android device.
- Calendar account sync is enabled.
- Google Calendar → **Settings → General → Share Google Calendar data with other apps** is enabled.
- The target Google calendar grants writable access and supports reminders.

## Verification boundary

- API 36 E2E already proves SnapCal's account-independent Calendar Provider path: calendar discovery, event/reminder batch insertion, notification, and safe Undo deletion.
- The production code does not filter out Google account types; the chosen calendar ID is carried unchanged into the event insertion.
- **Live Google account synchronization was not executed in this build environment.** The Google APIs API 36 image contained Google Play services and Google Calendar, but the emulator had zero Google accounts. With hardware virtualization unavailable, its Google framework services were also unstable under software emulation. No personal Google credentials were entered.
- Google Calendar compatibility is therefore based on the official Android/Google contracts, SnapCal's verified account-independent Provider path, and the fact that the implementation does not filter out `com.google` account calendars—not on an observed event in Google Calendar web.
- A live synchronization check requires an authenticated Google-enabled Android device or stable emulator. That final check verifies Google's platform sync adapter, not a separate SnapCal network integration.

## Official sources

- Android Developers: https://developer.android.com/identity/providers/calendar-provider
- Google Calendar Help — Calendar Provider data sharing: https://support.google.com/calendar/answer/16089827?hl=en
- Google Calendar Help — Android sync: https://support.google.com/calendar/answer/151674?hl=en-uk
