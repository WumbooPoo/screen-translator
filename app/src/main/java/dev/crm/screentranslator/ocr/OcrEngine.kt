package dev.crm.screentranslator.ocr

import android.graphics.Bitmap
import dev.crm.screentranslator.detection.TextRegion

/** Result of OCR-ing one text region. */
data class OcrResult(
    val regionId: String,
    val text: String,
    /** True when the source text was vertical (tategaki) — manga reading order. */
    val isVertical: Boolean,
    val confidence: Float,
)

/**
 * OCR engine. [recognize] returns null when the engine cannot produce text
 * for the region (empty result, model missing, or an unimplemented path) —
 * the pipeline then falls back to the next engine or skips the region.
 */
interface OcrEngine {
    val name: String

    /** True when this engine can run right now (model present, etc.). */
    fun isAvailable(): Boolean

    /**
     * OCR the [region] of [bitmap].
     * @param preferJapanese true in manga mode (Japanese-first recogniser).
     */
    suspend fun recognize(
        bitmap: Bitmap,
        region: TextRegion,
        preferJapanese: Boolean,
    ): OcrResult?
}
