# Developing Orbity

## Build

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :shared:testDebugUnitTest :mobile:assembleProdRelease :wear:assembleProdRelease   # normal builds
./gradlew :mobile:assembleDevRelease :wear:assembleDevRelease                                # dev builds
```

APKs land in `mobile/build/outputs/apk/<flavor>/release/` and `wear/build/outputs/apk/<flavor>/release/`.

### Flavors

| Flavor | Phone | Watch |
|---|---|---|
| `prod` | No `INTERNET` permission, no log collection | No log collection |
| `dev` | Uploads its own and the watch's logs and crash reports to `orbity.logServer` (switchable on the camera screen) | Forwards its logs to the phone |

Set the dev log server in `local.properties` (not committed):

```
orbity.logServer=http://<machine>.<tailnet>.ts.net:8077
```

Use the full MagicDNS name; Android often cannot resolve the short machine name.

### Signing

Both apps are signed with `keystore/orbity-debug.jks`, committed on purpose: the Wear OS Data Layer only
connects a phone and watch app with the same application id (`com.orbity.remote`) **and** signing key, so
builds from any machine must match. It is a development key — use a separate, private key for anything
published on a store.

## Architecture

- **`CameraService`** (phone) is a foreground service of type camera|microphone that owns `RemoteCamera`
  (CameraX). Android only grants camera/microphone access to a foreground service started while the app is
  visible, so when the watch asks for the camera, `PhoneListenerService` starts the invisible
  `LaunchActivity` (allowed by the *Display over other apps* permission), which starts the service and
  finishes as soon as the service is in the foreground. When nothing needs the camera it closes the
  camera after 1 s (`RemoteCamera.pause`) and stops the service after 30 s; a watch `START` within that
  window reopens the camera without the trampoline.
- **`CameraActivity`** binds to the service and attaches an on-screen preview while visible.
- **Preview frames**: small square JPEGs from an `ImageAnalysis` stream, numbered; the watch acknowledges
  each one (`FRAME_ACK`) and at most two are in flight, so frames never queue. Frame size adapts to latency.
- **Live listening**: 16 kHz mono microphone audio as 8-bit µ-law, 160 ms chunks.
- **Protocol**: message paths and the `CameraState` encoding are in `shared/.../Protocol.kt`.

## Dev server (logs and downloads over Tailscale)

`tools/log_server.py` serves the download page for the APKs in `dist/` and collects uploaded logs in `logs/`.
It listens on the machine's Tailscale addresses and on localhost, port 8077.

```
cp tools/orbity-dev.service ~/.config/systemd/user/
systemctl --user enable --now orbity-dev
systemctl --user status orbity-dev        # journalctl --user -u orbity-dev for its output
```

If the phone cannot open the page while Tailscale is on, allow the port:
`sudo ufw allow in on tailscale0 to any port 8077 proto tcp`.

## Reporting bugs

- Crashes on either device are saved automatically; watch crashes are forwarded to the phone the next time
  the watch app connects. The phone's camera screen then shows **Share crash reports**.
- Dev builds upload logs continuously; prod builds never do. For live logcat without a dev build, the
  Bugjaeger app can show the phone's or watch's log (tag `Orbity`).
