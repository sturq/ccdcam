# CCDCam

Open-source camcorder filter for Android. Live camera preview runs through a GLSL fragment shader that fakes the look of a Sony Handycam / Hi8 / MiniDV: vertical highlight smear from bright lights, chroma noise, scanlines, lifted blacks, warm color cast.

Built as a free alternative to subscription apps like Dazz Cam, 1888 and RarVid.

## Why

Dazz Cam costs about 5 EUR a month, 1888 is iOS only, and every other Android option in the Play Store is a different shade of paywall plus ads. The actual "old camcorder" look is mostly one well-tuned fragment shader. So here it is, open.

## What's in the shader

`app/src/main/assets/shaders/ccd.frag` does, in order:

1. Resolution downsample to ~480 lines (camcorder grid)
2. Vertical CCD smear: each pixel scans up and down its column for bright spots and adds a streak. This is the hardware artifact you get on real CCD sensors when light overloads the readout register.
3. Per-frame chroma noise on the R and B channels
4. Luma grain
5. Lifted blacks, warm color grade, slight desaturation
6. Horizontal scanline modulation
7. Vignette

Every section has its constants at the top, so tweaking the look is a one-liner.

## Status

`v0.1` is what's in main right now. Live preview with the filter, front/back camera flip, no recording yet.

`v0.2` adds recording. A second EGL surface feeds the same shader output into MediaCodec, muxed with AAC audio into MP4 via MediaMuxer, saved to the gallery through MediaStore.

`v0.3` is the iOS port. Same shader logic translated to SkSL or Metal, wrapped in a SwiftUI camera view.

## Building

GitHub Actions builds a debug APK on every push. Grab it from the Actions tab, "build-apk" workflow, "ccdcam-debug" artifact.

Locally on any machine with the Android SDK installed:

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on the phone

From a terminal with `adb` and USB debugging enabled:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just transfer the APK to the phone and tap it.

## Tweaking the look

Open `app/src/main/assets/shaders/ccd.frag` and change the numbers. Some useful starting points:

- Less smear: lower `0.85` in the `smoothstep(0.85, 1.0, lu)` line, or drop the `* 3.5` multiplier
- More tape grain: raise the `0.06` in the luma grain section
- More scanlines: raise the `0.04` in `0.96 + 0.04 * sin(...)`
- Warmer / cooler: edit the `vec3(1.08, 1.02, 0.93)` color grade
- Different resolution feel: change `float lines = 480.0` (240 = Video8, 576 = PAL, 720 = early HDV)

## License

MIT.
