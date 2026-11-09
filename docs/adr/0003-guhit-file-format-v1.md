# ADR 0003: .guhit file format v1 and storage layout (M1b)

Status: accepted

Storage (app-private, offline): `filesDir/artworks/<uuid>/`
- `meta.json`  name, width, height, createdAt, modifiedAt (fast gallery listing)
- `canvas.guhit`  ZIP, deflate level 1
  - `manifest.json`  {format:"guhit", version:1, width, height, colorSpace:"sRGB",
    pixelFormat:"RGBA8_PREMULTIPLIED", layers:[{file, name}]}
  - `layer0.rgba`  raw premultiplied RGBA8, row 0 = top, width*height*4 bytes (lossless)
- `canvas.guhit.bak`  previous good save; `thumb.png`  gallery thumbnail (max 320 px)

Safety: write to `.tmp`, then rename; the previous file becomes `.bak`. Loading falls back to `.bak`.
If the GL context is ever lost, drawing and saving are blocked until the artwork is reloaded from disk,
so a blank canvas can never overwrite a good save.

Planned: tiled layer data with LZ4 (M2+), multiple layers, bumping `version`. Readers must
reject unknown versions.

## Update (M2): format v2
`manifest.json` version 2 adds `activeLayer` and per-layer `id`, `file` (`layer<ID>.rgba`), `name`, `visible`,
`opacity`, `blend` (BlendMode id), `alphaLock`, `clip`, `locked`. The manifest is the first ZIP entry so layers can
be streamed. v1 (`layer0.rgba`, no ids) is still read: ids are assigned in order, settings default.
