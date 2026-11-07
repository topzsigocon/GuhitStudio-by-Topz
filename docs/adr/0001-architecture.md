# ADR 0001: Engine and module architecture

Status: accepted (M0)

- UI: Kotlin + Jetpack Compose. Canvas: C++20 engine on OpenGL ES 3.0 via a JNI bridge (`core:engine`).
- Rendering lives behind a small native API so a Vulkan backend can be added later without touching UI code.
- Canvas space: origin at canvas center, 2048x2048 test canvas in M0. Screen = t + s * R(rotation) * canvasPoint.
- All GL calls happen on the GLSurfaceView render thread; the UI thread only publishes an immutable transform.
- Planned (M1): tiled GPU canvas, brush engine, history, `.guhit` file format (ZIP + JSON manifest + LZ4 tiles).
- Offline by design: no INTERNET permission; CI fails if it appears in the merged manifest.
