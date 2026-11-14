package dev.crm.screentranslator.translate

/**
 * A translation engine. [translate] returns null when the engine cannot
 * produce a translation (model missing, network error, not configured) —
 * the pipeline then tries the next engine or shows the original text.
 */
interface Translator {
    val name: String

    /** True when this engine can run right now. */
    fun isAvailable(): Boolean

    /**
     * Translate [text] from [sourceLang] to [targetLang].
     * [sourceLang] may be "auto" (auto-detect) when the engine supports it.
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String?
}
