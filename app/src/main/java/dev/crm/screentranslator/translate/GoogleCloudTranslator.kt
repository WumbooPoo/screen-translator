package dev.crm.screentranslator.translate

/**
 * Google Cloud Translation (v2 REST API) — TODO, not implemented in V1.
 *
 * Why a stub: it needs a Google Cloud API key, and this app ships with NO
 * keys and NO premium features. When/if it is added, the integration is:
 *  - Endpoint: POST https://translation.googleapis.com/language/translate/v2
 *    with `key=<API_KEY>` (or an OAuth token), body
 *    `{ "q": text, "source": "en", "target": "id", "format": "text" }`.
 *  - The key must come from the user's own Google Cloud project and be
 *    entered in Settings — never hard-coded, never bundled.
 *  - "auto" source: omit the `source` field and the API detects it.
 *
 * Until then the engine picker shows this engine as "later" and the
 * pipeline never selects it.
 */
class GoogleCloudTranslator : Translator {

    override val name: String = "Google Cloud Translation (later)"

    override fun isAvailable(): Boolean = false

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? = null
}
