<p align="center">
  <img src="docs/icon.png" width="112" alt="CCDCam">
</p>

<h1 align="center">CCDCam</h1>

<p align="center">An open-source camcorder filter for Android. Your camera, run through a 90s Hi8 / MiniDV look, baked into every photo and video.</p>

<p align="center">
  <img src="https://github.com/sturq/ccdcam/actions/workflows/build.yml/badge.svg" alt="build status">
</p>

---

CCDCam runs the live camera feed through a GLSL fragment shader that fakes the look of a
90s Sony Handycam / Hi8 / MiniDV: chunky low-res chroma, vertical highlight smear,
horizontal flare, scanlines, a warm grade, and a vignette. Photos and video are saved with
the filter baked into the pixels. It is a free alternative to the paid camcorder-filter apps.

## Examples

Forest, shot on a Pixel through CCDCam:

<p>
  <img src="docs/examples/forest_1.jpg" width="32%" alt="forest 1" />
  <img src="docs/examples/forest_2.jpg" width="32%" alt="forest 2" />
  <img src="docs/examples/forest_3.jpg" width="32%" alt="forest 3" />
</p>
<p>
  <img src="docs/examples/sample_4.jpg" width="32%" alt="sample 4" />
  <img src="docs/examples/sample_6.jpg" width="32%" alt="sample 6" />
  <img src="docs/examples/sample_7.jpg" width="32%" alt="sample 7" />
</p>
<p>
  <img src="docs/examples/sample_8.jpg" width="49%" alt="sample 8" />
  <img src="docs/examples/sample_9.jpg" width="49%" alt="sample 9" />
</p>

## Features

- **Live filtered preview** at full display refresh rate (60 to 120 fps)
- **Photo mode**: single JPEG snapshot of the filtered frame, saved to `Pictures/CCDCam/`
- **Video mode**: H.264 MP4 plus AAC audio, filter baked in via a MediaCodec dual-render pipeline, saved to `Movies/CCDCam/`
- **Pinch-to-zoom** clamped to the lens's min/max
- **Camcorder HUD overlay**: corner viewfinder brackets, `HH:MM:SS:FF` timecode, `STBY`/`REC` indicator with a blinking red dot, tape mode label, battery icon, live date stamp
- **Front/back camera flip**
- **Material You adaptive icon** with a monochrome layer that picks up the system wallpaper colour on Android 13+
- **Python sim** (`tools/sim.py`): a numpy reimplementation of the shader, so you can iterate the look on a JPEG or MP4 without rebuilding the APK

## What the shader does

`app/src/main/assets/shaders/ccd.frag`, in order:

1. UV quantization to a 540x480 cell grid, so GPU bilinear inside each cell plus cell-center sampling reproduces a downsample-then-nearest-upsample Hi8 chunkiness without an offscreen pass
2. Chroma shift on R/B sampling for NTSC colour bleed
3. Vertical CCD smear, where bright pixels bleed up and down their column
4. Horizontal flare around extreme highlights
5. Per-frame chroma noise on R and B
6. Luma grain
7. Black lift, warm grade, slight desaturation
8. Scanline modulation tied to the vertical line count
9. Radial vignette

All constants live at the top of the file, so tuning the look is a one-liner. The same
constants live in `tools/sim.py` so changes can be previewed in Python before rebuilding.

## Architecture

- **CameraX 1.4** drives the `Preview` use case into a custom `SurfaceTexture` (CameraX `VideoCapture` is intentionally not used, since it would bypass the shader)
- **`CcdRenderer`** is a `GLSurfaceView.Renderer` that draws the shader-filtered scene to the on-screen surface every frame, and to an optional second `EGLSurface` backed by `MediaCodec`'s input Surface when recording (throttled to a steady 30 fps for clean encoder timing)
- **`VideoRecorder`** wraps the H.264 encoder, the AAC encoder (`AudioRecord` into `MediaCodec` AAC), and a shared `MediaMuxer`. Both tracks use the same `startNs` reference so the muxed MP4 reports a correct duration
- **Photo capture** does a `glReadPixels` of the display framebuffer the frame after `requestFrameSnapshot`, Y-flips the bitmap, and writes a JPEG via `MediaStore`

## Build

```
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK, signed with the bundled debug keystore
```

Requires Android 7.0 (API 24) or newer. CI builds on every push, and release APKs are
produced on `v*` tags via `.github/workflows/release.yml`.

## Install

The latest pre-built APK is on the [Releases page](https://github.com/sturq/ccdcam/releases)
(signed with the bundled debug keystore, so installing requires allowing unknown sources).
Once published, F-Droid will be the recommended channel.

## Tweaking the look

Edit the constants at the top of `app/src/main/assets/shaders/ccd.frag`, and mirror the same
values in `tools/sim.py` (kept in sync by hand). Run the sim on a sample frame:

```
python tools/sim.py input.jpg output.jpg
python tools/sim.py input.mp4 output.mp4   # requires ffmpeg
python tools/test_sim.py                    # 8 invariants the shader has to satisfy
```

## License

[GPL-3.0-only](LICENSE). A fork or any project using this code has to be under GPL-3.0
(or a compatible license) with source available to users. The Hi8 look should stay free
for everyone.
