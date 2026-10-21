# Orbity

**Use your Wear OS watch as a remote for your Android phone's camera** — like Camera Remote on the Apple
Watch, built around the rotating bezel of the Galaxy Watch Classic.

Put your phone on a tripod, step back, and see exactly what it sees on your wrist. Frame the shot, zoom with
the bezel, tap to focus, and take the photo or start the video from the watch.

- **Live preview** on the watch (about 10 frames a second, about 0.15 s behind the phone)
- **Bezel controls** — Mode, Zoom, Filter, Exposure, Timer, Flash
- **Tap to focus** on the watch or the phone
- **Photos and videos** (with sound), 3 / 5 / 10 s timer, front and back camera
- **11 colour filters** — Vivid, Warm, Cool, Chrome, Dramatic, Fade, Vintage, Sepia, Mono, Noir
- **Night / HDR / Portrait** modes on phones that offer them to apps
- **Live listening** — hear the phone's microphone on the watch, even while recording
- **Works with the phone locked** — the camera starts silently in the background
- **Private by design** — the normal build has no internet access at all

---

## What you need

| | |
|---|---|
| **Phone** | Android 10 or newer with a camera |
| **Watch** | Wear OS 3 or newer (e.g. Galaxy Watch 4, 5, 6, 7, Pixel Watch), paired with the phone |
| **For installing on the watch** | The free **Wear Installer 2** app on the phone, and Wi‑Fi (or the phone's hotspot) |

The watch must already be paired with the phone (for Galaxy watches: through the **Galaxy Wearable** app).

## Download

Get the latest version from the **[Releases page](https://github.com/ambuj14sept/Orbity/releases/latest)**.
There are two files — install **both**, one on each device:

| File | Install on |
|---|---|
| `Orbity-phone.apk` | Phone |
| `Orbity-watch.apk` | Watch |

These are the normal builds: they **never send anything off your phone** (no internet permission at all).
Newer versions install over the top — your photos and settings are kept.

> Testers may also get `Orbity-phone-dev.apk` / `Orbity-watch-dev.apk` from the developer. These also send
> logs and crash reports to the developer (switchable on the phone). Normal and dev files work together and
> install over each other.

---|---|---|
| `Orbity-phone.apk` | Phone | You just want to use Orbity. **Never sends anything off your phone.** |
| `Orbity-watch.apk` | Watch | (goes with the phone file above) |
| `Orbity-phone-dev.apk` | Phone | You are helping test: also sends logs and crash reports to the developer. |
| `Orbity-watch-dev.apk` | Watch | (goes with the dev phone file) |

Any phone file works with any watch file. You can switch between the normal and dev versions at any time by
installing the other file over the top — your photos and settings are kept.

---

## Step 1 — Install on the phone

1. Download **`Orbity-phone.apk`** on your phone and tap it.
2. If Android asks, allow **Install unknown apps** for your browser or Files app, then go back and tap
   **Install**.
3. Open **Orbity** and allow:
   - **Camera** (required)
   - **Microphone** (for video sound and live listening)
   - **Notifications** (Android shows "Orbity camera on" while the camera is in use)
4. Tap **Allow watch to start the camera** and switch on **Display over other apps** for Orbity.
   This lets the watch start the camera when the phone is locked or you are in another app.

## Step 2 — Install on the watch

Watches can't install files on their own, so you send it from the phone over Wi‑Fi. You only do the
developer-mode part once.

**On the watch — turn on developer mode (once):**
1. **Settings → About watch → Software information** (on some watches: *Software*).
2. Tap **Software version** about **5 times**, until it says developer mode is on.
3. Go back to **Settings → Developer options** and turn on **ADB debugging**.
4. Connect the watch to the **same Wi‑Fi as the phone** (or to the phone's hotspot), then turn on
   **Wireless debugging** in Developer options.

**On the phone — install Wear Installer 2:**

5. Install **Wear Installer 2** from the Play Store.

**Pair the phone with the watch (once):**

6. On the watch: **Wireless debugging → Pair new device**. It shows an address like `10.70.12.34:41235`
   and a **6‑digit code**. Keep this screen open.
7. In Wear Installer 2, use its **Pair** option and enter that address and code.

**Connect and install:**

8. Back on the watch's main **Wireless debugging** screen there is a second address — **the port number is
   different** from the pairing one. Enter it in Wear Installer 2 and connect. Accept **Allow debugging?** on
   the watch.
9. In Wear Installer 2, choose **Custom APK** (or *APK from storage*), pick **`Orbity-watch.apk`** from your
   Downloads and install. It takes 10–30 seconds.

Orbity now appears in the watch's app list. You can turn **Wireless debugging** off again; leave developer
mode on for future updates.

> **Tip:** If pairing or connecting fails, turn Wireless debugging off and on and use the *new* port numbers
> — they change every time. Galaxy watches sometimes turn Wi‑Fi off while connected by Bluetooth; turning
> the watch's Bluetooth off for the few minutes of installing helps (turn it back on afterwards — Orbity
> needs it).

---

## Using Orbity

Open **Orbity on the watch**. The phone's camera starts by itself, even if the phone is locked — you will see
the "Orbity camera on" notification and the green camera dot on the phone, as Android requires.

**On the watch**

- **Icons around the top edge** choose what the **bezel** controls. Tap one (it turns yellow), then turn the
  bezel:
  - 📷 **Mode** — Photo, Video, and Night / HDR / Portrait if your phone offers them
  - 🔍 **Zoom** — one full turn covers the whole zoom range
  - ✨ **Filter** — the preview changes as you turn
  - ☀️ **Exposure** — brighter or darker
  - ⏱ **Timer** — off, 3, 5 or 10 seconds
  - ⚡ **Flash** — off, auto, on (in video mode it works as a torch)
- **Tap the picture** to focus there. The ring turns yellow when focus locks.
- **White button** at the bottom takes the photo. In video mode it is **red**: tap to start, tap again to stop.
- **Left, lower:** 👂 **listen** to the phone's microphone (tap again to stop), and 🔄 **flip** camera.
- **Right, lower:** the **last photo** — tap it to see it full screen, tap again to close.

**On the phone** you can use the camera screen too: tap to focus, use the shutter, and see what the watch is
doing (recording, listening, mode, filter).

Photos are saved in **Pictures/Orbity** and videos in **Movies/Orbity**.

### When the camera stops

The phone uses the camera while the watch app is open, while recording or listening, or while you have
the camera screen open on the phone. About **a second** after none of these apply, the camera closes and
the green dot disappears. The small "Orbity camera on" notification stays for 30 more seconds so that
reopening Orbity on the watch is instant, then it goes away too. You can tap **Stop** in that notification
at any time.

### Live listening

Turn it on with the 👂 button. Keep the watch **away from the phone** (or use earbuds) — if the watch speaker
is near the phone's microphone you will hear a loud feedback whistle. Sound quality is similar to a phone
call so it does not slow down the video preview.

---

## Privacy

- **`Orbity-phone.apk` / `Orbity-watch.apk`** have **no internet permission**. Photos, videos, sound and
  logs stay on your devices. The watch and phone talk over the standard Wear OS connection (Bluetooth or
  your Wi‑Fi).
- The phone always shows the **"Orbity camera on"** notification and Android's camera/microphone dots while
  they are in use, and the camera screen shows **"Watch is listening"** during live listening.
- Crash reports are saved on the phone and only leave it if you tap **Share crash reports**.
- The **dev** versions additionally send logs and crash reports to the developer's own server, and show
  **Live logs** on the phone's camera screen — tap it to switch sending off.

## Troubleshooting

| Problem | Fix |
|---|---|
| Watch says **"Phone not reachable"** | Check the watch is connected to the phone (Galaxy Wearable app) and Orbity is installed on the phone. Tap **Retry**. |
| Watch says **"Tap the Orbity notification on your phone"** | Open Orbity on the phone and tap **Allow watch to start the camera** (Display over other apps). |
| **Phone screen lights up** for a moment when you open Orbity on the watch | Normal when the phone is locked — Android requires the app to appear briefly to start the camera. |
| **No sound** in live listening or videos | Allow the microphone for Orbity in the phone's Settings → Apps → Orbity → Permissions. |
| **Preview looks soft** | The picture size adapts to the connection; it gets sharper when the watch is close to the phone. |
| **Night / HDR / Portrait missing** | Your phone does not offer these to apps (the phone maker decides). They can take a moment to appear the first time. |
| Watch **app not installed** error | Install the phone and watch files from the same download — mixing with very old versions is not supported. |

---

## For developers

Building from source, the log server and how the phone and watch talk to each other are described in
[DEVELOPMENT.md](DEVELOPMENT.md).

| Module | What |
|---|---|
| `mobile` | Phone app: CameraX camera in a foreground service, streams preview frames and microphone audio to the watch |
| `wear` | Watch app: Compose for Wear OS remote with bezel (rotary) input |
| `shared` | Message protocol, filter colour matrices, µ-law audio codec, crash and log shipping |
| `tools` | `log_server.py`: dev server for logs and APK downloads (+ systemd user unit) |
