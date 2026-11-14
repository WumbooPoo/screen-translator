package dev.crm.screentranslator.detection

import android.graphics.Bitmap
import android.graphics.RectF
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-geometry fallback detector: finds enclosed bright regions (white
 * speech balloons and light narration boxes) by thresholding + connected
 * components. Zero dependencies, zero downloads, runs in milliseconds on a
 * downscaled frame.
 *
 * Accuracy is approximate — it cannot tell a balloon from a white UI card,
 * and it misses dark / coloured balloons. It exists so manga mode still
 * does *something* when neither the YOLO model nor ML Kit returns regions.
 * Never the first choice.
 */
class BrightRegionDetector : TextRegionDetector {

    override val name: String = "Bright-region geometry"

    override fun isAvailable(): Boolean = true

    override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
        // Work on a small copy: 360px wide is plenty for region geometry.
        val scale = 360f / bitmap.width.coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(
            bitmap,
            360,
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small != bitmap) small.recycle()

        // Binary mask: bright pixel = candidate balloon interior.
        val bright = BooleanArray(w * h) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            r > 225 && g > 225 && b > 225
        }

        // Connected-component labelling (4-connectivity, iterative BFS).
        val label = IntArray(w * h) { -1 }
        val regions = mutableListOf<TextRegion>()
        var nextId = 0
        val queue = ArrayDeque<Int>()
        for (start in bright.indices) {
            if (!bright[start] || label[start] != -1) continue
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var count = 0
            label[start] = nextId
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val x = cur % w
                val y = cur / w
                count++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                // 4 neighbours
                if (x > 0) tryAdd(cur - 1, w, h, bright, label, nextId, queue)
                if (x < w - 1) tryAdd(cur + 1, w, h, bright, label, nextId, queue)
                if (y > 0) tryAdd(cur - w, w, h, bright, label, nextId, queue)
                if (y < h - 1) tryAdd(cur + w, w, h, bright, label, nextId, queue)
            }
            nextId++
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val area = boxW * boxH
            val fillRatio = if (area > 0) count.toFloat() / area else 0f
            val areaFraction = count.toFloat() / (w * h)
            // Keep plausible balloons: big enough to hold text, not the whole
            // screen, mostly filled (a balloon is a solid bright blob).
            if (areaFraction in 0.005f..0.4f && fillRatio > 0.55f &&
                boxW > 24 && boxH > 24
            ) {
                val sx = bitmap.width.toFloat() / w
                val sy = bitmap.height.toFloat() / h
                regions.add(
                    TextRegion(
                        id = "geom-${regions.size}",
                        bounds = RectF(
                            minX * sx, minY * sy,
                            (maxX + 1) * sx, (maxY + 1) * sy,
                        ),
                        kind = RegionKind.BUBBLE,
                        confidence = 0.5f,
                    ),
                )
            }
        }
        return regions
    }

    private fun tryAdd(
        idx: Int,
        w: Int,
        h: Int,
        bright: BooleanArray,
        label: IntArray,
        id: Int,
        queue: ArrayDeque<Int>,
    ) {
        if (idx in bright.indices && bright[idx] && label[idx] == -1) {
            label[idx] = id
            queue.addLast(idx)
        }
    }
}
