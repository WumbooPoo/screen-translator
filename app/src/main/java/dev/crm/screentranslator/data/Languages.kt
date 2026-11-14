package dev.crm.screentranslator.data

/**
 * Languages offered in the language picker.
 *
 * ML Kit Translate supports 50+ languages offline; this list is the subset
 * shown in the picker (common pairs first). The default pair is English ->
 * Indonesian. Any pair can be chosen — the app is NOT limited to EN<->ID.
 * Codes are BCP-47 tags used by ML Kit's TranslateLanguage.fromLanguageTag().
 */
data class AppLanguage(val code: String, val displayName: String)

object Languages {
    val SUPPORTED: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("id", "Indonesia"),
        AppLanguage("ja", "日本語 (Japanese)"),
        AppLanguage("ko", "한국어 (Korean)"),
        AppLanguage("zh", "中文 (Chinese)"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("pt", "Português"),
        AppLanguage("ru", "Русский"),
        AppLanguage("ar", "العربية"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("it", "Italiano"),
        AppLanguage("ms", "Bahasa Melayu"),
        AppLanguage("th", "ไทย"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("tl", "Tagalog"),
        AppLanguage("uk", "Українська"),
    )

    fun byCode(code: String): AppLanguage? = SUPPORTED.firstOrNull { it.code == code }
}
