package dev.crm.screentranslator.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dev.crm.screentranslator.detection.TextRegion
import kotlinx.coroutines.tasks.await
import kotlin.math.max

/**
 * ML Kit on-device OCR — the WORKING default engine in V1.
 *
 * Uses the UNBUNDLED Play-services recognisers (zero APK weight):
 * Latin (bundled by default) + Japanese (add-on dependency).
 * Models download via Play services on first use.
 *
 * Manga handling:
 * - Tall regions (height > 2x width) are likely vertical Japanese
 *   (tategaki). The crop is rotated 90° clockwise and OCR-ed as horizontal
 *   text; the rotated lines read top-to-bottom = original columns
 *   right-to-left, which is exactly manga reading order.
 * - Furigana filter: within a line, symbols shorter than 50% of the
 *   line's median symbol height are dropped (furigana are tiny kana
 *   printed beside kanji and otherwise garble the line).
 * - Crops are upscaled so CJK characters are >= 24px tall (ML Kit's
 *   documented minimum; the pipeline targets 32-48px).
 *
 * Known limits (honest): ML Kit's docs never claim vertical-text support —
 * the rotation trick is a workaround, not a guarantee. Stylised /
 * hand-lettered fonts and SFX are weak spots. Hayai OCR
 * ([HayaiLiteRtOcr]) is the manga-trained upgrade path once its decode
 * loop is implemented.
 */
class MlKitOcr : OcrEngine {

    override val name: String = "ML Kit OCR"

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override fun isAvailable(): Boolean = true

    override suspend fun recognize(
        bitmap: Bitmap,
        region: TextRegion,
        preferJapanese: Boolean,
    ): OcrResult? {
        val crop = cropRegion(bitmap, region) ?: return null
        val isTall = crop.height > crop.width * 2
        // Rotate tall crops so vertical text becomes horizontal for the OCR.
        val (workBitmap, wasRotated) = if (isTall) {
            rotate90Cw(crop) to true
        } else {
            crop to false
        }
        // Upscale small crops so CJK glyphs clear ML Kit's 24px minimum.
        val scaled = upscaleIfSmall(workBitmap)
        return try {
            val primary = if (preferJapanese) japaneseRecognizer else latinRecognizer
            val fallback = if (preferJapanese) latinRecognizer else japaneseRecognizer
            val text = recognizeWith(primary, scaled, preferJapanese)
                ?: recognizeWith(fallback, scaled, !preferJapanese)
            if (text.isNullOrBlank()) {
                null
            } else {
                OcrResult(
                    regionId = region.id,
                    text = text,
                    isVertical = wasRotated,
                    confidence = 1f,
                )
            }
        } finally {
            if (scaled != workBitmap) scaled.recycle()
            if (workBitmap != crop) workBitmap.recycle()
            if (crop != bitmap) crop.recycle()
        }
    }

    /**
     * Runs one recogniser and returns cleaned text, or null when empty.
     * For Japanese: symbol-level furigana filtering + no-space joining.
     * For Latin: element-level joining with spaces.
     */
    private suspend fun recognizeWith(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
        japanese: Boolean,
    ): String? {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        if (result.text.isBlank()) return null
        return if (japanese) {
            buildJapaneseText(result)
        } else {
            buildLatinText(result)
        }.takeIf { it.isNotBlank() }
    }

    private fun buildJapaneseText(result: Text): String {
        val lines = StringBuilder()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                // Furigana filter: drop symbols < 50% of the line's median
                // symbol height before joining.
                val symbols = line.elements.flatMap { it.symbols }
                val heights = symbols.mapNotNull { it.boundingBox?.height()?.toFloat() }
                val dominant = heights.median()
                val kept = StringBuilder()
                for (element in line.elements) {
                    for (symbol in element.symbols) {
                        val h = symbol.boundingBox?.height()?.toFloat() ?: continue
                        if (dominant > 0f && h < dominant * FURIGANA_RATIO) continue
                        kept.append(symbol.text)
                    }
                }
                if (kept.isNotBlank()) {
                    if (lines.isNotEmpty()) lines.append('\n')
                    lines.append(kept)
                }
            }
        }
        return lines.toString().trim()
    }

    private fun buildLatinText(result: Text): String {
        return result.textBlocks
            .flatMap { it.lines }
            .joinToString("\n") { line ->
                line.elements.joinToString(" ") { it.text }
            }.trim()
    }

    /** Crops the region out of the frame with a small padding margin. */
    private fun cropRegion(bitmap: Bitmap, region: TextRegion): Bitmap? {
        val pad = 4
        val left = max(0, region.bounds.left.toInt() - pad)
        val top = max(0, region.bounds.top.toInt() - pad)
        val right = minOf(bitmap.width, region.bounds.right.toInt() + pad)
        val bottom = minOf(bitmap.height, region.bounds.bottom.toInt() + pad)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun rotate90Cw(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun upscaleIfSmall(src: Bitmap): Bitmap {
        val minSide = minOf(src.width, src.height)
        if (minSide >= MIN_CROP_SIDE) return src
        val factor = 2
        return Bitmap.createScaledBitmap(
            src, src.width * factor, src.height * factor, true,
        )
    }

    companion object {
        /** Symbols shorter than this fraction of the dominant height are furigana. */
        private const val FURIGANA_RATIO = 0.5f

        /** Crops smaller than this on their short side are upscaled 2x. */
        private const val MIN_CROP_SIDE = 128
    }
}

/** Median of a list of floats (0f when empty). */
private fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = size / 2
    return if (size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}
