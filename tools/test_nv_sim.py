"""
Sanity checks for the NVCam look. Run: python tools/test_nv_sim.py
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from nv_sim import process  # noqa: E402


def dark_with_spot(h=240, w=320, r=6):
    img = np.full((h, w, 3), 8, dtype=np.uint8)
    yy, xx = np.mgrid[0:h, 0:w]
    img[(xx - w // 2) ** 2 + (yy - h // 2) ** 2 <= r * r] = 255
    return img


def test_green_phosphor():
    out = process(np.full((120, 160, 3), 128, np.uint8), seed=0).astype(int)
    c = out[50:70, 70:90].reshape(-1, 3).mean(0)
    assert c[1] > c[0] > c[2], f"midtones not P43 green: {c}"


def test_blacks_lifted_not_crushed():
    out = process(np.zeros((120, 160, 3), np.uint8), seed=0).astype(int)
    assert out[50:70, 70:90, 1].mean() > 5, "black should glow faintly green"


def test_halo_around_light():
    img = dark_with_spot()
    out = process(img, seed=0).astype(int)[..., 1]
    ring = out[120, 160 + 10]   # just outside the r=6 spot
    far = out[120, 160 + 60]
    assert ring > far + 10, f"no halo: ring {ring} vs far {far}"


def test_deterministic():
    img = dark_with_spot()
    assert np.array_equal(process(img, seed=3), process(img, seed=3))


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
