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

### Brush library
Guhit Studio includes 33 original, editable brush presets spanning inking, comic pens, pencils, charcoal, markers, airbrush, watercolor, oil-style painting, blending, pixel art, spray, calligraphy, and erasing. Open the Brush tool to select a preset and tune hardness, flow, spacing, size-pressure response, flow-pressure response, and streamline. These are native engine presets rather than imported brush files from other commercial apps.

### Custom brush profiles
Open the Brush panel and choose **Save Current Settings as Custom Brush** to save the current brush tuning as a reusable profile. Custom profiles persist locally across app restarts. Use **Export** to create a versioned `Guhit-brushes.json` library file, or **Import** to merge a previously exported library. Imported files are validated for format/version and limited to 500 profiles. The current profile format stores brush engine parameters; bitmap tip textures and custom stamp-shape masks are not yet part of this format.


## Brush engine update (shape + pigment grain)

The brush panel now exposes three native stamp shapes (round, square, diamond), a procedural pigment-grain amount, and a pressure-response curve. Shape and grain are evaluated in the OpenGL ES stamp fragment shader; they are not preview-only controls. Pressure response is applied to stylus pressure before points enter the native stroke queue. These controls are included in saved custom brush profiles and JSON import/export. Grain is procedural and deterministic, so the feature works offline and does not require downloaded texture assets.

Current scope: custom bitmap tip import, full pressure-curve graph editing, tilt/rotation dynamics, and physically simulated wet media remain future engine work.

### Brush library previews
Each brush row has its own **PREVIEW ⚙** button. Opening it selects that brush and displays a sample stroke plus controls for size, opacity, hardness, flow, spacing, pressure response, streamline, pigment grain, pressure curve, and tip shape. Slider changes are applied to the active brush immediately; choose **USE BRUSH** to close the dialog and draw with it. To keep edited settings as a reusable separate preset, use **Save current settings as custom brush** in the brush library.
