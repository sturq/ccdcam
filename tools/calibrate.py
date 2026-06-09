#!/usr/bin/env python3
"""
Calibration helper for matching CCDCam output to a reference video.

Extracts per-frame statistics — mean RGB, std dev, saturation, high-frequency
energy ("grain"), vignette ratio — and prints a side-by-side comparison so
shader constants can be tuned without visual inspection bouncing huge PNGs
through the chat context.

Usage:
    python tools/calibrate.py reference.mp4 mine.mp4 [N=30]
"""
import sys
from pathlib import Path
import subprocess
import tempfile

import numpy as np
from PIL import Image


def extract_frames(video: Path, n: int) -> list[np.ndarray]:
    """Pull n evenly-spaced frames as RGB arrays."""
    # probe duration
    out = subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(video)],
        text=True,
    ).strip()
    duration = float(out)
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        # space frames evenly, skip the first/last 0.5s for clean frames
        timestamps = np.linspace(0.5, max(duration - 0.5, 0.5), n)
        for i, ts in enumerate(timestamps):
            subprocess.run(
                ["ffmpeg", "-y", "-ss", f"{ts:.3f}", "-i", str(video),
                 "-frames:v", "1", "-vf", "scale=480:-1",
                 str(td / f"f{i:03d}.png")],
                check=True, capture_output=True,
            )
        frames = sorted(td.glob("f*.png"))
        return [np.asarray(Image.open(f).convert("RGB"), dtype=np.float32) / 255.0
                for f in frames]


def luma(img: np.ndarray) -> np.ndarray:
    return img @ np.array([0.299, 0.587, 0.114], dtype=np.float32)


def stats(frames: list[np.ndarray]) -> dict:
    """Per-video stats by averaging per-frame stats."""
    rs, gs, bs = [], [], []
    contrast = []
    saturation = []
    grain = []
    warm_ratio = []
    vignette = []
    for f in frames:
        rs.append(f[..., 0].mean())
        gs.append(f[..., 1].mean())
        bs.append(f[..., 2].mean())
        L = luma(f)
        contrast.append(L.std())
        # saturation: mean of (max-min)/max across pixels
        mx = f.max(axis=-1)
        mn = f.min(axis=-1)
        sat = np.where(mx > 0.01, (mx - mn) / np.maximum(mx, 1e-3), 0).mean()
        saturation.append(sat)
        # grain: std dev of luma - luma_blurred (high-freq energy)
        from PIL import ImageFilter
        bl = np.asarray(
            Image.fromarray((L * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)),
            dtype=np.float32,
        ) / 255.0
        grain.append((L - bl).std())
        # warm ratio: red mean / blue mean
        warm_ratio.append(rs[-1] / max(bs[-1], 0.001))
        # vignette: corner brightness vs center brightness
        h, w = L.shape
        cy, cx = h // 2, w // 2
        center = L[cy - h // 8:cy + h // 8, cx - w // 8:cx + w // 8].mean()
        corners = np.mean([
            L[:h // 8, :w // 8].mean(),
            L[:h // 8, -w // 8:].mean(),
            L[-h // 8:, :w // 8].mean(),
            L[-h // 8:, -w // 8:].mean(),
        ])
        vignette.append(corners / max(center, 0.001))
    return {
        "r_mean": float(np.mean(rs)),
        "g_mean": float(np.mean(gs)),
        "b_mean": float(np.mean(bs)),
        "luma_std (contrast)": float(np.mean(contrast)),
        "saturation": float(np.mean(saturation)),
        "grain (hf_std)": float(np.mean(grain)),
        "warm_ratio (r/b)": float(np.mean(warm_ratio)),
        "vignette (corner/center)": float(np.mean(vignette)),
    }


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    ref = Path(sys.argv[1])
    mine = Path(sys.argv[2])
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    print(f"Extracting {n} frames from each video...")
    ref_frames = extract_frames(ref, n)
    mine_frames = extract_frames(mine, n)
    print(f"  ref: {len(ref_frames)} frames {ref_frames[0].shape}")
    print(f" mine: {len(mine_frames)} frames {mine_frames[0].shape}")
    ref_s = stats(ref_frames)
    mine_s = stats(mine_frames)
    print()
    print(f"{'metric':32s} {'ref':>10s} {'mine':>10s} {'delta':>10s} {'hint':s}")
    print("-" * 90)
    for k in ref_s:
        r = ref_s[k]
        m = mine_s[k]
        d = m - r
        hint = "↑ mine" if d > 0 else "↓ mine"
        if abs(d / max(abs(r), 0.001)) < 0.05:
            hint = "≈ match"
        print(f"{k:32s} {r:>10.4f} {m:>10.4f} {d:>+10.4f} {hint}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
