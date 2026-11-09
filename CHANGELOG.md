# Changelog
Format: Keep a Changelog, SemVer.

## [0.4.1]
### Added
- App icon (adaptive + Android 13 themed icon) from the supplied brush artwork. Source and generator:
  `docs/branding/icon-source.png`, `tools/make_icons.py`.
- Fixed personal signing key (`app/signing/guhit-personal.p12`). Every build is now signed with the same
  key, so new versions install OVER the old one: no more uninstalling, and drawings are kept.
### Fixed
- Text in pop-ups, side panel and handles was black on dark (hard to read). Content color is now explicit.
- Pop-up panels are fully opaque (the canvas no longer shows through).
### Note
- The first install of this version still needs ONE uninstall (older builds used random debug keys).

## [0.4.0] - M2: Layers
### Added
- Layers: add, duplicate, delete, reorder, rename, show/hide, select. Layer panel under the top bar.
- Per-layer opacity and 18 blend modes (Normal, Multiply, Darken, Color Burn, Linear Burn, Screen,
  Lighten, Color Dodge, Add, Overlay, Soft Light, Hard Light, Difference, Exclusion, Hue, Saturation,
  Color, Luminosity).
- Alpha lock, clipping mask (clips to the nearest non-clipped layer below), lock layer.
- Merge Down.
- Layer limit depends on canvas size (shown as n/max).
- `.guhit` format v2: every layer is stored with its settings. v1/v0.3 files open normally.
- Saving/loading streams one layer at a time (low memory).
- Export uses the flattened picture (all visible layers).
### Changed
- Engine renderer: per-layer GPU textures, dirty-rect recompositing, strokes are committed in place.
### Known gaps
- Undo/redo covers strokes only (per layer). Adding, deleting, moving, merging layers and changing layer
  settings are NOT undoable yet (delete and merge ask for confirmation).
- No layer thumbnails, groups, masks or multi-select yet (M2b).
- Clipping mask assumes the base layer is Normal/100%.

## [0.3.1]
### Fixed
- Opacity (and color, eraser, finger mode) changes were only sent to the engine after the size slider
  moved. All brush state is now read in the screen scope so every change applies immediately.

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
