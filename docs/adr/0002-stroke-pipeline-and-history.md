# ADR 0002: Stroke pipeline and undo history (M1a)

Status: accepted

- Canvas = two RGBA8 textures (ping-pong) + one stroke buffer, 2048x2048, premultiplied alpha.
- Brush stamps (instanced quads) accumulate into the stroke buffer. Opacity is applied when the stroke
  is composited onto the layer, so overlapping stamps never exceed the opacity cap (Procreate-like).
- Eraser uses the same stamps; the composite step multiplies the layer by (1 - strokeAlpha).
- Live preview and final commit use the same composite shader, so what you see is what is committed.
- Undo/redo store BEFORE and AFTER pixels of the stroke's bounding rectangle (CPU memory),
  capped at 200 entries / 256 MB. The ping-pong layout means the "before" pixels are always still
  available in the previous texture at commit time.
- Path: input points are filtered (StreamLine), turned into quadratic curves through midpoints,
  and stamped at spacing = brushSize * spacing with distance carry between segments.
- Finger input: pressure is simulated from speed; start taper by ramping the first points.
  Stylus: real pressure. Known gaps (planned): end taper, tilt, tiled canvas, layers.
