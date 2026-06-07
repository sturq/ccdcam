"""
Sanity tests for the CCD filter so a future tweak that breaks the look
fails CI before the APK ships.

Run with:
    pytest tools/test_sim.py -v

Or directly:
    python tools/test_sim.py
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from sim import (  # noqa: E402
    process,
    vertical_smear,
    horizontal_flare,
    color_grade,
    SMEAR_STRENGTH,
)


def make_dark_with_bright_spot(h=240, w=320, cx=160, cy=120, r=8) -> np.ndarray:
    img = np.full((h, w, 3), 12, dtype=np.uint8)  # near-black
    yy, xx = np.meshgrid(np.arange(h), np.arange(w), indexing="ij")
    mask = (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r
    img[mask] = 255
    return img


def test_output_shape_dtype():
    inp = make_dark_with_bright_spot()
    out = process(inp, seed=0)
    assert out.shape == inp.shape, f"shape changed {inp.shape} -> {out.shape}"
    assert out.dtype == np.uint8, f"dtype is {out.dtype}, expected uint8"
    assert out.min() >= 0 and out.max() <= 255


def test_smear_is_vertical():
    """Pixels in the column above/below a bright spot should brighten more than
    pixels in a column far from the bright spot. Spot placed centrally so smear
    range reaches sample rows."""
    inp = make_dark_with_bright_spot(h=240, w=320, cx=160, cy=120, r=12)
    x = inp.astype(np.float32) / 255.0
    smeared = vertical_smear(x)
    # same column as the spot, 60 rows above it
    above = smeared[60, 160].mean()
    # far column, same row
    elsewhere = smeared[60, 20].mean()
    assert above > elsewhere + 0.005, (
        f"vertical smear not detected: above={above:.4f} elsewhere={elsewhere:.4f}"
    )


def test_no_smear_on_clean_dark_image():
    """A uniformly dark image should not gain any smear."""
    inp = np.full((120, 160, 3), 20, dtype=np.uint8)
    x = inp.astype(np.float32) / 255.0
    smeared = vertical_smear(x)
    diff = np.abs(smeared - x).max()
    assert diff < 0.01, f"smear leaked into clean dark image (max diff {diff})"


def test_no_smear_on_dim_text_pattern():
    """A pattern of 50% gray pixels should NOT trigger smear (it's not blown)."""
    inp = np.full((120, 160, 3), 128, dtype=np.uint8)
    inp[40:50, :] = 180  # bright but not blown
    x = inp.astype(np.float32) / 255.0
    smeared = vertical_smear(x)
    diff = np.abs(smeared - x).max()
    assert diff < 0.05, f"dim midtones should not smear (max diff {diff})"


def test_horizontal_flare_only_on_extreme():
    """The horizontal flare must NOT trigger on luma ~0.85 (text-bright but not blown)."""
    inp = np.full((120, 160, 3), 30, dtype=np.uint8)
    inp[58:62, 78:82] = 215  # bright but below FLARE_THRESHOLD=0.95
    x = inp.astype(np.float32) / 255.0
    flared = horizontal_flare(x)
    diff = np.abs(flared - x).max()
    assert diff < 0.03, f"flare incorrectly triggered on midtones (max diff {diff})"


def test_color_grade_warms_neutral_gray():
    """A neutral gray should pick up a warm tint (R > B after grade)."""
    inp = np.full((10, 10, 3), 0.5, dtype=np.float32)
    out = color_grade(inp)
    r, _, b = out.mean(axis=(0, 1))
    assert r > b, f"warm grade missing: r={r:.3f} b={b:.3f}"


def test_full_pipeline_deterministic():
    """Same seed must produce identical output (catches accidental nondeterminism)."""
    inp = make_dark_with_bright_spot()
    a = process(inp, seed=123)
    b = process(inp, seed=123)
    assert np.array_equal(a, b), "process() not deterministic for fixed seed"


def test_smear_strength_constant_in_range():
    assert 0.5 <= SMEAR_STRENGTH <= 5.0, (
        f"SMEAR_STRENGTH={SMEAR_STRENGTH} outside reasonable bounds"
    )


def _run_all():
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failed = []
    for t in tests:
        try:
            t()
            print(f"OK   {t.__name__}")
        except AssertionError as e:
            print(f"FAIL {t.__name__}: {e}")
            failed.append(t.__name__)
        except Exception as e:
            print(f"ERR  {t.__name__}: {type(e).__name__}: {e}")
            failed.append(t.__name__)
    print(f"\n{len(tests) - len(failed)}/{len(tests)} passed")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(_run_all())
