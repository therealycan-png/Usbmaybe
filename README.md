# PixelFerry

Move photos and videos from any phone, tablet, camera or computer onto an **original Google Pixel XL**, so Google Photos backs them up with the Pixel's free unlimited Original-quality storage. Then, once Google has them, delete them from the device they came from.

- **Full quality.** Files are copied byte for byte. Nothing is converted or compressed, and video and audio are never re-encoded.
- **No batch limit.** 10,000+ files in one run. When the Pixel fills up, PixelFerry waits for Google Photos to back up, frees the space, and carries on by itself.
- **Delete from the source only when it's safe.** A file is offered for deletion only after Google Photos itself has removed it from the Pixel with *Free up space*, which Google does only for items it has backed up. Every delete asks you twice.
- **Tells you whose phone it came from.** Files are tagged like `Mom_iPhone13_IMG_1234.HEIC`. Search `Mom_iPhone13` in Google Photos to see everything from that phone.

| From | How |
|---|---|
| iPhone / iPad | USB cable into the Pixel (Lightning or USB-C to USB-C) |
| Oppo, OnePlus Pad 3, any Android | USB-C to USB-C cable into the Pixel |
| Insta360 / other cameras | USB cable, or the SD card in a USB card reader |
| Windows / Linux computer | Wi-Fi, with the small sender in `desktop/` |

---

## 1. Install on the Pixel XL

1. On the Pixel, open this repository on GitHub (branch `claude/multi-device-photo-transfer-xdaim4`), go to `release/PixelFerry.apk` and tap **Download** (View raw).
2. Open the downloaded file. If Android asks, allow "Install unknown apps" for your browser or Files app.
3. Open **PixelFerry** and go through the **Setup checklist**:
   - **Save photos to the Pixel**: Allow.
   - **See when Google Photos is backing up**: turn on *PixelFerry backup watcher*.
   - **Auto-tap "Free up space"** (optional, but means zero taps per wave): Accessibility > *PixelFerry free-up helper* > On. It only works inside Google Photos, and only while a wave is waiting.
   - **Keep running with the screen off**: Allow.
   - **Google Photos backup quality is Original**: check it, then confirm.
4. In **Google Photos** on the Pixel: Backup **On**, quality **Original**.

## 2. Send from a phone, tablet or camera (cable)

1. Plug the device into the Pixel.
   - **iPhone:** unlock it and tap **Trust / Allow**. One-time setting for full quality: iPhone **Settings > Photos > Transfer to Mac or PC > Keep Originals**.
   - **Oppo / OnePlus / Android:** swipe down on that device, tap the USB notification, choose **File transfer**. If the Pixel doesn't see it, tap the Pixel's own USB notification and choose **USB controlled by: This device**.
   - **Camera:** set its USB mode to MTP/PTP. If it only offers "storage", or you use a card reader, tap **Choose a folder / SD card / USB drive** instead.
2. Tap **Open …** in PixelFerry and type whose it is (e.g. *Mom*) and which device (e.g. *iPhone13*).
3. Choose **Everything**, **By date range**, **By folder / album**, or **Pick photos myself**, then tap **Start transfer**.
4. After the first transfer from a new device, Google Photos asks whether to back up the new folder (or go to Google Photos > Backup > Back up device folders). Turn the **PixelFerry/Owner_Device** folder **On**. This happens once per device.
5. Leave it running. You can leave PixelFerry open on screen (it keeps the screen on so the helper can tap) or turn the screen off (then you get one notification per wave to tap *Free up space*).
6. When it says **Done**, the card **Delete from …** shows how many files are confirmed backed up. Tap it, then confirm twice.

**Unplugged by accident?** Plug back in, open the device, and tap **Resume last transfer**. Finished files are never copied twice.

## 3. Send from a Windows or Linux computer (Wi-Fi)

1. On the Pixel, tap **A computer**, then **Send to my computer**. Send the zip to your computer with Nearby Share, Google Drive or a USB cable. Some email apps block it. It already contains this Pixel's code.
2. On the computer, extract the zip (right-click > Extract All).
3. **Windows:** double-click **PixelFerry Send**. **Linux:** run `./pixelferry-send.sh`.
4. Choose your photo folder. The sender finds the Pixel on the Wi-Fi by itself, so there's no address or code to type.

That's it. Next time, just double-click again. Stop any time (close the window), and run it again to continue where it left off.

- No Python? On Windows, the sender downloads a private copy from python.org the first time (about 10 MB, no install, no admin). On Linux, Python 3 is usually already there.
- At the end it asks whether to delete the photos Google has already backed up from that folder. It asks you to type `DELETE`, then `yes`, and re-checks every file's SHA-256 first.
- "Forget paired computers" on the Pixel makes a new code and disconnects every computer.

<details><summary>Advanced: command line</summary>

```
python pixelferry_send.py send "D:\Photos" --owner Shawn --device Laptop            # finds the Pixel, uses the saved pairing
python pixelferry_send.py send "D:\Photos" --code XXXX-XXXX-XXXX-XXXX-XXXX-XXXX ...  # first time, if not using the bundle
python pixelferry_send.py send "D:\Photos" --to 192.168.1.20 ...                     # if Wi-Fi blocks discovery
python pixelferry_send.py delete-backed-up "D:\Photos"
```
Add `--since 2024-01-01 --until 2024-12-31` for a date range.
</details>

## 4. Getting everything out of iCloud (to stop paying for it)

The cable can only reach photos that are actually stored on the iPhone.

1. iPhone **Settings > [your name] > iCloud > Photos > Download and Keep Originals**. Let it download as much as fits.
2. Transfer with PixelFerry and let it finish (all files show as backed up).
3. Delete them from the iPhone with PixelFerry's delete button. With iCloud Photos on, this also removes them from iCloud. They sit in *Recently Deleted* for 30 days; empty it to free iCloud space.
4. Repeat until iCloud is empty, then downgrade the iCloud plan.

If you have a Windows PC, *iCloud for Windows* can download everything at once, and the computer sender can then push it to the Pixel. That's faster for very large libraries.

## 5. Limits you should know

- **Pixel XL battery:** when the Pixel powers the other phone over the cable it usually can't charge itself. PixelFerry pauses at 12% and continues at 25%. For very long runs, a powered USB-C hub that supports charge-while-host lets the Pixel charge (not all hubs work).
- **Videos over 10 GB:** Google Photos can't back these up, so PixelFerry warns you and leaves them on the device.
- **iPhone videos over 4 GB over the cable:** iPhones report sizes in 32 bits, so these are checked by video structure instead of size. If one can't be copied completely, it is marked failed and kept on the iPhone. Send those through a computer.
- **Insta360 raw files** arrive as `NAME_insv.mp4` / `NAME_insp.jpg` so Google Photos backs them up. The bytes are untouched; rename them back to `.insv` / `.insp` to edit in Insta360 Studio. `.lrv` preview files are skipped.
- **Google Photos changes:** the auto-tap helper looks for Google's "Free up space" button. If a Google Photos update moves it, you get a notification to tap it yourself, and nothing unsafe happens.
- **Don't delete files from `DCIM/PixelFerry` by hand** while a transfer is running. If a copy disappears without Google freeing it, PixelFerry keeps the source file and sends it again next time.

## 6. How good is it? (measured, not guessed)

| What | Standard | Result |
|---|---|---|
| Transfer speed | IETF RFC 6349 transfer-time ratio (ideal ÷ actual over the same link) | **98.4 %** overall (99 % camera roll); **99.8 %** of a plain copy that checks nothing |
| Accessibility | Google Accessibility Test Framework (the engine behind Play Console's pre-launch report, WCAG 2.1) on every screen | **52/52 checks pass (100 %)** |
| Code health | Android Lint | **0 errors** |
| Safety | 33 automated tests, incl. a 10,000-file run with an unplug, corrupted copies and Pixel-full waves, and an end-to-end computer test over TLS | **all pass** |

Re-run them with `./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease` (reports: `android/core/build/efficiency.txt`, `android/app/build/accessibility.txt`). Not tested yet: a real phone. There's no Android emulator or USB device in the build environment, so the first real run is the final test.

## 7. How it keeps your stuff safe

- **Verification:** every file is written, forced to flash (fsync), then read back and hashed (SHA-256). The size must match exactly. From a computer, the hash must match the computer's own hash. Bad copies are retried, and nothing unverified is ever recorded.
- **Deletion gate:** a source file is deletable only if its Pixel copy disappeared during a short window that opens at the moment Google's *Free up space* is confirmed, and the copy was on the Pixel when that window opened. Copies that vanish any other way are re-sent, and the source is kept. Every delete is confirmed twice, and USB deletes double-check the file name and size on the device first.
- **Duplicates:** identical files (same SHA-256) from any device are stored once.
- **Wi-Fi security:** TLS with the Pixel's key kept in Android Keystore. The computer pins 80 bits of the Pixel's certificate fingerprint before sending anything, pairs once with the code, then uses its own 160-bit key. Wrong codes lock that address out for 5 minutes. A device pretending to be your Pixel is refused. Discovery replies carry no secrets.
- **Permissions:** the notification watcher reads only Google Photos' notifications. The accessibility helper acts only inside Google Photos and only while a wave is waiting. PixelFerry has no internet servers and no accounts.

---

## For developers

```
android/core     pure Kotlin: ingest/verify engine, ledger, waves, backup tracker, HTTPS receiver (JVM-tested)
android/app      Android 10 app: MTP/PTP + SAF sources, foreground service, watcher, free-up helper, UI
desktop/         stdlib-only Python sender
```

Build and test (JDK 17+, Android SDK 34):

```
cd android
./gradlew :core:test :app:testReleaseUnitTest :app:assembleRelease
```

The tests include a real end-to-end run of the Python sender against the Kotlin receiver over TLS, and a 10,000-file transfer with an unplug, corrupted copies and several Pixel-full waves.
