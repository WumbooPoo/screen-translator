package dev.crm.screentranslator.detection

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Fallback region provider: runs ML Kit Latin OCR on the frame and turns
 * every recognised text block into a [TextRegion].
 *
 * Always available (unbundled Play-services recogniser, zero APK weight).
 * Downside: it returns text *blocks*, not bubble shapes, and it costs a full
 * OCR pass just to get geometry. The pipeline therefore prefers
 * [YoloBubbleDetector] when its model is downloaded, and only uses this
 * provider when no bubble model is present.
 *
 * NOTE (optimisation for later): when this provider is used, the pipeline
 * re-runs ML Kit per region in [dev.crm.screentranslator.ocr.MlKitOcr].
 * The recognised [com.google.mlkit.vision.text.Text] object could be shared
 * between the two steps instead of OCR-ing twice.
 */
class MlKitRegionProvider : TextRegionDetector {

    override val name: String = "ML Kit blocks"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun isAvailable(): Boolean = true

    override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapIndexedNotNull { index, block ->
            val box = block.boundingBox ?: return@mapIndexedNotNull null
            TextRegion(
                id = "mlkit-$index",
                bounds = RectF(box),
                // ML Kit gives no bubble info; the manga pipeline treats
                // blocks as free text unless they look like a bubble.
                kind = if (looksLikeBubble(box.width(), box.height())) {
                    RegionKind.BUBBLE
                } else {
                    RegionKind.FREE_TEXT
                },
                confidence = 1f,
            )
        }
    }

    /**
     * Very rough heuristic: manga bubbles are taller than they are wide
     * (vertical text) or roughly square-ish. Wide flat strips are usually
     * UI text / captions, not bubbles.
     */
    private fun looksLikeBubble(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = height.toFloat() / width.toFloat()
        return ratio in 0.6f..3.5f
    }
}
