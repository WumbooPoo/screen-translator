package dev.crm.screentranslator.manga

import dev.crm.screentranslator.detection.TextRegion

/**
 * Manga reading order: right-to-left, top-to-bottom.
 *
 * No off-the-shelf permissive reading-order solver exists for Android, so
 * V1 implements the geometry directly:
 *  1. Cluster bubbles into rows by vertical overlap (rows = panels-ish).
 *  2. Sort rows top-to-bottom.
 *  3. Within a row, sort bubbles right-to-left (rightmost first).
 *
 * Within a bubble, vertical columns are already in reading order: the ML
 * Kit path rotates tall crops 90° CW, so rotated OCR lines read
 * top-to-bottom = original columns right-to-left. Hayai reads the whole
 * bubble in one pass in the correct order natively.
 *
 * Known limit: interlocking / irregular panel layouts can confuse the
 * order. V1 accepts imperfection here — the overlay lets the user tap a
 * bubble to re-read it, and the detail card shows bubbles in order.
 */
object ReadingOrder {

    /**
     * Sorts regions into manga reading order (right-to-left, top-to-bottom).
     * Input and output use the same [TextRegion] objects, only reordered.
     */
    fun sort(regions: List<TextRegion>): List<TextRegion> {
        if (regions.size <= 1) return regions
        // Cluster into rows: two bubbles share a row when their vertical
        // ranges overlap by more than 25% of the shorter bubble's height.
        val rows = mutableListOf<MutableList<TextRegion>>()
        val sortedByTop = regions.sortedBy { it.bounds.top }
        for (region in sortedByTop) {
            val row = rows.firstOrNull { row ->
                row.any { other -> verticalOverlap(region, other) > 0.25f }
            }
            if (row != null) row.add(region) else rows.add(mutableListOf(region))
        }
        // Rows top-to-bottom; within a row, right-to-left (center x desc).
        return rows
            .sortedBy { row -> row.minOf { it.bounds.top } }
            .flatMap { row -> row.sortedByDescending { it.bounds.centerX() } }
    }

    private fun verticalOverlap(a: TextRegion, b: TextRegion): Float {
        val overlap = minOf(a.bounds.bottom, b.bounds.bottom) -
            maxOf(a.bounds.top, b.bounds.top)
        if (overlap <= 0f) return 0f
        val shorter = minOf(a.bounds.height(), b.bounds.height())
        return if (shorter > 0f) overlap / shorter else 0f
    }

    /**
     * Joins sentences split across bubbles. A bubble whose text does not
     * end with sentence-final punctuation continues into the next bubble
     * in reading order. CJK text joins without a space; Latin joins with
     * one space.
     *
     * @param texts bubble texts in reading order (same order as [sort]).
     * @return the joined texts, same size as input (a joined bubble keeps
     *   its text; the continuation bubble becomes an empty string so the
     *   caller can drop it).
     */
    fun joinSplitSentences(texts: List<String>): List<String> {
        if (texts.size <= 1) return texts
        val out = texts.toMutableList()
        var i = 0
        while (i < out.size - 1) {
            val current = out[i].trim()
            val next = out[i + 1].trim()
            if (current.isNotEmpty() && next.isNotEmpty() &&
                !endsSentence(current) && looksLikeContinuation(next)
            ) {
                val joiner = if (containsCjk(current) || containsCjk(next)) "" else " "
                out[i] = current + joiner + next
                out[i + 1] = ""
            }
            i++
        }
        return out
    }

    private fun endsSentence(text: String): Boolean {
        val last = text.last()
        return last in "。！？!?…."
    }

    /** A continuation usually starts lowercase, with CJK, or with 、 / ，. */
    private fun looksLikeContinuation(text: String): Boolean {
        val first = text.first()
        return first.isLowerCase() || first in "、，, " || containsCjk(text.take(1))
    }

    private fun containsCjk(text: String): Boolean =
        text.any { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' || it in '\uAC00'..'\uD7AF' }
}
