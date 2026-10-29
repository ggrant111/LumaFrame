# Luma Frame · Android 1.0

A native Android photo frame with a quiet, Apple-inspired glass interface: full-bleed photos, soft transitions, an oversized clock, a month calendar, and a daily agenda. Original visual design; no Apple assets or services.

## Open in Android Studio

1. Extract this archive and open the **LumaFrame** directory (the folder containing `settings.gradle`).
2. Select **JDK 17** as the Gradle JDK. Install Android SDK Platform 35 if prompted.
3. Let Gradle sync, then Run on an Android 9+ phone or tablet.
4. Tap **Photos** and select one or multiple images. To choose multiple images, long-press the first image in the system picker.
5. Tap **Connect calendar** and allow read access. Enable calendar sync for your account in Android settings.

Build from a terminal: `./gradlew assembleDebug` (macOS/Linux) or `gradlew.bat assembleDebug` (Windows). Debug output: `app/build/outputs/apk/debug/app-debug.apk`.

## Included

- Native Java Android application; no WebView or third-party runtime libraries.
- Landscape split-panel and portrait stacked layouts; scrolls on smaller screens.
- Frosted panels sample a downscaled version of the actual background photo, with tint and fine translucent borders. This works on Android 9+, without relying on Android 12 blur APIs.
- Clock follows the device's 12/24-hour preference.
- Persistent photo selection through Android's document picker; no broad photo-library permission.
- EXIF-aware, downsampled decoding off the main thread; maximum 2048-pixel longest edge.
- Fade-in transitions, previous/next, pause/play, 5/15/30/60/300-second timing.
- Settings for photo-only mode and removing selected photos. Removal never deletes original files.
- Month navigation, Today shortcut, selected-day agenda, recurring event instances, and UTC-safe all-day handling.
- Event taps open the installed calendar app. The + button opens its new-event editor; Luma Frame does not directly write calendar data.
- Refresh on resume, once a minute, and when observed calendar data changes.
- Immersive display and keep-awake while the app is in the foreground.
- No ads, analytics, network permission, server, API key, or account password storage.

## Calendar scope

Luma Frame reads visible calendars exposed by Android's Calendar Provider. Google, Exchange, Outlook, and other accounts work **when their calendar data is actually synced to that provider**. An account appearing inside a vendor app alone does not guarantee availability. Direct Google/Outlook OAuth, direct iCloud login, ICS subscriptions, and cloud photo-album sync are not implemented.

Devices without a calendar provider or document picker (including some Fire TV configurations) need those services or a future dedicated TV integration. This version primarily targets Android phones and tablets. It is not an Android TV launcher.

## Privacy and permissions

`READ_CALENDAR` is requested only when you connect calendars. Denial leaves photo playback usable. You can revoke access in Android Settings. Selected document URI grants persist between launches; moving, deleting, or revoking a photo can make it unavailable, in which case it is removed from this app's list. App backup is disabled. Photos are not copied or uploaded.

## Validation

See BUILD_NOTES.md for the checks actually completed for this package. Physical-device visual and calendar-account testing is still recommended before regular use. Debug builds are for local testing; create your own signing key for releases and retain it for future updates.

## Manual device checks

- First launch with no photos: ambient background and calendar connection prompt.
- Add portrait and landscape photos; rotate device; restart app and verify selection persists.
- Pause/play, previous/next, and each interval; background the app and return.
- Delete or revoke a selected image; verify graceful removal and continued playback.
- Grant/deny/revoke calendar permission. Check an empty date, timed event, recurring event, multi-day event, and all-day event in your timezone.
- Open an event; add an event in the calendar editor and return to see refresh.
- Test small phone widths, landscape tablet, large font settings, and photo-only mode.

## References

- https://developer.android.com/reference/android/provider/CalendarContract.Instances
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/graphics/ImageDecoder
