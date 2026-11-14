package dev.crm.screentranslator.manga

import dev.crm.screentranslator.detection.RegionKind
import dev.crm.screentranslator.detection.TextRegion

/**
 * Sound-effect (SFX / onomatopoeia) handling for manga mode.
 *
 * Rule: SFX is NOT translated by default. The user must opt in with the
 * "Terjemahkan SFX" toggle in Settings. Rationale: SFX is art as much as
 * text (ドキドキ, バビュン) — translating it by default clutters the page,
 * and generic translators mangle onomatopoeia anyway.
 *
 * Detection is kind-based first ([RegionKind.SFX] from the detector), with
 * a conservative text heuristic as a backstop: short all-katakana strings
 * outside bubbles are very likely SFX.
 */
object SfxFilter {

    /**
     * Returns true when this region+text should be treated as SFX.
     */
    fun isSfx(region: TextRegion, text: String): Boolean {
        if (region.kind == RegionKind.SFX) return true
        if (region.kind == RegionKind.BUBBLE) return false
        val trimmed = text.trim()
        // Short katakana-only strings outside bubbles (e.g. ドキドキ).
        if (trimmed.length in 2..6 && trimmed.all { it in '\u30A0'..'\u30FF' || it == 'ー' }) {
            return true
        }
        return false
    }

    /**
     * Drops SFX items unless [sfxEnabled]. Items are (region, text) pairs
     * in reading order; the returned list keeps the original order.
     */
    fun filter(
        items: List<Pair<TextRegion, String>>,
        sfxEnabled: Boolean,
    ): List<Pair<TextRegion, String>> {
        if (sfxEnabled) return items
        return items.filterNot { (region, text) -> isSfx(region, text) }
    }
}
