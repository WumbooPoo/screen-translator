package dev.crm.screentranslator.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import dev.crm.screentranslator.data.Engine
import dev.crm.screentranslator.data.SettingsStore
import dev.crm.screentranslator.detection.BrightRegionDetector
import dev.crm.screentranslator.detection.MlKitRegionProvider
import dev.crm.screentranslator.detection.TextRegion
import dev.crm.screentranslator.detection.TextRegionDetector
import dev.crm.screentranslator.detection.YoloBubbleDetector
import dev.crm.screentranslator.manga.BubbleFit
import dev.crm.screentranslator.manga.ReadingOrder
import dev.crm.screentranslator.manga.SfxFilter
import dev.crm.screentranslator.models.ModelDownloadManager
import dev.crm.screentranslator.models.ModelRegistry
import dev.crm.screentranslator.ocr.HayaiLiteRtOcr
import dev.crm.screentranslator.ocr.MlKitOcr
import dev.crm.screentranslator.ocr.OcrEngine
import dev.crm.screentranslator.translate.GoogleCloudTranslator
import dev.crm.screentranslator.translate.LibreTranslateEngine
import dev.crm.screentranslator.translate.MlKitTranslator
import dev.crm.screentranslator.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** One translated bubble, ready to render as an overlay. */
data class TranslatedBubble(
    val region: TextRegion,
    val original: String,
    val translated: String,
    /** Fill colour sampled from the bubble (before opacity is applied). */
    val fillColor: Int,
    val isVertical: Boolean,
)

enum class PipelineStatus { IDLE, WORKING, ERROR }

/**
 * The event-driven translation pipeline. NEVER runs OCR on every frame.
 *
 * Flow: tap / screen-settle -> ONE still frame -> detect regions ->
 * OCR crops -> reading order -> SFX filter -> translate -> overlay.
 *
 * Lightweight rules (tuned for low-end phones with ~4 GB RAM):
 * - 300 ms throttle between runs; overlapping requests are dropped.
 * - Frame-hash change detection: identical frames are skipped entirely.
 * - Detection runs on a frame downscaled to <= 1024px longest side;
 *   OCR crops are taken at native resolution and upscaled so characters
 *   are ~32-48px tall.
 * - Inference runs on a dedicated 2-thread executor, never the main thread.
 * - The last good overlay is KEPT until a new result is ready (anti-flicker:
 *   the overlay is never cleared mid-inference).
 * - XNNPack CPU is the default delegate; GPU/NNAPI are probed at runtime
 *   inside the engines and always fall back to CPU.
 */
class TranslationPipeline(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val downloadManager = ModelDownloadManager(appContext, settings.wifiOnlyDownloads)

    /** 2-thread executor: one lane for detection, one for OCR/translate. */
    private val executor = Executors.newFixedThreadPool(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _bubbles = MutableStateFlow<List<TranslatedBubble>>(emptyList())
    /** The current overlay. The UI subscribes; empty = no overlay shown. */
    val bubbles: StateFlow<List<TranslatedBubble>> = _bubbles.asStateFlow()

    private val _status = MutableStateFlow(PipelineStatus.IDLE)
    val status: StateFlow<PipelineStatus> = _status.asStateFlow()

    private var lastRunMs = 0L
    private var lastFrameHash = 0L

    // Engines (lazy: heavy ones only initialise when their model exists).
    private val mlKitOcr = MlKitOcr()
    private val hayaiOcr by lazy {
        HayaiLiteRtOcr(downloadManager.modelDir(ModelRegistry.HAYAI_LITERT.id))
    }
    private val mlKitTranslator by lazy { MlKitTranslator(settings.wifiOnlyDownloads) }
    private val libreTranslator by lazy {
        LibreTranslateEngine(settings.libreTranslateUrl)
    }
    private val googleTranslator = GoogleCloudTranslator()

    private val yoloDetector by lazy {
        val file = downloadManager.localFile(ModelRegistry.YOLO_BUBBLE)
        if (file != null) YoloBubbleDetector(file) else null
    }
    private val mlKitRegions = MlKitRegionProvider()
    private val brightRegions = BrightRegionDetector()

    /**
     * Entry point: translate ONE still frame. Safe to call from any thread;
     * throttling and change detection happen synchronously, the heavy work
     * is dispatched to the background executor.
     */
    fun processFrame(frame: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastRunMs < THROTTLE_MS) return // too soon: drop
        val hash = frameHash(frame)
        if (hash == lastFrameHash) return // unchanged frame: skip entirely
        lastRunMs = now
        lastFrameHash = hash
        _status.value = PipelineStatus.WORKING
        scope.launch(executor.asCoroutineDispatcher()) {
            try {
                val result = runPipeline(frame)
                // Only replace the overlay on success — anti-flicker.
                _bubbles.value = result
                _status.value = PipelineStatus.IDLE
            } catch (e: Exception) {
                Log.w(TAG, "Pipeline failed, keeping last overlay", e)
                _status.value = PipelineStatus.ERROR
            }
        }
    }

    /** Clears the overlay (user pressed stop / switched app). */
    fun clearOverlay() {
        _bubbles.value = emptyList()
        lastFrameHash = 0L
    }

    fun close() {
        executor.shutdown()
        mlKitTranslator.close()
        hayaiOcr.close()
        yoloDetector?.close()
    }

    private suspend fun runPipeline(frame: Bitmap): List<TranslatedBubble> {
        // 1. Detect regions on a downscaled frame (<= 1024px longest side).
        val detectionFrame = downscaleForDetection(frame)
        val regions = detectRegions(detectionFrame, frame)
        if (regions.isEmpty()) return emptyList()

        // 2. OCR each region (crops at native resolution).
        val ocrEngine = pickOcrEngine()
        val ocrResults = regions.mapNotNull { region ->
            ocrEngine.recognize(frame, region, settings.mangaMode)
                ?.takeIf { it.text.isNotBlank() }
                ?.let { region to it }
        }
        if (ocrResults.isEmpty()) return emptyList()

        // 3. Manga reading order (right-to-left, top-to-bottom) when enabled.
        val ordered = if (settings.mangaMode) {
            val sortedRegions = ReadingOrder.sort(ocrResults.map { it.first })
            val byId = ocrResults.associateBy { it.first.id }
            sortedRegions.mapNotNull { byId[it.id] }
        } else {
            ocrResults
        }

        // 4. Join sentences split across bubbles, then drop SFX if disabled.
        val texts = ReadingOrder.joinSplitSentences(ordered.map { it.second.text })
        val items = ordered.mapIndexedNotNull { index, (region, ocr) ->
            val text = texts.getOrNull(index).orEmpty()
            if (text.isBlank()) null else Triple(region, text, ocr.isVertical)
        }
        val filtered = SfxFilter.filter(
            items.map { (region, text, _) -> region to text },
            settings.sfxEnabled,
        )
        if (filtered.isEmpty()) return emptyList()

        // 5. Translate (engine from Settings; falls back offline -> online).
        val translator = pickTranslator()
        val verticalById = items.associate { it.first.id to it.third }
        val bubbles = filtered.mapNotNull { (region, text) ->
            val translated = translator.translate(text, settings.sourceLanguage, settings.targetLanguage)
                ?: fallbackTranslate(text)
                ?: return@mapNotNull null
            TranslatedBubble(
                region = region,
                original = text,
                translated = translated,
                fillColor = BubbleFit.sampleFillColor(frame, region),
                isVertical = verticalById[region.id] == true,
            )
        }
        return bubbles
    }

    /** Detector chain: YOLO (if downloaded) -> ML Kit blocks -> geometry. */
    private suspend fun detectRegions(
        detectionFrame: Bitmap,
        fullFrame: Bitmap,
    ): List<TextRegion> {
        val detectors = listOfNotNull<TextRegionDetector>(
            yoloDetector?.takeIf { it.isAvailable() },
            mlKitRegions,
            brightRegions,
        )
        for (detector in detectors) {
            runCatching {
                val scaleX = fullFrame.width.toFloat() / detectionFrame.width
                val scaleY = fullFrame.height.toFloat() / detectionFrame.height
                val regions = detector.detect(detectionFrame).map { region ->
                    region.copy(
                        bounds = RectF(
                            region.bounds.left * scaleX,
                            region.bounds.top * scaleY,
                            region.bounds.right * scaleX,
                            region.bounds.bottom * scaleY,
                        ),
                    )
                }
                if (regions.isNotEmpty()) return regions
            }.onFailure { e -> Log.w(TAG, "Detector ${detector.name} failed", e) }
        }
        return emptyList()
    }

    private fun pickOcrEngine(): OcrEngine {
        // Hayai only when its decode loop is implemented AND the model is
        // downloaded. Until then ML Kit is the working default.
        if (HayaiLiteRtOcr.DECODE_IMPLEMENTED && hayaiOcr.isAvailable()) return hayaiOcr
        return mlKitOcr
    }

    private fun pickTranslator(): Translator = when (settings.engine) {
        Engine.OFFLINE -> mlKitTranslator
        Engine.ONLINE -> libreTranslator
        Engine.GOOGLE -> googleTranslator
    }

    /** Fallback chain when the chosen translator fails: offline -> online. */
    private suspend fun fallbackTranslate(text: String): String? {
        if (settings.engine != Engine.OFFLINE) {
            mlKitTranslator.translate(text, settings.sourceLanguage, settings.targetLanguage)
                ?.let { return it }
        }
        if (settings.engine != Engine.ONLINE) {
            libreTranslator.translate(text, settings.sourceLanguage, settings.targetLanguage)
                ?.let { return it }
        }
        return null
    }

    /** Downscale so the longest side is <= 1024px (detection budget). */
    private fun downscaleForDetection(frame: Bitmap): Bitmap {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= DETECTION_MAX_SIDE) return frame
        val scale = DETECTION_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).toInt().coerceAtLeast(1),
            (frame.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Cheap frame hash: 160x90 greyscale, mean absolute difference vs the
     * previous hash is overkill — a simple FNV-style hash of the downscaled
     * luma is enough to tell "same screen" from "new screen".
     */
    private fun frameHash(frame: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(frame, 160, 90, true)
        val pixels = IntArray(160 * 90)
        small.getPixels(pixels, 0, 160, 0, 0, 160, 90)
        if (small != frame) small.recycle()
        var hash = -3750763034362895579L // FNV-1a offset basis (0xcbf29ce484222325 as signed Long)
        for (p in pixels) {
            val luma = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 +
                (p and 0xFF)) / 10
            hash = hash xor (luma.toLong() and 0xFF)
            hash *= 0x100000001b3L // FNV-1a prime
        }
        return hash
    }

    companion object {
        private const val TAG = "TranslationPipeline"

        /** Minimum time between pipeline runs. */
        const val THROTTLE_MS = 300L

        /** Detection runs on a frame no larger than this on its longest side. */
        const val DETECTION_MAX_SIDE = 1024
    }
}
