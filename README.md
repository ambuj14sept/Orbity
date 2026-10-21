# Orbity

Control your Android phone's camera from a Wear OS watch — like Apple Watch Camera Remote, built for the
Galaxy Watch 6 Classic's rotating bezel.

- Live camera preview on the watch (~10 fps, ~0.15 s behind the phone; frames are acknowledged so they never queue up)
- **Bezel control**: tap an icon around the top edge — Mode, Zoom, Filter, Exposure, Timer, Flash — then turn the bezel
- **Tap to focus** on the watch preview or the phone screen
- Photo and **video** (with sound; flash becomes a torch), 3 / 5 / 10 s timer, front/back camera switch
- Night / HDR / Portrait modes where the phone maker exposes CameraX extensions
- 11 colour filters (Vivid, Warm, Cool, Chrome, Dramatic, Fade, Vintage, Sepia, Mono, Noir), applied to the
  watch preview, the phone preview and the saved photo
- **Live listening**: hear the phone microphone on the watch, also while recording
- Tap the last capture on the watch to view it full screen
- Opening Orbity on the watch opens the camera on the phone automatically
- Crash reports, and optional live logs to a developer server over Tailscale (see INSTALL.md)

## Modules
| Module | What |
|---|---|
| `mobile` | Phone app: CameraX camera, streams preview frames and microphone audio to the watch, saves to `Pictures/Orbity` and `Movies/Orbity` |
| `wear` | Watch app: Compose for Wear OS remote UI with bezel (rotary) input |
| `shared` | Message protocol, filter colour matrices, µ-law audio codec, crash and log shipping |
| `tools` | `log_server.py`: dev server for logs and APK downloads (+ systemd user unit) |

Watch and phone talk over the Wear OS Data Layer (`MessageClient`). Both apps share the application id
`com.orbity.remote` and the signing key in `keystore/`, which the Data Layer requires.

## Build
Requires JDK 17 and the Android SDK (platform 35).
```
./gradlew :shared:testDebugUnitTest :mobile:assembleRelease :wear:assembleRelease
```

## Install
See [INSTALL.md](INSTALL.md) — including installing on the watch with only your phone.

> `keystore/orbity-debug.jks` is a development key committed on purpose so builds from any machine match.
> Use a separate, private key for anything you publish.
