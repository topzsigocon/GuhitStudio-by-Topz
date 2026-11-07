# Changelog
Format: Keep a Changelog, SemVer.

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
