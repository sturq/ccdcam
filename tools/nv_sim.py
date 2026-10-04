#!/usr/bin/env python3
"""
numpy mirror of the NVCam shader (app/src/nv/assets/shaders/ccd.frag), so the
night vision look can be tuned on a JPEG without rebuilding the APK.

Usage:
    python tools/nv_sim.py input.jpg output.jpg
"""
import sys

import numpy as np
from PIL import Image

# ---------- shader constants (kept in sync with nv ccd.frag) ----------
BARREL = 0.08
SOFT = 0.0025
GAIN = 1.30
GAMMA = 0.75
HALO_THRESHOLD = 0.80
HALO_RADIUS = 0.018
HALO_STRENGTH = 0.35
GRAIN_AMP = 0.10
SPARKLE_RATE = 0.0015
SPARKLE_AMP = 0.30
VIGNETTE_STRENGTH = 0.55
PHOSPHOR_LOW = np.array([0.02, 0.07, 0.02], dtype=np.float32)
PHOSPHOR_MID = np.array([0.34, 0.74, 0.16], dtype=np.float32)
PHOSPHOR_HIGH = np.array([0.85, 1.00, 0.60], dtype=np.float32)

LUMA_W = np.array([0.299, 0.587, 0.114], dtype=np.float32)


def sample(L, u, v):
    """Bilinear clamp-to-edge lookup like texture2D, texel centers at (i + 0.5) / n."""
    h, w = L.shape
    x = np.clip(u * w - 0.5, 0, w - 1)
    y = np.clip(v * h - 0.5, 0, h - 1)
    x0 = np.floor(x).astype(np.int32)
    y0 = np.floor(y).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = x - x0
    fy = y - y0
    top = L[y0, x0] * (1 - fx) + L[y0, x1] * fx
    bot = L[y1, x0] * (1 - fx) + L[y1, x1] * fx
    return top * (1 - fy) + bot * fy


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def process(img: np.ndarray, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    h, w = img.shape[:2]
    L = (img.astype(np.float32) / 255.0) @ LUMA_W
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32) + 0.5
    u, v = xx / w, yy / h
    ex, ey = 1.0 / w, 1.0 / h  # one screen pixel; sim has no rotation
    short = min(w, h)

    sx, sy = (xx - 0.5 * w) / h, (yy - 0.5 * h) / h
    rc2 = 0.25 * (w / h) ** 2 + 0.25
    k = (1 + BARREL * (sx * sx + sy * sy)) / (1 + BARREL * rc2)
    u, v = 0.5 + (u - 0.5) * k, 0.5 + (v - 0.5) * k

    sp = SOFT * short
    l = 0.5 * sample(L, u, v) + 0.125 * (
        sample(L, u + ex * sp, v) + sample(L, u - ex * sp, v)
        + sample(L, u, v + ey * sp) + sample(L, u, v - ey * sp)
    )

    hr = HALO_RADIUS * short
    halo = np.zeros_like(l)
    for i in range(8):
        a = i * 0.785398
        du, dv = ex * np.cos(a) * hr, ey * np.sin(a) * hr
        halo += 0.5 * smoothstep(HALO_THRESHOLD, 1.0, sample(L, u + du, v + dv))
        halo += smoothstep(HALO_THRESHOLD, 1.0, sample(L, u + du * 0.5, v + dv * 0.5))
    l = l + halo / 12.0 * HALO_STRENGTH

    l = np.clip(l * GAIN, 0.0, 1.0) ** GAMMA

    # ponytail: rng instead of the shader hash, same distribution, not the same pixels
    def cells2(x):  # one value per 2x2 px cell, like floor(px * 0.5) in the shader
        return np.repeat(np.repeat(x, 2, 0), 2, 1)[:h, :w]
    ch, cw = (h + 1) // 2, (w + 1) // 2
    l = l + (cells2(rng.random((ch, cw), dtype=np.float32)) - 0.5) * GRAIN_AMP * (1.0 - 0.5 * l)
    l = l + cells2(rng.random((ch, cw), dtype=np.float32) >= 1.0 - SPARKLE_RATE) * SPARKLE_AMP
    l = np.clip(l, 0.0, 1.0)[..., None]

    lo = np.clip(l * 2, 0, 1)
    hi = np.clip(l * 2 - 1, 0, 1)
    col = (PHOSPHOR_LOW * (1 - lo) + PHOSPHOR_MID * lo) * (1 - hi) + PHOSPHOR_HIGH * hi

    qx, qy = xx / w - 0.5, yy / h - 0.5
    col = col * (1.0 - (qx * qx + qy * qy) * VIGNETTE_STRENGTH)[..., None]
    return (np.clip(col, 0.0, 1.0) * 255).astype(np.uint8)


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    out = process(np.asarray(Image.open(src).convert("RGB")), seed=42)
    Image.fromarray(out).save(dst, quality=92)
    print(f"wrote {dst}")
