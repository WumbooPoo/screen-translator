package dev.crm.screentranslator.detection

import android.graphics.RectF

/** What kind of text region was found. Drives manga-mode handling. */
enum class RegionKind {
    /** Speech bubble (enclosed balloon). */
    BUBBLE,

    /** Narration / caption box. */
    NARRATION,

    /** Free-floating text (signs, labels, UI text). */
    FREE_TEXT,

    /** Sound effect (onomatopoeia). Translated only when the SFX toggle is ON. */
    SFX,
}

/**
 * One text region on the captured frame, in bitmap pixel coordinates.
 * [bounds] is in the coordinate space of the bitmap passed to the detector.
 */
data class TextRegion(
    val id: String,
    val bounds: RectF,
    val kind: RegionKind,
    val confidence: Float,
)
