package dev.crm.screentranslator.detection

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLO11n instance-segmentation bubble detector, run via ONNX Runtime.
 *
 * Model: huyvux3005/manga109-segmentation-bubble (Hugging Face).
 * Input is FIXED 1600x1600 (the exported ONNX graph's input size).
 * The model file is NEVER bundled in the APK — it is downloaded on demand
 * by [dev.crm.screentranslator.models.ModelDownloadManager] into the app's
 * files dir. See [dev.crm.screentranslator.models.ModelRegistry] for the
 * licence situation (read it before shipping: the repo's own licence
 * metadata and its README text disagree).
 *
 * V1 scope: bounding boxes only. The model also outputs mask prototypes
 * (32 per detection), but mask decoding (coefficients x prototypes ->
 * sigmoid -> threshold -> resize) is a TODO — boxes are enough for the
 * opaque-cover overlay, which fits the cover to the box.
 *
 * Execution providers: NNAPI first, CPU fallback. If NNAPI session creation
 * fails (common on some OEMs), the session is rebuilt CPU-only.
 * Inference runs on 2 threads (see [OrtSession.SessionOptions] below) on
 * the pipeline's background executor — never the main thread.
 *
 * IMPORTANT — output layout assumption (verify on first run, see
 * BUILD_NOTES.md): Ultralytics YOLO11-seg ONNX exports output0 as
 * [1, 4 + nc + 32, 8400] (channels-first) or [1, 8400, 4 + nc + 32]
 * (rows-first). Both layouts are handled. nc = number of classes is
 * derived from the channel count, never hard-coded.
 */
class YoloBubbleDetector(
    private val modelFile: File,
) : TextRegionDetector {

    override val name: String = "YOLO11n bubble"

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null

    override fun isAvailable(): Boolean = modelFile.exists() && modelFile.length() > 0

    @Synchronized
    private fun ensureSession(): Boolean {
        if (session != null) return true
        if (!isAvailable()) return false
        return try {
            val environment = OrtEnvironment.getEnvironment()
            env = environment
            session = createSession(environment, useNnapi = true)
            inputName = session?.inputNames?.firstOrNull()
            // Prefer the detection output; fall back to the first output.
            outputName = session?.outputNames?.firstOrNull { it.contains("output0") }
                ?: session?.outputNames?.firstOrNull()
            session != null && inputName != null && outputName != null
        } catch (e: OrtException) {
            Log.w(TAG, "ONNX session creation failed", e)
            false
        }
    }

    private fun createSession(environment: OrtEnvironment, useNnapi: Boolean): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            if (useNnapi) {
                // May throw on devices without a working NNAPI driver.
                addNnapi()
            }
            addCPU(true)
        }
        return try {
            environment.createSession(modelFile.absolutePath, options)
        } catch (e: OrtException) {
            if (useNnapi) {
                // NNAPI failed: rebuild CPU-only.
                Log.w(TAG, "NNAPI unavailable, falling back to CPU", e)
                options.close()
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    addCPU(true)
                }
                environment.createSession(modelFile.absolutePath, cpuOptions)
            } else {
                throw e
            }
        }
    }

    override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
        if (!ensureSession()) return emptyList()
        val activeSession = session ?: return emptyList()
        val inName = inputName ?: return emptyList()
        val outName = outputName ?: return emptyList()

        // Letterbox the frame to the model's fixed 1600x1600 input.
        val scale = min(INPUT_SIZE / bitmap.width.toFloat(), INPUT_SIZE / bitmap.height.toFloat())
        val newW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - newW) / 2f
        val padY = (INPUT_SIZE - newH) / 2f
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        // CHW float tensor, values 0..1. (3 * 1600 * 1600 floats ~ 30 MB transient.)
        val buffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(newW * newH)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        if (resized != bitmap) resized.recycle()
        val plane = INPUT_SIZE * INPUT_SIZE
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val srcX = x - padX.roundToInt()
                val srcY = y - padY.roundToInt()
                val idx = y * INPUT_SIZE + x
                if (srcX in 0 until newW && srcY in 0 until newH) {
                    val p = pixels[srcY * newW + srcX]
                    buffer.put(idx, ((p shr 16) and 0xFF) / 255f)
                    buffer.put(plane + idx, ((p shr 8) and 0xFF) / 255f)
                    buffer.put(2 * plane + idx, (p and 0xFF) / 255f)
                } else {
                    // Letterbox padding: Ultralytics default grey (114).
                    buffer.put(idx, 114f / 255f)
                    buffer.put(plane + idx, 114f / 255f)
                    buffer.put(2 * plane + idx, 114f / 255f)
                }
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            env ?: return emptyList(),
            buffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        val result = activeSession.run(mapOf(inName to inputTensor))
        return try {
            val output = result.get(outName) ?: return emptyList()
            parseDetections(output, bitmap.width, bitmap.height, scale, padX, padY)
        } finally {
            inputTensor.close()
            result.close()
        }
    }

    /**
     * Parses Ultralytics YOLO11-seg output0 into boxes.
     * Layout A (channels-first): [1, 4+nc+32, N] — value[0][channel][n].
     * Layout B (rows-first):     [1, N, 4+nc+32] — value[0][n][channel].
     */
    private fun parseDetections(
        output: OnnxValue,
        origW: Int,
        origH: Int,
        scale: Float,
        padX: Float,
        padY: Float,
    ): List<TextRegion> {
        val info = output.info as? TensorInfo ?: return emptyList()
        val shape = info.shape
        if (shape.size != 3) return emptyList()
        val value = output.value as? Array<Array<FloatArray>> ?: return emptyList()
        val channelsFirst = shape[1] < shape[2]
        val channels = if (channelsFirst) shape[1].toInt() else shape[2].toInt()
        val count = if (channelsFirst) shape[2].toInt() else shape[1].toInt()
        // channels = 4 (box) + nc (classes) + 32 (mask coefficients)
        if (channels < 4 + 1 + 32) return emptyList()
        val numClasses = channels - 4 - 32

        fun cell(n: Int, c: Int): Float =
            if (channelsFirst) value[0][c][n] else value[0][n][c]

        val boxes = ArrayList<Box>(64)        for (n in 0 until count) {
            var bestScore = 0f
            for (c in 0 until numClasses) {
                bestScore = max(bestScore, cell(n, 4 + c))
            }
            if (bestScore < SCORE_THRESHOLD) continue
            val cx = cell(n, 0)
            val cy = cell(n, 1)
            val w = cell(n, 2)
            val h = cell(n, 3)
            // Back to original bitmap coordinates (undo letterbox).
            val x1 = ((cx - w / 2f) - padX) / scale
            val y1 = ((cy - h / 2f) - padY) / scale
            val x2 = ((cx + w / 2f) - padX) / scale
            val y2 = ((cy + h / 2f) - padY) / scale
            if (x2 <= x1 || y2 <= y1) continue
            boxes.add(
                Box(
                    x1.coerceIn(0f, origW.toFloat()),
                    y1.coerceIn(0f, origH.toFloat()),
                    x2.coerceIn(0f, origW.toFloat()),
                    y2.coerceIn(0f, origH.toFloat()),
                    bestScore,
                ),
            )
        }
        // NMS (IoU 0.45), highest score first, max 100 boxes.
        boxes.sortByDescending { it.score }
        val kept = ArrayList<Box>()
        for (box in boxes) {
            if (kept.size >= MAX_BOXES) break
            if (kept.none { iou(it, box) > NMS_IOU }) kept.add(box)
        }
        return kept.mapIndexed { index, box ->
            TextRegion(
                id = "yolo-$index",
                bounds = RectF(box.x1, box.y1, box.x2, box.y2),
                kind = RegionKind.BUBBLE,
                confidence = box.score,
            )
        }
    }

    private fun iou(a: Box, b: Box): Float {
        val interW = min(a.x2, b.x2) - max(a.x1, b.x1)
        val interH = min(a.y2, b.y2) - max(a.y1, b.y1)
        if (interW <= 0f || interH <= 0f) return 0f
        val inter = interW * interH
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union > 0f) inter / union else 0f
    }

    /** One YOLO detection box in original bitmap coordinates. */
    private data class Box(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
    )

    fun close() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    companion object {
        private const val TAG = "YoloBubbleDetector"
        private const val INPUT_SIZE = 1600
        private const val SCORE_THRESHOLD = 0.25f
        private const val NMS_IOU = 0.45f
        private const val MAX_BOXES = 100
    }
}
