package dev.crm.screentranslator.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.crm.screentranslator.data.SettingsStore
import dev.crm.screentranslator.manga.BubbleFit
import dev.crm.screentranslator.pipeline.TranslatedBubble
import kotlin.math.abs

/**
 * Floating bubble + translated-bubble overlay windows.
 *
 * WHY CLASSIC VIEWS (not Compose): a ComposeView added to WindowManager
 * from a Service needs a manually wired LifecycleOwner, SavedStateRegistry
 * owner and Recomposer. Classic Views just work in a service window, and
 * the overlay is simple (a rounded box + centred text). The in-app UI
 * (Home / Settings / ModelManager) is Compose; only these floating windows
 * are Views. If Compose-in-service is ever wanted, wire
 * ViewTreeLifecycleOwner + WindowRecomposer explicitly — do not just call
 * setContent() and hope.
 *
 * - Floating bubble: one small circular window, draggable, snaps to the
 *   nearest screen edge on release. Tap = translate the screen now.
 * - Translated bubbles: one small window per bubble, positioned exactly
 *   over the source bubble. Draggable (if a translation covers a face,
 *   drag it away). Tap = expand in place to show original + translation.
 *   Positions reset when a new translation result arrives (V1 limitation).
 */
class OverlayWindowManager(
    context: Context,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = appContext.resources.displayMetrics.density
    private val screenWidth = appContext.resources.displayMetrics.widthPixels
    private val screenHeight = appContext.resources.displayMetrics.heightPixels

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val overlayViews = mutableListOf<View>()
    private val expandedIds = mutableSetOf<String>()
    private var currentSignature: String? = null

    /** Shows the floating bubble. [onTap] runs on tap (translate now). */
    fun showBubble(onTap: () -> Unit) {
        if (bubbleView != null) return
        val sizePx = (settings.bubbleSizeDp * density).toInt()
        val container = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUBBLE_COLOR)
            }
            addView(
                TextView(appContext).apply {
                    text = "文"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start at the right edge, vertically centred.
            x = screenWidth - sizePx - (16 * density).toInt()
            y = screenHeight / 2
        }
        container.setOnTouchListener(
            DragTouchListener(params, onTap = onTap, onRelease = { snapBubbleToEdge() }),
        )
        windowManager.addView(container, params)
        bubbleView = container
        bubbleParams = params
    }

    /** Renders translated bubbles. No-op when the result is unchanged. */
    fun showBubbles(bubbles: List<TranslatedBubble>) {
        val signature = bubbles.joinToString("|") { "${it.region.id}:${it.translated}" }
        if (signature == currentSignature) return
        currentSignature = signature
        clearOverlayViews()
        for (bubble in bubbles) addBubbleWindow(bubble)
    }

    private fun addBubbleWindow(bubble: TranslatedBubble) {
        val region = bubble.region
        val expanded = expandedIds.contains(region.id)
        val widthPx = region.bounds.width().toInt().coerceAtLeast((48 * density).toInt())
        val heightPx = if (expanded) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            region.bounds.height().toInt().coerceAtLeast((32 * density).toInt())
        }
        val paddingPx = (6 * density).toInt()
        val fill = BubbleFit.withOpacity(bubble.fillColor, settings.overlayOpacity)
        val textColor = BubbleFit.textColorFor(bubble.fillColor)
        val text = if (expanded) "${bubble.original}\n\n${bubble.translated}" else bubble.translated
        val fontSizeSp = if (expanded) {
            14f
        } else {
            BubbleFit.fitFontSizeSp(
                text = bubble.translated,
                maxWidthPx = widthPx - paddingPx * 2,
                maxHeightPx = (if (heightPx > 0) heightPx else 200) - paddingPx * 2,
                density = density,
            )
        }
        val container = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(fill)
            }
            addView(
                TextView(appContext).apply {
                    this.text = text
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                    gravity = Gravity.CENTER
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val params = WindowManager.LayoutParams(
            widthPx, heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = region.bounds.left.toInt().coerceAtLeast(0)
            y = region.bounds.top.toInt().coerceAtLeast(0)
        }
        container.setOnTouchListener(
            DragTouchListener(
                params,
                onTap = {
                    if (expandedIds.contains(region.id)) expandedIds.remove(region.id)
                    else expandedIds.add(region.id)
                    // Rebuild this one window in its new state.
                    removeOverlayView(container)
                    addBubbleWindow(bubble)
                },
            ),
        )
        windowManager.addView(container, params)
        overlayViews.add(container)
    }

    private fun removeOverlayView(view: View) {
        runCatching { windowManager.removeView(view) }
        overlayViews.remove(view)
    }

    private fun clearOverlayViews() {
        for (view in overlayViews.toList()) runCatching { windowManager.removeView(view) }
        overlayViews.clear()
    }

    private fun snapBubbleToEdge() {
        val params = bubbleParams ?: return
        if (!settings.snapToEdge) return
        val sizePx = (settings.bubbleSizeDp * density).toInt()
        val centerX = params.x + sizePx / 2
        params.x = if (centerX < screenWidth / 2) 0 else screenWidth - sizePx
        bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** Removes the bubble and all overlay windows. */
    fun removeAll() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
        clearOverlayViews()
        expandedIds.clear()
        currentSignature = null
    }

    /**
     * Drag-or-tap touch handling for a floating window. Dragging moves the
     * window; a quick tap (small movement, short press) fires [onTap].
     */
    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
        private val onRelease: (() -> Unit)? = null,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var downTime = 0L
        private var viewRef: View? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    viewRef = view
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    downTime = System.currentTimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downX + (event.rawX - downRawX).toInt()
                    params.y = downY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    val duration = System.currentTimeMillis() - downTime
                    if (moved < 12 * density && duration < 300) {
                        onTap()
                    } else {
                        onRelease?.invoke()
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        // Material baseline primary purple.
        private const val BUBBLE_COLOR = 0xFF6750A4.toInt()
    }
}
