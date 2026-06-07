# CCDCam

Live camcorder filter for Android. Camera preview is rendered through a GLSL fragment shader that fakes the look of a Sony Handycam / Hi8 / MiniDV: vertical highlight smear, chroma noise, scanlines, lifted blacks, warm color cast.

## Why

The 90s-camcorder look has become its own visual genre on Instagram and TikTok skate clips. Getting it on a phone today usually means either hunting a used Sony VX1000 on eBay or signing up for a subscription camera app. Neither is necessary. The whole effect is a fragment shader you can read and tune.

## What the shader does

`app/src/main/assets/shaders/ccd.frag`, in order:

1. Resolution downsample to ~480 lines
2. Vertical CCD smear. Each pixel scans its column for bright spots and adds a streak. This is the hardware artifact you get on real CCD sensors when light overloads the readout register.
3. Horizontal flare around highlights
4. Per-frame chroma noise on R and B
5. Luma grain
6. Lifted blacks, warm grade, slight desaturation
7. Horizontal scanline modulation
8. Vignette

Each section has its constants at the top, so dialing the look is a one-liner.

## Status

`v0.1.1` is live in `main`: filtered preview, front/back flip, video recording to `Movies/CCDCam/`. Recording is currently RAW (the camera stream goes to MP4 unfiltered) because baking the GLSL shader into the encoded video needs a second EGL surface fed into MediaCodec, which is the v0.2 milestone. So right now the preview shows the look, the saved MP4 does not.

`v0.2` is filtered recording. Same shader output piped into MediaCodec input surface and muxed with AAC audio so the saved MP4 has the look baked in.

`v0.3` is the iOS port. Same shader translated to SkSL or Metal, wrapped in a SwiftUI camera view.

## Install

Latest pre-built APK is on the [Releases page](https://github.com/sturq/ccdcam/releases). Download, tap, install (you'll need to allow installs from unknown sources for your browser or file manager).

If you'd rather build it yourself, push to your fork and grab the artifact from the `build-apk` workflow, or run locally:

```
./gradlew assembleDebug
```

## Tweaking the look

Open `app/src/main/assets/shaders/ccd.frag` and edit the constants. Useful knobs:

- Smear strength: the `0.85` threshold and the `* 3.5` multiplier in the vertical smear loop
- Tape grain amount: the `0.06` in the luma grain section
- Scanline contrast: the `0.04` in `0.96 + 0.04 * sin(...)`
- Color grade: the `vec3(1.08, 1.02, 0.93)` warm tint
- Resolution feel: `float lines = 480.0` (240 for Video8, 576 for PAL, 720 for early HDV)

A reference Python implementation of the same algorithm lives in `tools/sim.py`. Run it on a JPEG to preview shader tweaks without rebuilding the app.

## License

MIT.
