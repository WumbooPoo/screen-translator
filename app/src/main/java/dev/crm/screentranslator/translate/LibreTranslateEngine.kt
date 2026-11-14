package dev.crm.screentranslator.translate

import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * LibreTranslate — the ONLINE engine.
 *
 * Free and open-source (https://github.com/LibreTranslate/LibreTranslate).
 * Default instance is the public libretranslate.com; it may rate-limit or
 * require an API key under heavy use — the instance URL is user-configurable
 * in Settings, so anyone can point the app at their own self-hosted
 * LibreTranslate server instead. No key is bundled with the app.
 */
class LibreTranslateEngine(
    baseUrl: String,
) : Translator {

    override val name: String = "LibreTranslate (online)"

    private val api: LibreTranslateApi = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LibreTranslateApi::class.java)

    override fun isAvailable(): Boolean = true

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String? {
        if (text.isBlank()) return text
        return try {
            val response = api.translate(
                q = text,
                source = sourceLang, // "auto" is accepted by LibreTranslate
                target = targetLang,
            )
            response.translatedText.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "LibreTranslate failed", e)
            null
        }
    }

    private interface LibreTranslateApi {
        @FormUrlEncoded
        @POST("translate")
        suspend fun translate(
            @Field("q") q: String,
            @Field("source") source: String,
            @Field("target") target: String,
            @Field("format") format: String = "text",
        ): LibreTranslateResponse
    }

    private data class LibreTranslateResponse(
        val translatedText: String,
    )

    companion object {
        private const val TAG = "LibreTranslateEngine"
    }
}
