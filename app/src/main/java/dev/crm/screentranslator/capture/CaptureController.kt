package dev.crm.screentranslator.capture

import android.graphics.Bitmap
import android.media.ImageReader
import android.util.Log
import dev.crm.screentranslator.pipeline.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-frame capture with "screen settle" detection.
 *
 * The app NEVER captures a video stream and NEVER runs OCR per frame.
 * On a translate request it grabs one frame, waits briefly, grabs again,
 * and only proceeds when two consecutive frames are (near-)identical —
 * i.e. the screen has stopped moving (page finished loading, scroll ended).
 * Up to 3 attempts; if the screen never settles (video, animation), the
 * latest frame is used anyway rather than spinning forever.
 */
class CaptureController(
    private val imageReader: ImageReader,
    private val pipeline: TranslationPipeline,
    private val scope: CoroutineScope,
) {
    /** Captures a settled frame and feeds it to the pipeline. */
    fun captureSettledAndTranslate() {
        scope.launch {
            val frame = captureSettledFrame()
            if (frame != null) {
                pipeline.processFrame(frame)
            } else {
                Log.w(TAG, "No frame captured (ImageReader returned null)")
            }
        }
    }

    private suspend fun captureSettledFrame(): Bitmap? {
        var previousHash = 0L
        var latest: Bitmap? = null
        repeat(MAX_ATTEMPTS) {
            val frame = captureSingleFrame() ?: return@repeat
            latest?.recycle()
            latest = frame
            val hash = quickHash(frame)
            if (hash == previousHash) return latest // settled
            previousHash = hash
            delay(SETTLE_DELAY_MS)
        }
        return latest
    }

    /** Grabs the latest frame from the ImageReader as a Bitmap, or null. */
    fun captureSingleFrame(): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (pixelStride == 4 && rowStride == width * 4) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Row stride has padding: copy row by row.
                val rowBytes = width * 4
                val tmp = ByteArray(rowBytes)
                val full = ByteArray(rowStride * height)
                buffer.get(full)
                val out = java.nio.ByteBuffer.allocate(rowBytes * height)
                for (y in 0 until height) {
                    full.copyInto(tmp, 0, y * rowStride, y * rowStride + rowBytes)
                    out.put(tmp)
                }
                out.rewind()
                bitmap.copyPixelsFromBuffer(out)
            }
            bitmap
        } finally {
            image.close()
        }
    }

    /** Same cheap 160x90 FNV hash idea as the pipeline's change detection. */
    private fun quickHash(frame: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(frame, 160, 90, true)
        val pixels = IntArray(160 * 90)
        small.getPixels(pixels, 0, 160, 0, 0, 160, 90)
        if (small != frame) small.recycle()
        var hash = -3750763034362895579L // FNV-1a offset basis (0xcbf29ce484222325 as signed Long)
        for (p in pixels) {
            val luma = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 +
                (p and 0xFF)) / 10
            hash = hash xor (luma.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }

    companion object {
        private const val TAG = "CaptureController"
        private const val MAX_ATTEMPTS = 3
        private const val SETTLE_DELAY_MS = 400L
    }
}
