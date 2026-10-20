# Installing Orbity without a computer

Both APKs are signed with `keystore/orbity-debug.jks`, so builds from any machine install over each other
and the watch and phone apps always trust each other.

## Phone (OnePlus)
1. Open `Orbity-phone.apk` on the phone → allow "Install unknown apps" for the app you opened it from → Install.
2. Open Orbity once, allow Camera and Notifications.
3. Tap **Allow watch to open the camera** and enable "Display over other apps".

## Watch (Galaxy Watch 6 Classic), using only the phone
1. Watch: Settings → About watch → Software information → tap **Software version** 5 times.
2. Watch: Settings → Developer options → enable **ADB debugging** and **Wireless debugging**
   (watch and phone must be on the same Wi‑Fi).
3. Phone: install **Bugjaeger** or **Wear Installer 2** from the Play Store.
4. Watch: Wireless debugging → **Pair new device** — shows IP:port and a pairing code.
5. Phone app: pair using that IP:port + code, then connect to the IP:port shown on the main
   Wireless debugging screen (it differs from the pairing port).
6. Install `Orbity-watch.apk` from the phone app.

## Updating
Install the new APKs the same way; settings and photos are kept.

## Reporting bugs
- Crashes on either device are saved automatically. Watch crashes are forwarded to the phone the next
  time the watch app connects. The phone camera screen then shows **Share crash reports** — send that text.
- For live logs, Bugjaeger can show the watch's or phone's logcat (filter by `RemoteCamera` / `RemoteViewModel`).

## Building
```
export JAVA_HOME=~/.jdks/jdk-17*
./gradlew :shared:testDebugUnitTest :mobile:assembleRelease :wear:assembleRelease
```
APKs: `mobile/build/outputs/apk/release/`, `wear/build/outputs/apk/release/`.
