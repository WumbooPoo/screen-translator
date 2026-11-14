package dev.crm.screentranslator.translate

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit on-device translation — the OFFLINE default engine.
 *
 * Free, no internet after the language models are downloaded
 * (~30 MB per language, Wi-Fi download, deletable from ModelManager).
 * Supports 50+ languages; the picker offers the common pairs, default
 * English -> Indonesian. The user can pick ANY pair — not just EN<->ID.
 *
 * Honest limits: quality is below cloud translation for long/complex
 * sentences and idioms, and ML Kit's translation is English-centric
 * (non-English pairs route through English internally, so quality drops
 * a little for e.g. Japanese -> Indonesian vs English -> Indonesian).
 */
class MlKitTranslator(
    /** Download translation models on Wi-Fi only (user setting). */
    private val wifiOnly: Boolean = true,
) : Translator {

    override val name: String = "ML Kit Translate (offline)"

    private val languageIdClient by lazy { LanguageIdentification.getClient() }

    /** One Translator client per language pair, cached. */
    private val clients = mutableMapOf<Pair<String, String>, com.google.mlkit.nl.translate.Translator>()

    override fun isAvailable(): Boolean = true

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        if (text.isBlank()) return text
        val resolvedSource = if (sourceLang == "auto") {
            identifyLanguage(text) ?: return null
        } else {
            sourceLang
        }
        if (resolvedSource == targetLang) return text
        val client = getClient(resolvedSource, targetLang) ?: return null
        return try {
            val conditions = DownloadConditions.Builder().apply {
                if (wifiOnly) requireWifi()
            }.build()
            client.downloadModelIfNeeded(conditions).await()
            client.translate(text).await()
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit translate failed ($resolvedSource->$targetLang)", e)
            null
        }
    }

    private fun getClient(
        sourceLang: String,
        targetLang: String,
    ): com.google.mlkit.nl.translate.Translator? {
        val key = sourceLang to targetLang
        clients[key]?.let { return it }
        val source = runCatching { TranslateLanguage.fromLanguageTag(sourceLang) }.getOrNull()
        val target = runCatching { TranslateLanguage.fromLanguageTag(targetLang) }.getOrNull()
        if (source == null || target == null) return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also { clients[key] = it }
    }

    /** Returns a BCP-47 tag ("ja", "en", …) or null when undetectable. */
    private suspend fun identifyLanguage(text: String): String? {
        return try {
            val tag = languageIdClient.identifyLanguage(text).await()
            if (tag == "und") null else tag
        } catch (e: Exception) {
            Log.w(TAG, "Language identification failed", e)
            null
        }
    }

    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    companion object {
        private const val TAG = "MlKitTranslator"
    }
}
