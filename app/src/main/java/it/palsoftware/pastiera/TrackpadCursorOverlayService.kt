package it.palsoftware.pastiera

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Service that displays a semi-transparent cursor overlay on the screen.
 * The cursor can be moved using trackpad events.
 */
class TrackpadCursorOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var cursorView: View? = null
    private var cursorX: Float = 0f
    private var cursorY: Float = 0f
    private val cursorBinder = CursorBinder()

    companion object {
        private const val TAG = "TrackpadCursor"
        private const val CURSOR_SIZE_DP = 24
    }

    inner class CursorBinder : Binder() {
        fun getService(): TrackpadCursorOverlayService = this@TrackpadCursorOverlayService
    }

    override fun onBind(intent: Intent?): IBinder = cursorBinder

    override fun onCreate() {
        super.onCreate()

        // Check if we have overlay permission
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No overlay permission - stopping service")
            stopSelf()
            return
        }

        showCursor()
    }

    private fun showCursor() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Get screen dimensions to center cursor initially
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        // Create cursor view - a semi-transparent circle
        val cursorSizePx = (CURSOR_SIZE_DP * displayMetrics.density).toInt()

        cursorView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(cursorSizePx, cursorSizePx)

            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 255, 255, 255)) // Semi-transparent white
                // Add a border for better visibility
                setStroke(2, Color.argb(255, 100, 100, 255)) // Blue border
            }
            background = drawable
        }

        val params = WindowManager.LayoutParams(
            cursorSizePx,
            cursorSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt() - cursorSizePx / 2
            y = cursorY.toInt() - cursorSizePx / 2
        }

        windowManager?.addView(cursorView, params)
        Log.d(TAG, "Cursor overlay shown at ($cursorX, $cursorY)")
    }

    /**
     * Update cursor position from trackpad movement.
     * @param deltaX Movement in X axis
     * @param deltaY Movement in Y axis
     */
    fun updateCursorPosition(deltaX: Float, deltaY: Float) {
        Handler(Looper.getMainLooper()).post {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val cursorSizePx = (CURSOR_SIZE_DP * displayMetrics.density).toInt()

            // Update cursor position with bounds checking
            cursorX = max(0f, min(screenWidth.toFloat(), cursorX + deltaX))
            cursorY = max(0f, min(screenHeight.toFloat(), cursorY + deltaY))

            // Update window position
            cursorView?.let { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                params.x = cursorX.toInt() - cursorSizePx / 2
                params.y = cursorY.toInt() - cursorSizePx / 2
                windowManager?.updateViewLayout(view, params)
            }
        }
    }

    /**
     * Set absolute cursor position (from raw trackpad coordinates).
     * @param x Absolute X position (0-1440 trackpad range)
     * @param y Absolute Y position (0-900 trackpad range)
     * @param trackpadMaxX Maximum trackpad X value (default 1440)
     * @param trackpadMaxY Maximum trackpad Y value (default 900)
     */
    fun setCursorAbsolutePosition(x: Int, y: Int, trackpadMaxX: Int = 1440, trackpadMaxY: Int = 900) {
        Handler(Looper.getMainLooper()).post {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Map trackpad coordinates to screen coordinates
            cursorX = (x.toFloat() / trackpadMaxX) * screenWidth
            cursorY = (y.toFloat() / trackpadMaxY) * screenHeight

            val cursorSizePx = (CURSOR_SIZE_DP * displayMetrics.density).toInt()

            // Update window position
            cursorView?.let { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                params.x = cursorX.toInt() - cursorSizePx / 2
                params.y = cursorY.toInt() - cursorSizePx / 2
                windowManager?.updateViewLayout(view, params)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cursorView?.let {
            try {
                windowManager?.removeView(it)
                Log.d(TAG, "Cursor overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing cursor overlay", e)
            }
        }
        cursorView = null
        windowManager = null
    }
}
