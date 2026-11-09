# ADR 0004: Layer pipeline (M2)

Status: accepted

- Each layer = one RGBA8 premultiplied GPU texture (+FBO), canvas-sized. Stack order lives in the engine
  (`gLayers`, bottom to top) and is mirrored from the Kotlin `LayerStack` (source of truth for names/ids).
- `flat` = cached composite of all visible layers. The screen samples only `flat` (plus white paper).
- `recomposite(rect)`: walks visible layers bottom->top through two scratch textures (blend modes need the
  backdrop), under a scissor rect, then blits the rect into `flat`. Stroke previews and undo recomposite only
  the touched rect; property/structure changes recomposite everything.
- Blend modes are implemented in one fragment shader (W3C compositing formulas, premultiplied-aware).
  Mode ids are part of the file format: never renumber.
- Clipping mask: a clipped layer's alpha is multiplied by the nearest non-clipped layer below (base alpha).
- Strokes commit in place with fixed-function blending (normal / erase / alpha-lock via separate blend).
- Undo entries are per layer (before/after pixels of the stroke rect). Removing or merging a layer purges its
  entries. Structural undo is planned (M2b): entries would own detached GPU textures.
- Memory: layer limit = (512 MB / canvas bytes) - 4 fixed textures, clamped to 1..100.
