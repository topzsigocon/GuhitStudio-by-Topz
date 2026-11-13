# Changelog

## Professional brush engine increment — 2026-11-13
- Added native round, square, and diamond stamp geometry in the OpenGL ES brush shader.
- Added deterministic procedural pigment grain with an adjustable amount; works offline and needs no texture downloads.
- Added adjustable stylus pressure response before points are submitted to the native stroke engine.
- Exposed tip shape, pigment grain, and pressure curve controls in the Brush panel.
- Included the new controls in custom brush profiles and JSON import/export while keeping older profile files backward-compatible.
- Tuned pencil, charcoal, dry-brush, texture-dab, flat-brush, and chisel-marker presets to use the new engine controls.
- Added pressure-response unit tests and documentation.

Not included in this increment: user-imported bitmap brush tips, arbitrary texture maps, tilt/rotation dynamics, and physically simulated wet media.

## Brush library preview and per-brush settings
- Added a dedicated PREVIEW / settings button to every brush row in the brush library.
- Added a brush preview dialog with a responsive sample stroke and live controls for size, opacity, hardness, flow, spacing, size/flow pressure, streamline, pigment grain, pressure curve, and tip shape.
- Opening a brush preview selects that brush and loads its preset parameters; changes immediately update the active drawing brush. USE BRUSH closes the editor and returns to the canvas.
