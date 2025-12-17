package it.palsoftware.pastiera.inputmethod.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Simple view shown in the status bar when trackpad cursor mode is active.
 * Displays a centered "Trackpad Cursor" message.
 */
class TrackpadCursorView(context: Context) : FrameLayout(context) {

    private val titleText: TextView

    init {
        setBackgroundColor(Color.TRANSPARENT)

        // Much smaller height - just enough for the text
        val fixedHeight = dpToPx(24f)
        setPadding(0, 0, 0, 0)

        titleText = TextView(context).apply {
            text = "Trackpad Cursor"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        addView(titleText)

        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            fixedHeight
        )
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
