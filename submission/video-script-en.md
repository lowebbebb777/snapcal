# SnapCal demo voiceover

Target length: approximately 1 minute 45 seconds.

> Screenshots are how events get lost. We save an announcement from a chat or social post, but the date never reaches our calendar.
>
> SnapCal turns that screenshot into a calendar event, while keeping the private image on the Android device.
>
> Here is the app. There is no account, API key, or cloud setup. I select a writable Android calendar. For the broadest privacy, I can share just one image from Gallery or LINE. I can also explicitly start persistent monitoring, which places this small bubble at the edge of the screen.
>
> This demo image contains one clear Japanese event: SnapCal Demo, July twenty-first at seven P.M. I share it to SnapCal. The bundled Japanese OCR and deterministic date rules run locally. The app only proceeds when there is exactly one title, one date, and one start time, and the event is safely in the future.
>
> The result is added to the selected calendar with a thirty-minute reminder. The notification also provides Undo, which removes only the event created by this SnapCal operation.
>
> Ambiguous screenshots are intentionally rejected instead of guessed. The release APK has no Internet or network-state permission, and SnapCal never stores the image, full OCR transcript, event title, location, or URL.
>
> We built SnapCal with Codex and GPT-5.6. GPT-5.6 helped design the Android architecture, deterministic safety gate, recoverable Calendar writes, adversarial tests, and an executable privacy release gate. GPT-5.6 is part of the development workflow—not the installed runtime.
>
> SnapCal makes a screenshot useful, while treating uncertainty and privacy as product features.
