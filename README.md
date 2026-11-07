# Guhit Studio

Native Android digital painting app. 100% offline, no ads, no accounts. (Working name.)

**Status:** M0 - foundation. See the master prompt for the full roadmap (M0-M11).

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

## Try M0
Two fingers: pan, pinch-zoom, rotate. Top panel collapses with the handle. One-finger drawing arrives in M1.

## License
To be decided by the owner (add a LICENSE file).
