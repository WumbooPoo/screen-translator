package dev.crm.screentranslator.detection

import android.graphics.Bitmap

/**
 * Detects text regions (bubbles, narration boxes, free text) on a frame.
 *
 * Implementations, in preference order for manga mode:
 * 1. [YoloBubbleDetector] — YOLO11n bubble segmentation (ONNX Runtime).
 *    Best accuracy, needs the model downloaded (runtime-download only).
 * 2. [MlKitRegionProvider] — regions derived from ML Kit OCR blocks.
 *    Always available, zero download, but no bubble shapes.
 * 3. [BrightRegionDetector] — pure-geometry fallback for enclosed white
 *    balloons. Zero dependencies, works offline, approximate.
 *
 * The pipeline tries them in this order and uses the first one that returns
 * at least one region.
 */
interface TextRegionDetector {
    /** Human-readable name shown in logs / debug UI. */
    val name: String

    /** True when this detector can run right now (model present, etc.). */
    fun isAvailable(): Boolean

    /** Detect regions on [bitmap]. Coordinates are in bitmap pixels. */
    suspend fun detect(bitmap: Bitmap): List<TextRegion>
}
