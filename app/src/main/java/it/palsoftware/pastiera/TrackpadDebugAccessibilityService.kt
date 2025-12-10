package it.palsoftware.pastiera

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TrackpadDebugAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var debugTextView: TextView? = null
    private val events = mutableListOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need these events
    }

    override fun onInterrupt() {
        // Required override
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        debugTextView = TextView(this).apply {
            text = "Trackpad Debug (A11y)\nWaiting for events...\n\n"
            textSize = 8f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF00FF00.toInt())
            setPadding(4, 4, 4, 4)
            maxLines = 10
        }

        val scrollView = ScrollView(this).apply {
            addView(debugTextView)
        }

        overlayView = object : LinearLayout(this) {
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                android.util.Log.d("TrackpadDebug", "A11y onGenericMotionEvent: ${event.actionMasked}")
                logEvent(event)
                return false // Pass through
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                android.util.Log.d("TrackpadDebug", "A11y onTouchEvent: ${event.actionMasked}")
                logEvent(event)
                return false // Pass through
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                android.util.Log.d("TrackpadDebug", "A11y dispatchGenericMotionEvent: ${event.actionMasked}")
                logEvent(event)
                return super.dispatchGenericMotionEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt())
            addView(scrollView)
        }

        val params = WindowManager.LayoutParams(
            300,
            400,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 100
        }

        windowManager?.addView(overlayView, params)
    }

    private fun logEvent(event: MotionEvent) {
        val actionStr = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER"
            MotionEvent.ACTION_SCROLL -> "SCROLL"
            else -> "ACT_${event.actionMasked}"
        }

        val sourceStr = when (event.source) {
            android.view.InputDevice.SOURCE_TOUCHPAD -> "TPAD"
            android.view.InputDevice.SOURCE_TOUCHSCREEN -> "TSCR"
            android.view.InputDevice.SOURCE_MOUSE -> "MOUS"
            else -> "SRC_${event.source}"
        }

        val line = "[$actionStr/$sourceStr] X=${"%.0f".format(event.x)} Y=${"%.0f".format(event.y)}\n"
        events.add(line)

        while (events.size > 10) {
            events.removeAt(0)
        }

        debugTextView?.post {
            debugTextView?.text = "Trackpad (A11y) ${events.size}\n\n" + events.joinToString("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
