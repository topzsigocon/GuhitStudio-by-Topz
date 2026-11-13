# GuhitStudio by Topz

Native Android digital painting app. 100% offline, no ads, no accounts. (Working name.)

**Status:** M2 - layers, fill, pipette (v0.6.0). See the master prompt for the full roadmap (M0-M11).

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

## Updating without uninstalling
All builds are signed with the same personal key (`app/signing/guhit-personal.p12`), so Android accepts each new
APK as an update and keeps your artworks. Keep this repo PRIVATE: anyone with the key can sign apps that Android
will treat as updates of this one. If you ever publish the app, create a new key and store it in GitHub Secrets.

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
- Top bar: back to gallery, undo, redo, fit view, menu (export, finger mode, FPS). Bottom: size/opacity sliders and tools (Brush, Eraser, Layers, Color). The gold handle hides/shows the bars.
- Menu -> Export saves a PNG/JPEG through the system file picker.
- Tools: Brush, Eraser, Pipette (pick color), Fill (tap to fill; slider = tolerance), Layers, Color.
- "Layer" button: layer list, opacity, blend modes, alpha lock, clipping mask, lock, merge, reorder.
- Your work is autosaved. The gallery (home) lists all artworks; use "Bagong Canvas" for new ones.

## License
Personal use only. See LICENSE.
