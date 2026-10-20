# Orbity

Control your Android phone's camera from a Wear OS watch — like Apple Watch Camera Remote, built for the
Galaxy Watch 6 Classic's rotating bezel.

- Live camera preview on the watch
- Shutter with 3 / 5 / 10 s timer, front/back camera switch
- **Bezel control**: tap the label at the top to pick Zoom, Filter, Exposure, Timer or Flash, then turn the bezel
- 11 colour filters (Vivid, Warm, Cool, Chrome, Dramatic, Fade, Vintage, Sepia, Mono, Noir), applied to the
  watch preview, the phone preview and the saved photo
- Opening Orbity on the watch opens the camera on the phone automatically
- Crash reports from both devices can be shared from the phone app

## Modules
| Module | What |
|---|---|
| `mobile` | Phone app: CameraX camera, streams preview frames to the watch, saves photos to `Pictures/Orbity` |
| `wear` | Watch app: Compose for Wear OS remote UI with bezel (rotary) input |
| `shared` | Message protocol, filter colour matrices, crash logging |

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
