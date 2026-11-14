package dev.crm.screentranslator.models

/**
 * Catalogue of every model the app can use.
 *
 * GOLDEN RULE: no model weights are ever bundled in the APK. Everything
 * heavy is downloaded on demand (Wi-Fi only by default), with its size
 * shown BEFORE the download starts, and deletable afterwards. The APK
 * itself stays small (target < 25 MB).
 *
 * LICENCE FINDINGS (verified 2026-11-15 — read before changing anything):
 * - Hayai OCR code: Apache-2.0 (LICENSE file read at
 *   https://github.com/NopeNopeGuy/hayai-ocr/blob/master/LICENSE).
 * - Hayai OCR v2 weights: Apache-2.0 (Hugging Face license:apache-2.0 tag).
 * - Hayai v2-tflite repo: NO licence field declared on Hugging Face.
 *   Runtime-download-only; never bundle until the repo declares one.
 * - YOLO11n bubble model: CONTRADICTORY. The repo's Hugging Face metadata
 *   and README frontmatter say `license: cc-by-nc-4.0` (non-commercial),
 *   but the README body claims "released under the Apache 2.0 License"
 *   with an Apache badge. Until the author clarifies, treat the weights
 *   as CC BY-NC-4.0: runtime-download-only, non-commercial use only, and
 *   never bundle. The app's own code stays Apache-2.0 either way.
 * - comic-text-detector: GPL-3.0 — DO NOT USE (would force the whole app
 *   to GPL-3.0). manga-ocr-nar-small weights: CC BY-NC-SA — DO NOT USE.
 */
object ModelRegistry {

    enum class Kind {
        /** Works out of the box (Play-services model, no APK weight). */
        WORKING,

        /** Downloadable on demand; the app works without it (fallback exists). */
        DOWNLOAD_OPTIONAL,

        /** Downloadable, but blocked until a TODO is resolved. */
        BLOCKED_TODO,
    }

    data class RemoteModel(
        val id: String,
        val displayName: String,
        val description: String,
        /** Repo / project page (always shown; never a bare file host). */
        val sourceUrl: String,
        /** Direct download URL, or null when the download is multi-file / TODO. */
        val downloadUrl: String?,
        /** File name under filesDir/models/<id>/ (null for Play-services models). */
        val fileName: String?,
        val licence: String,
        /** Exact size in bytes when known; null = "verify at download time". */
        val sizeBytes: Long?,
        val kind: Kind,
    )

    /** ML Kit Japanese OCR — working default, Play-services model. */
    val MLKIT_OCR_JAPANESE = RemoteModel(
        id = "mlkit-ocr-japanese",
        displayName = "ML Kit OCR — Japanese",
        description = "On-device Japanese text recognition (Play services). " +
            "Fallback OCR engine; handles Latin too.",
        sourceUrl = "https://developers.google.com/ml-kit/vision/text-recognition/v2",
        downloadUrl = null,
        fileName = null,
        licence = "Google ML Kit Terms of Service (proprietary SDK, free to use)",
        sizeBytes = null,
        kind = Kind.WORKING,
    )

    /** ML Kit translation models — one per language, ~30 MB each. */
    val MLKIT_TRANSLATE = RemoteModel(
        id = "mlkit-translate",
        displayName = "ML Kit Translate — language models",
        description = "Offline translation models, downloaded per language " +
            "(~30 MB each) via Play services. English + Indonesian first.",
        sourceUrl = "https://developers.google.com/ml-kit/language/translation/android",
        downloadUrl = null,
        fileName = null,
        licence = "Google ML Kit Terms of Service (proprietary SDK, free to use)",
        sizeBytes = 30L * 1024 * 1024, // per language, approximate
        kind = Kind.WORKING,
    )

    /** YOLO11n bubble segmentation — download-on-demand, licence unclear. */
    val YOLO_BUBBLE = RemoteModel(
        id = "yolo-bubble",
        displayName = "YOLO11n bubble detector",
        description = "Speech-bubble segmentation (Manga109). Precision 97.55%. " +
            "Input fixed 1600x1600. Downloaded on demand, never bundled.",
        sourceUrl = "https://huggingface.co/huyvux3005/manga109-segmentation-bubble",
        // File names best.pt/last.pt per the repo; verify the file list on
        // first download — if this URL 404s, check the repo page.
        downloadUrl = "https://huggingface.co/huyvux3005/manga109-segmentation-bubble" +
            "/resolve/main/best.pt",
        fileName = "best.pt",
        licence = "CONTRADICTORY — HF metadata/frontmatter: CC BY-NC-4.0; " +
            "README body: Apache-2.0. Treat as NON-COMMERCIAL until clarified.",
        sizeBytes = null, // verify at download time; shown before downloading
        kind = Kind.DOWNLOAD_OPTIONAL,
    )

    /** Hayai OCR v2.1 LiteRT exports — download-on-demand, decode TODO. */
    val HAYAI_LITERT = RemoteModel(
        id = "hayai-litert-wi4",
        displayName = "Hayai OCR v2.1 — LiteRT (int4)",
        description = "Manga-trained OCR (vertical text, furigana, SFX, " +
            "multi-line bubbles in one pass). LiteRT int4 export, CER 3.96% " +
            "on JMangaBench_Mixed. Multi-file download (encoder + prefill " +
            "+ decode). Decode loop still TODO — see HayaiLiteRtOcr.",
        sourceUrl = "https://huggingface.co/JustANormalTinkerer/hayai-ocr-v2-tflite",
        downloadUrl = null, // multi-file: fetched via the HF tree API
        fileName = null,
        licence = "Code Apache-2.0 (verified LICENSE); v2 weights Apache-2.0; " +
            "tflite repo declares NO licence — runtime-download only, do not bundle.",
        sizeBytes = null, // verify at download time; shown before downloading
        kind = Kind.BLOCKED_TODO,
    )

    val ALL: List<RemoteModel> = listOf(
        MLKIT_OCR_JAPANESE,
        MLKIT_TRANSLATE,
        YOLO_BUBBLE,
        HAYAI_LITERT,
    )

    fun byId(id: String): RemoteModel? = ALL.firstOrNull { it.id == id }
}
