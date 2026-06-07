#!/usr/bin/env python3
"""
Reference implementation of the CCD camcorder shader in pure numpy.
Mirrors app/src/main/assets/shaders/ccd.frag step by step so we can
iterate the look without rebuilding the APK every time.

Usage:
    python tools/sim.py input.jpg output.jpg
    python tools/sim.py input.mp4 output.mp4   # requires ffmpeg in PATH
"""
import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

# ---------- shader constants (kept in sync with ccd.frag) ----------
LINES = 480.0
SMEAR_THRESHOLD = 0.88
SMEAR_STRENGTH = 1.6
SMEAR_SAMPLES = 8
SMEAR_RANGE = 0.45
HORIZONTAL_FLARE = 0.55
FLARE_THRESHOLD = 0.93
FLARE_RANGE = 0.15
FLARE_SAMPLES = 5
CHROMA_NOISE_AMP = 0.07
LUMA_GRAIN_AMP = 0.05
BLACK_LIFT = 0.06
WARM_GRADE = np.array([1.07, 1.02, 0.94], dtype=np.float32)
DESAT = 0.9
SCANLINE_AMP = 0.025
VIGNETTE_STRENGTH = 0.45

LUMA_W = np.array([0.299, 0.587, 0.114], dtype=np.float32)


def luma(img: np.ndarray) -> np.ndarray:
    return img @ LUMA_W


def downsample(img: np.ndarray, lines: float) -> np.ndarray:
    h, w, _ = img.shape
    target_h = int(lines)
    target_w = max(1, int(round(target_h * w / h)))
    small = Image.fromarray((img * 255).astype(np.uint8)).resize(
        (target_w, target_h), Image.BILINEAR
    )
    back = small.resize((w, h), Image.NEAREST)
    return np.asarray(back, dtype=np.float32) / 255.0


def vertical_smear(img: np.ndarray) -> np.ndarray:
    """For each column, find bright pixels and bleed them vertically."""
    h, w, _ = img.shape
    L = luma(img)
    mask = np.clip((L - SMEAR_THRESHOLD) / max(1e-3, 1.0 - SMEAR_THRESHOLD), 0.0, 1.0)
    mask = mask * mask * (3 - 2 * mask)
    smear = np.zeros_like(L)
    max_dy = int(SMEAR_RANGE * h)
    for i in range(1, SMEAR_SAMPLES + 1):
        t = i / SMEAR_SAMPLES
        dy = int(t * max_dy)
        falloff = (1.0 - t) / SMEAR_SAMPLES
        if dy == 0:
            continue
        up = np.zeros_like(mask)
        up[dy:, :] = mask[:-dy, :]
        down = np.zeros_like(mask)
        down[:-dy, :] = mask[dy:, :]
        smear += np.maximum(up, down) * falloff
    smear = smear * SMEAR_STRENGTH
    tint = np.stack(
        [smear * 0.9, smear * 0.95, smear * 1.1], axis=-1
    ).astype(np.float32)
    return img + tint


def horizontal_flare(img: np.ndarray) -> np.ndarray:
    """Tight horizontal streak around extreme point highlights only."""
    h, w, _ = img.shape
    L = luma(img)
    mask = np.clip((L - FLARE_THRESHOLD) / max(1e-3, 1.0 - FLARE_THRESHOLD), 0.0, 1.0)
    flare = np.zeros_like(L)
    samples = FLARE_SAMPLES
    max_dx = int(FLARE_RANGE * w)
    for i in range(1, samples + 1):
        t = i / samples
        dx = int(t * max_dx)
        falloff = (1.0 - t) * (1.0 - t) / samples
        if dx == 0:
            continue
        left = np.zeros_like(mask)
        left[:, dx:] = mask[:, :-dx]
        right = np.zeros_like(mask)
        right[:, :-dx] = mask[:, dx:]
        flare += np.maximum(left, right) * falloff
    flare = flare * HORIZONTAL_FLARE
    return img + np.stack([flare, flare, flare * 1.05], axis=-1)


def chroma_noise(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    h, w, _ = img.shape
    n = rng.random((h, w), dtype=np.float32) - 0.5
    out = img.copy()
    out[..., 0] += n * CHROMA_NOISE_AMP * 0.6
    out[..., 2] -= n * CHROMA_NOISE_AMP * 0.6
    return out


def luma_grain(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    h, w, _ = img.shape
    g = (rng.random((h, w), dtype=np.float32) - 0.5) * LUMA_GRAIN_AMP
    return img + g[..., None]


def color_grade(img: np.ndarray) -> np.ndarray:
    out = img * (1.0 - BLACK_LIFT) + BLACK_LIFT
    out = out * WARM_GRADE
    L = luma(out)
    out = L[..., None] * (1.0 - DESAT) + out * DESAT
    return out


def scanlines(img: np.ndarray) -> np.ndarray:
    h, _, _ = img.shape
    y = np.arange(h, dtype=np.float32) / h
    s = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * np.sin(y * LINES * np.pi)
    return img * s[:, None, None]


def vignette(img: np.ndarray) -> np.ndarray:
    h, w, _ = img.shape
    yy, xx = np.meshgrid(
        np.linspace(-0.5, 0.5, h, dtype=np.float32),
        np.linspace(-0.5, 0.5, w, dtype=np.float32),
        indexing="ij",
    )
    d = xx * xx + yy * yy
    v = 1.0 - d * VIGNETTE_STRENGTH
    return img * v[..., None]


def process(img: np.ndarray, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    x = img.astype(np.float32) / 255.0
    x = downsample(x, LINES)
    x = vertical_smear(x)
    x = horizontal_flare(x)
    x = chroma_noise(x, rng)
    x = luma_grain(x, rng)
    x = color_grade(x)
    x = scanlines(x)
    x = vignette(x)
    x = np.clip(x, 0.0, 1.0)
    return (x * 255).astype(np.uint8)


def process_image(src: Path, dst: Path) -> None:
    img = np.asarray(Image.open(src).convert("RGB"))
    out = process(img, seed=42)
    Image.fromarray(out).save(dst, quality=88)
    print(f"wrote {dst}")


def process_video(src: Path, dst: Path) -> None:
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        in_dir = td / "in"
        out_dir = td / "out"
        in_dir.mkdir()
        out_dir.mkdir()
        subprocess.run(
            ["ffmpeg", "-y", "-i", str(src), str(in_dir / "f%05d.png")],
            check=True, capture_output=True,
        )
        frames = sorted(in_dir.glob("f*.png"))
        for i, f in enumerate(frames):
            img = np.asarray(Image.open(f).convert("RGB"))
            out = process(img, seed=i)
            Image.fromarray(out).save(out_dir / f.name)
            if i % 30 == 0:
                print(f"frame {i}/{len(frames)}")
        # extract audio + remux
        subprocess.run(
            [
                "ffmpeg", "-y",
                "-framerate", "30",
                "-i", str(out_dir / "f%05d.png"),
                "-i", str(src),
                "-map", "0:v", "-map", "1:a?",
                "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "copy",
                "-shortest",
                str(dst),
            ],
            check=True, capture_output=True,
        )
    print(f"wrote {dst}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    args = ap.parse_args()
    src = Path(args.src)
    dst = Path(args.dst)
    if src.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"):
        process_image(src, dst)
    else:
        process_video(src, dst)
    return 0


if __name__ == "__main__":
    sys.exit(main())
