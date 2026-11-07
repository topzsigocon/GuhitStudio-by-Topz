# GuhitStudio by Topz

Native Android digital painting app. 100% offline, no ads, no accounts. (Working name.)

**Status:** M1a - drawing (v0.2.0). See the master prompt for the full roadmap (M0-M11).

## Build (GitHub)
1. Push this repo to GitHub (branch `main`).
2. Open **Actions -> CI -> latest run -> Artifacts -> guhit-studio-debug-apk**.
3. Download, unzip, install the APK (allow "install unknown apps").

## Build (local)
Requires JDK 17, Android SDK 35, NDK + CMake 3.22.1 (Android Studio installs these).
```
gradle wrapper --gradle-version 8.10.2   # once, then commit gradlew files
./gradlew assembleDebug
```

## Release
Add repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, then:
```
git tag v0.1.0 && git push origin v0.1.0
```
Create a keystore once: `keytool -genkeypair -v -keystore release.jks -alias guhit -keyalg RSA -keysize 4096 -validity 10000`
then `base64 -w0 release.jks` for the secret. Never commit the keystore.

## Try it
- One finger (or stylus): draw. Two fingers: pan / pinch-zoom / rotate.
- Two-finger tap: undo. Three-finger tap: redo.
- Top bar: brush list, eraser, color, menu (export, finger mode, reset view). Handles collapse the panels.
- Menu -> Export saves a PNG/JPEG through the system file picker.
- Autosave and gallery arrive in M1b: export your work before closing the app.

## License
Personal use only. See LICENSE.
