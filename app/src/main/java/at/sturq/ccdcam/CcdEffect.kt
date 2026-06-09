package at.sturq.ccdcam

import androidx.camera.core.CameraEffect

/**
 * Glue: binds our SurfaceProcessor to Preview + VideoCapture UseCases through CameraX's
 * CameraEffect API. IMAGE_CAPTURE is intentionally left out — in 1.4.x only a separate
 * ImageProcessor can target it, and we get correctly-rotated, shader-filtered photos by
 * snapshotting PreviewView.bitmap instead, which is much simpler.
 */
class CcdEffect(processor: CcdSurfaceProcessor) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    processor.executor,
    processor,
    { /* error listener: nothing actionable */ },
)
