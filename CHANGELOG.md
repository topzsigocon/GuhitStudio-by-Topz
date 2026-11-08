# Changelog
Format: Keep a Changelog, SemVer.

## [0.3.0] - M1b: Gallery, autosave, new canvas
### Added
- Gallery (home screen): thumbnails, open, rename, duplicate, delete (with confirmation).
- New Canvas dialog: presets (square, device screen, Full HD, Instagram, A4 150 dpi, poster, 4K)
  and custom size from 64 to 4096 px per side (up to 16.7 MP).
- Autosave: 4 s after the last change, when the app is paused, and when returning to the gallery.
  Atomic writes plus a .bak of the previous save; crash-safe.
- `.guhit` file format v1 (ZIP: manifest.json + lossless premultiplied RGBA layer). See ADR 0003.
- Engine: variable canvas size, load pixels, safe reload if the GPU context is lost.
- Back button: closes pop-ups first, then saves and returns to the gallery.
### Known gaps
- Single layer (layers are M2). Thumbnails refresh when you return to the gallery.

## [0.2.1]
### Changed
- App renamed to "GuhitStudio by Topz" (personal use).
### Fixed
- Opacity slider: top of the slider now snaps to exactly 100% (1% steps), so strokes can be fully opaque.
- Finger strokes no longer fade the paint with simulated pressure (only the line width changes).

## [0.2.0] - M1a: Drawing
### Added
- Real drawing with finger or stylus (GPU brush stamping, stroke buffer, opacity cap).
- Finger Input Mode: simulated pressure from speed, start taper, StreamLine smoothing,
  automatic switch to stylus (fingers then navigate), palm-friendly.
- 5 original brushes + eraser; size and opacity sliders; HSV color picker with swatches.
- Undo/redo (buttons, two-finger tap, three-finger tap) with bounding-rect history.
- Export to PNG (transparent / white background) and JPEG via the system file picker.
- Collapsible top bar, collapsible left panel, pop-up panels (brushes, color, actions).
- Unit tests for brush math.
### Known gaps (coming next)
- Gallery, autosave and new-canvas dialog (M1b). Until then artwork is lost if the app is killed:
  export before leaving the app.
- End taper, tilt, layers (M2).

## [0.1.0] - M0
- Multi-module project, GLES3 renderer, pan/zoom/rotate canvas, CI and release workflows.
