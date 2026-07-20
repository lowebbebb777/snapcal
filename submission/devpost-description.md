# SnapCal

## Track

Apps for Your Life

## Tagline

Turn one screenshot into a safe calendar event—entirely on device.

## Inspiration

People screenshot event announcements in LINE, social media, ticket pages, and group chats, then forget to transfer the details into a calendar. The screenshot becomes a dead end. SnapCal makes that single habit useful without asking users to upload their private conversations or images to a cloud service.

## What it does

SnapCal is an Android app with two deliberate entry points:

- Share one image from Gallery, LINE, or another Android app.
- Explicitly start foreground screenshot monitoring and keep a small edge bubble visible.

The app runs bundled Japanese OCR on the device, extracts event evidence with deterministic Kotlin rules, and automatically adds exactly one event only when the title, date, and start time are unambiguous and safely in the future. It adds a 30-minute reminder and posts a private result notification with Undo.

If the screenshot contains multiple events, separated date regions, non-event phrases such as business hours or deadlines, missing fields, a past time, or unsupported calendar capabilities, SnapCal refuses to guess.

## How we built it

The app is written in Kotlin with Jetpack Compose. It uses bundled ML Kit Japanese text recognition, Android `CalendarContract`, Room for minimal operational state, WorkManager for recovery, MediaStore observation for screenshots, and a user-started foreground service for the persistent bubble experience.

The release build has no `INTERNET` or `ACCESS_NETWORK_STATE` permission. SnapCal does not persist images, full OCR text, event titles, dates, locations, or URLs. It keeps only hashes and operational identifiers needed for duplicate prevention, reconciliation, and safe Undo.

## How we used Codex and GPT-5.6

Codex was the primary environment for planning, implementation, testing, privacy review, and release hardening. GPT-5.6 helped convert the concept into a stateful Android architecture, implement the vertical feature slice, design deterministic ambiguity rules, generate adversarial tests, and iterate on review findings.

One of the most important GPT-5.6-assisted decisions was to remove cloud analysis from the runtime entirely. We converted the privacy promise into an executable release gate that fails the build if forbidden network permissions or known network client dependencies appear, then rechecks the completed APK with Android build tools.

GPT-5.6 runs in the development workflow through Codex, not inside the installed app.

## Challenges

Calendar writes can have uncertain outcomes if a provider process fails after receiving a request. Screenshot monitoring also varies across Android versions and vendors. We treated these as state-machine problems instead of assuming success: each write has a unique operation token, expected event signature, reconciliation path, duplicate guard, and constrained Undo.

OCR ambiguity was another challenge. Extracting every date is easy; deciding when not to create an event is the harder product problem. SnapCal uses a fail-closed gate and never asks a language model to guess at runtime.

## Accomplishments

- A complete offline screenshot-to-calendar flow with a visible edge bubble.
- Share-based use that does not require broad photo or overlay access.
- A minimal-data design with no stored screenshot or OCR transcript.
- Recoverable Calendar writes, 30-minute reminders, duplicate protection, and Undo.
- Automated unit, privacy, manifest, dependency, APK-permission, signature, and checksum verification.

## What we learned

Privacy is strongest when it is a build invariant, not a sentence in a policy. We also learned that safe automation is mainly about expressing uncertainty: a useful assistant should automate the clear cases and make the ambiguous cases visibly safe.

## What's next

Next steps are a Play-distributed build with a production signing key, wider device/OEM testing, more deterministic Japanese date formats, accessibility polish, and an optional confirmation UI for ambiguous candidates—while preserving the same no-network runtime boundary.
