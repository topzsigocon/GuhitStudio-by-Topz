#!/usr/bin/env python3
"""Builds the Android adaptive-icon layers from docs/branding/icon-source.png.

Usage (from the repo root):  python3 tools/make_icons.py
Needs: pip install pillow numpy
Outputs mipmap-*/ic_launcher_foreground.png and ic_launcher_monochrome.png.
"""
import os
import numpy as np
from PIL import Image

SRC = "docs/branding/icon-source.png"
RES = "app/src/main/res"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
BG = np.array([0.0, 20.0, 60.0])   # navy of the source image
SAFE_RADIUS_DP = 35.0               # content must stay inside ~70dp circle (adaptive-icon safe zone)

img = np.array(Image.open(SRC).convert("RGB")).astype(np.float64)
r = img[..., 0]
alpha = np.clip((r - 45.0) / 70.0, 0.0, 1.0)             # gold has high red; navy/black has none
a3 = np.maximum(alpha, 1e-3)[..., None]
color = np.clip((img - BG * (1.0 - alpha[..., None])) / a3, 0, 255)  # remove the navy fringe

ys, xs = np.where(alpha > 0.3)
cx, cy = (xs.min() + xs.max()) / 2.0, (ys.min() + ys.max()) / 2.0
rmax = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()

rgba = np.dstack([color, alpha * 255.0]).astype(np.uint8)
src = Image.fromarray(rgba, "RGBA")

BASE = 432  # xxxhdpi canvas (108dp * 4)
scale = (SAFE_RADIUS_DP * 4.0) / rmax
new = (max(1, round(src.width * scale)), max(1, round(src.height * scale)))
resized = src.resize(new, Image.LANCZOS)
canvas = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
canvas.alpha_composite(resized, (round(BASE / 2 - cx * scale), round(BASE / 2 - cy * scale)))

mono = Image.new("RGBA", canvas.size, (255, 255, 255, 0))
mono.putalpha(canvas.getchannel("A"))
mono_rgb = Image.new("RGBA", canvas.size, (255, 255, 255, 255))
mono_rgb.putalpha(canvas.getchannel("A"))

for name, px in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    canvas.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
    mono_rgb.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)
print("icons written; content radius (px) =", round(rmax), " scale =", round(scale, 3))
