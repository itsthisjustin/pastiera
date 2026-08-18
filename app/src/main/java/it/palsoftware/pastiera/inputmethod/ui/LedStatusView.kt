package it.palsoftware.pastiera.inputmethod.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import it.palsoftware.pastiera.R
import it.palsoftware.pastiera.inputmethod.StatusBarController
import kotlin.math.roundToInt

/**
 * Compact controller around the LED strip at the bottom of the IME status bar.
 */
class LedStatusView(
    private val context: Context
) {
    companion object {
        private val LED_COLOR_GRAY_OFF = Color.argb(100, 17, 17, 17)
        private val LED_COLOR_RED_LOCKED = Color.rgb(247, 99, 0)
        private val LED_COLOR_BLUE_ACTIVE = Color.rgb(100, 150, 255)
    }

    private val ledHeight: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            5.5f,
            context.resources.displayMetrics
        ).toInt()
    }
    private val topPadding: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            context.resources.displayMetrics
        ).toInt()
    }
    private val cornerRadius: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        )
    }

    private var container: ModifierLedCanvas? = null
    private val ledsByState = mutableMapOf<ModifierLedState, MutableList<View>>()

    internal var layout: ModifierLedLayout = ModifierLedLayouts.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            rebuildSegments()
        }

    var onLongPressListener: (() -> Unit)? = null
    var themeOverride: KeyboardThemeColors? = null

    fun ensureView(): ViewGroup {
        container?.let { return it }

        container = ModifierLedCanvas(context, ledHeight).apply {
            setPadding(0, topPadding, 0, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnLongClickListener {
                onLongPressListener?.invoke()
                true
            }
        }

        rebuildSegments()

        return container!!
    }

    fun getView(): ViewGroup? = container

    fun update(snapshot: StatusBarController.StatusSnapshot) {
        val shiftLocked = snapshot.capsLockEnabled
        val shiftActive = (snapshot.shiftPhysicallyPressed || snapshot.shiftOneShot) && !shiftLocked
        updateLeds(ModifierLedState.SHIFT, shiftLocked, shiftActive)

        val ctrlLocked = snapshot.ctrlLatchActive
        val ctrlActive = (snapshot.ctrlPhysicallyPressed || snapshot.ctrlOneShot) && !ctrlLocked
        updateLeds(ModifierLedState.CTRL, ctrlLocked, ctrlActive)

        val altLocked = snapshot.altLatchActive
        val altActive = (snapshot.altPhysicallyPressed || snapshot.altOneShot) && !altLocked
        updateLeds(ModifierLedState.ALT, altLocked, altActive)

        updateSymLeds(snapshot.symPage)
    }

    private fun rebuildSegments() {
        val canvas = container ?: return
        ledsByState.clear()
        canvas.replaceSegments(layout.segments) { segment ->
            createLedView(LED_COLOR_GRAY_OFF).also { led ->
                ledsByState.getOrPut(segment.state) { mutableListOf() }.add(led)
            }
        }
    }

    private fun createLedView(initialColor: Int): View {
        return View(context).apply {
            background = createDrawable(initialColor)
            setTag(R.id.led_previous_color, initialColor)
        }
    }

    private fun createDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = this@LedStatusView.cornerRadius
        }
    }

    private fun updateLeds(state: ModifierLedState, isLocked: Boolean, isActive: Boolean = false) {
        val theme = themeOverride
        val targetColor = when {
            isLocked -> theme?.ledLocked ?: LED_COLOR_RED_LOCKED
            isActive -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            else -> theme?.ledInactive ?: LED_COLOR_GRAY_OFF
        }
        ledsByState[state].orEmpty().forEach { led -> animateLedColor(led, targetColor) }
    }

    private fun updateSymLeds(symPage: Int) {
        val theme = themeOverride
        val targetColor = when (symPage) {
            1 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            2 -> theme?.ledLocked ?: LED_COLOR_RED_LOCKED
            3 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            4 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            else -> theme?.ledInactive ?: LED_COLOR_GRAY_OFF
        }
        ledsByState[ModifierLedState.SYM].orEmpty().forEach { led -> animateLedColor(led, targetColor) }
    }

    private fun animateLedColor(led: View?, targetColor: Int) {
        led ?: return
        val previousColor = (led.getTag(R.id.led_previous_color) as? Int) ?: LED_COLOR_GRAY_OFF
        led.setTag(R.id.led_previous_color, targetColor)

        if (previousColor == targetColor) {
            led.background = createDrawable(targetColor)
            return
        }

        ValueAnimator.ofArgb(previousColor, targetColor).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                led.background = createDrawable(color)
            }
        }.start()
    }

    private class ModifierLedCanvas(
        context: Context,
        private val contentHeightPx: Int
    ) : ViewGroup(context) {
        private val segments = mutableListOf<ModifierLedSegment>()

        fun replaceSegments(
            newSegments: List<ModifierLedSegment>,
            createView: (ModifierLedSegment) -> View
        ) {
            removeAllViews()
            segments.clear()
            newSegments.forEach { segment ->
                segments.add(segment)
                addView(createView(segment))
            }
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
            val desiredHeight = paddingTop + contentHeightPx + paddingBottom
            val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
            val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
            val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val segment = segments[index]
                child.measure(
                    MeasureSpec.makeMeasureSpec(
                        (contentWidth * segment.width).roundToInt().coerceAtLeast(1),
                        MeasureSpec.EXACTLY
                    ),
                    MeasureSpec.makeMeasureSpec(
                        (availableHeight * segment.height).roundToInt().coerceAtLeast(1),
                        MeasureSpec.EXACTLY
                    )
                )
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val contentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            val availableHeight = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val segment = segments[index]
                val childLeft = paddingLeft + (contentWidth * segment.x).roundToInt()
                val childTop = paddingTop + (availableHeight * segment.y).roundToInt()
                child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.measuredWidth,
                    childTop + child.measuredHeight
                )
            }
        }

    }
}
