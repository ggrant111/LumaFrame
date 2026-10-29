# Build validation · 2026-10-29

Completed:
- Java 17 compilation of all app sources against Android SDK 35.
- Android resource compilation and manifest linking using AAPT2 35.0.0.
- D8 dex conversion with minimum API 28.
- APK ZIP alignment and signing with a development key.
- APK signature verification passed (v3; supported on this app's minimum API 28).
- APK metadata inspected: com.blockhead.lumaframe, version 1.0.0, minimum API 28, target API 35, launcher activity, and READ_CALENDAR permission.
- Eight executable date-boundary checks passed: UTC all-day date, preceding-day exclusion, exclusive end, overnight start/end days, DST fall-back inclusion/exclusion, and zero-duration events.
- Gradle wrapper generation succeeded (Gradle 8.11.1).

Limitations:
- The full Gradle/Android Gradle Plugin build and Android Lint could not run in this environment because plugin dependency resolution failed. The APK was built directly with the Android SDK tools from the same Java sources and resources. Android Studio must download the configured plugin and dependencies for its first sync.
- No emulator or physical-device execution was performed. No runtime screenshot or account-sync verification is claimed.
- The APK is development-signed. Android Studio's local debug key will differ; uninstall this APK before installing your own build if Android reports a signature mismatch. Uninstalling clears this app's selected-photo list and settings.

To rerun date tests with JDK 17:

```
javac -d checks/out app/src/main/java/com/blockhead/lumaframe/CalendarEvent.java checks/CalendarEventCheck.java
java -cp checks/out com.blockhead.lumaframe.CalendarEventCheck
```

APK installation:

```
adb install -r LumaFrame-v1.0-debug.apk
```

No calendar writes are performed by this app. The installed calendar app owns event creation and any account sync.
