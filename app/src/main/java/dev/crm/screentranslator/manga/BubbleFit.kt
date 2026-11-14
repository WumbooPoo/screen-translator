package dev.crm.screentranslator.manga

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.crm.screentranslator.detection.TextRegion
import kotlin.math.roundToInt

/**
 * Bubble overlay fitting: opaque cover + text that fits inside the bubble.
 *
 * Live overlays use an OPAQUE cover (not neural inpainting): a filled
 * rounded rectangle sampled from the bubble's own fill colour, with the
 * translated text drawn on top. Inpainting (LaMa, ~198 MB) is a batch /
 * export-quality technique, not a live one — it is seconds per region and
 * far too heavy for a low-end phone. The cover is fitted to the bubble's
 * bounding box (mask-polygon fitting is a TODO once YOLO mask decoding
 * lands in [dev.crm.screentranslator.detection.YoloBubbleDetector]).
 */
object BubbleFit {

    /**
     * Samples the bubble's fill colour from the border pixels of the region
     * (inset 2px so the dark outline is excluded). Falls back to white when
     * the region is dark (dark balloons get a white cover for legibility).
     */
    fun sampleFillColor(bitmap: Bitmap, region: TextRegion): Int {
        val left = region.bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.bounds.right.toInt().coerceIn(0, bitmap.width - 1)
        val bottom = region.bounds.bottom.toInt().coerceIn(0, bitmap.height - 1)
        if (right - left < 4 || bottom - top < 4) return Color.WHITE
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var n = 0
        val inset = 2
        // Sample the top and bottom border rows.
        for (x in left + inset until right - inset step 3) {
            for (y in listOf(top + inset, bottom - inset)) {
                val p = bitmap.getPixel(x, y.coerceIn(0, bitmap.height - 1))
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
                n++
            }
        }
        if (n == 0) return Color.WHITE
        val r = (rSum / n).toInt()
        val g = (gSum / n).toInt()
        val b = (bSum / n).toInt()
        // Luminance check: dark fill -> white cover (readable text wins).
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luminance < 128) Color.WHITE else Color.rgb(r, g, b)
    }

    /**
     * Binary-searches the largest font size (in sp) at which [text] fits
     * inside [maxWidthPx] x [maxHeightPx] (already inset by padding).
     * Uses [StaticLayout] for accurate multi-line measurement.
     *
     * @param density screen density (for sp -> px conversion).
     * @param minSp smallest legible size; below it the text is allowed to
     *   overflow the bubble slightly (the overlay window grows instead).
     */
    fun fitFontSizeSp(
        text: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
        density: Float,
        minSp: Float = 10f,
        maxSp: Float = 30f,
    ): Float {
        if (maxWidthPx <= 0 || maxHeightPx <= 0) return minSp
        var lo = minSp
        var hi = maxSp
        var best = minSp
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 12) { // ~12 iterations: precision well under 0.1sp
            val mid = (lo + hi) / 2f
            paint.textSize = mid * density
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
            if (layout.height <= maxHeightPx) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
            if (hi - lo < 0.1f) break
        }
        return best
    }

    /** Applies the user's opacity setting (10..100) to an ARGB fill colour. */
    fun withOpacity(fillColor: Int, opacityPercent: Int): Int {
        val alpha = (opacityPercent.coerceIn(10, 100) * 255 / 100).coerceIn(0, 255)
        return Color.argb(
            alpha,
            Color.red(fillColor),
            Color.green(fillColor),
            Color.blue(fillColor),
        )
    }

    /** Text colour for a given fill: black on light fills, white on dark. */
    fun textColorFor(fillColor: Int): Int {
        val luminance = 0.299 * Color.red(fillColor) +
            0.587 * Color.green(fillColor) +
            0.114 * Color.blue(fillColor)
        return if (luminance < 128) Color.WHITE else Color.BLACK
    }
}
