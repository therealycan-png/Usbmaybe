# PixelFerry: instructions for Claude

PixelFerry is an Android 10 app for an original Pixel XL that copies photos/videos (USB from phones/tablets/cameras, Wi-Fi from a computer) into `DCIM/PixelFerry/<Owner_Device>/` so Google Photos backs them up. Code: `android/core` (pure Kotlin, JVM-tested), `android/app` (Android), `desktop/` (Python sender). The user's working branch is `claude/multi-device-photo-transfer-xdaim4`.

## If the Pixel is connected to this computer: diagnose and fix

The user's standing request: "I only have to connect the Pixel to a computer with Claude on it and it gets fixed." So when a Pixel is attached, do this without waiting to be asked step by step:

1. `adb devices` (install Android platform-tools if missing). If "unauthorized", ask the user to tap **Allow** on the Pixel.
2. Pull the diagnostics the app writes as things happen:
   ```
   adb pull /sdcard/Download/PixelFerry ./pixelferry-diagnostics
   ```
   - `diagnostics.log`: timestamped events: `RUN start/end/result`, `FAILED` (per-reason counts + examples), `MTP` (read mode the device answered), `ERROR`/`CRASH` (stack traces), `RETRY`.
   - `failed-<device>.tsv`: files not sent yet: itemKey, name, size, reason (`REFUSED`, `UNPLUGGED`, `NO_ROOM`, `TOO_BIG_FOR_GOOGLE`, `CHECK_FAILED`, `INCOMPLETE_BIG`, `PIXEL_STORAGE`, `OTHER`), detail, time.
   - `status.txt`: app version, phone, free space, ledger counts by state, devices, recent problems.
   Also useful: `adb logcat -d -v time | grep -iE "pixelferry|AndroidRuntime|MtpDevice"`.
3. Explain to the user in plain words what went wrong and why (they are not technical; keep it short).
4. Fix the cause in the code, add a test that reproduces it, run
   `cd android && ./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`
   (needs JDK 17+ and Android SDK 34; `local.properties` with `sdk.dir=...`).
5. Install the fix keeping the app's data: `adb install -r android/app/build/outputs/apk/release/app-release.apk`
   (signed with `android/app/pixelferry-sideload.p12`, so it updates in place).
6. Copy the APK to `release/PixelFerry.apk`, update `release/SHA256SUMS.txt`, commit and push to the branch above.
7. Tell the user to open the device in PixelFerry and tap **Retry them** (failed files) or **Resume**.

## Rules that must never break

- Never delete a photo from a source device unless it is confirmed in Google Photos (BACKED_UP in the ledger), and always behind two confirmations.
- Never skip a photo silently: every file not sent is recorded with a reason (`Diagnostics.recordFailures`).
- Copies are byte-identical and verified (SHA-256); nothing unverified goes in the ledger.
- No background automation that opens other apps (the user explicitly had the auto Google Photos checker removed).
- Keep the user's existing data: always `adb install -r`, never uninstall.
