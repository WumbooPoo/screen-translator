package dev.crm.screentranslator.data

import android.content.Context
import android.content.SharedPreferences

/** Translation engine chosen by the user. No premium engines exist in V1. */
enum class Engine {
    /** ML Kit on-device translation (offline after model download). Default. */
    OFFLINE,

    /** LibreTranslate (free public instance or self-hosted). Needs internet. */
    ONLINE,

    /** Google Cloud Translation. TODO: needs an API key — not available in V1. */
    GOOGLE,
}

/**
 * Small, dependency-free settings store (SharedPreferences).
 * Kept deliberately free of DataStore: one less dependency, zero startup cost.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var engine: Engine
        get() = runCatching { Engine.valueOf(prefs.getString(KEY_ENGINE, null) ?: "") }
            .getOrDefault(Engine.OFFLINE)
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    /** BCP-47 code ("en", "id", "ja", …) or "auto" for auto-detect. */
    var sourceLanguage: String
        get() = prefs.getString(KEY_SOURCE_LANG, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_SOURCE_LANG, value).apply()

    /** BCP-47 code. Default: Indonesian. */
    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANG, "id") ?: "id"
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    /** Manga mode: bubble detection + RTL reading order + manga OCR. Default ON. */
    var mangaMode: Boolean
        get() = prefs.getBoolean(KEY_MANGA_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_MANGA_MODE, value).apply()

    /** Translate sound effects (SFX). Default OFF — SFX is skipped by default. */
    var sfxEnabled: Boolean
        get() = prefs.getBoolean(KEY_SFX, false)
        set(value) = prefs.edit().putBoolean(KEY_SFX, value).apply()

    /** Overlay background opacity, 10..100. Default 90. */
    var overlayOpacity: Int
        get() = prefs.getInt(KEY_OPACITY, 90).coerceIn(10, 100)
        set(value) = prefs.edit().putInt(KEY_OPACITY, value.coerceIn(10, 100)).apply()

    /** Floating bubble diameter in dp. Default 45. */
    var bubbleSizeDp: Int
        get() = prefs.getInt(KEY_BUBBLE_SIZE, 45).coerceIn(32, 72)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_SIZE, value.coerceIn(32, 72)).apply()

    /** Snap the floating bubble to the nearest screen edge on release. Default ON. */
    var snapToEdge: Boolean
        get() = prefs.getBoolean(KEY_SNAP_EDGE, true)
        set(value) = prefs.edit().putBoolean(KEY_SNAP_EDGE, value).apply()

    /** Download models on Wi-Fi only. Default ON (models are tens of MB). */
    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** LibreTranslate instance base URL (user-configurable). */
    var libreTranslateUrl: String
        get() = prefs.getString(KEY_LIBRE_URL, DEFAULT_LIBRE_URL) ?: DEFAULT_LIBRE_URL
        set(value) = prefs.edit().putString(KEY_LIBRE_URL, value.trim().trimEnd('/')).apply()

    companion object {
        private const val PREFS_NAME = "screen_translator"
        private const val KEY_ENGINE = "engine"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_MANGA_MODE = "manga_mode"
        private const val KEY_SFX = "sfx_enabled"
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_BUBBLE_SIZE = "bubble_size_dp"
        private const val KEY_SNAP_EDGE = "snap_to_edge"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LIBRE_URL = "libre_url"

        /** Default public LibreTranslate instance. May rate-limit; user can self-host. */
        const val DEFAULT_LIBRE_URL = "https://libretranslate.com"
    }
}
