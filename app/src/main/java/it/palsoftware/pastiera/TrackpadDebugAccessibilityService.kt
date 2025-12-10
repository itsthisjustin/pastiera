package it.palsoftware.pastiera

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

class TrackpadDebugAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var debugTextView: TextView? = null
    private val events = mutableListOf<String>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var geteventJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startGetevent()
        } else {
            updateDebugText("Shizuku permission denied")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        showOverlay()
        checkShizukuAndStart()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need these events
    }

    override fun onInterrupt() {
        // Required override
    }

    private fun checkShizukuAndStart() {
        try {
            if (!Shizuku.pingBinder()) {
                updateDebugText("Shizuku not running!\nPlease start Shizuku app")
                return
            }

            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startGetevent()
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                updateDebugText("Need Shizuku permission")
                Shizuku.requestPermission(1001)
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            updateDebugText("Shizuku error: ${e.message}")
            android.util.Log.e("TrackpadDebug", "Shizuku check failed", e)
        }
    }

    private fun startGetevent() {
        geteventJob?.cancel()
        geteventJob = serviceScope.launch {
            try {
                updateDebugText("Starting getevent...")

                // Use reflection to access Shizuku.newProcess
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("getevent", "-l", "/dev/input/event7"),
                    null,
                    null
                ) as Process

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.contains("ABS_MT") || line.contains("BTN_TOUCH") ||
                            line.contains("SYN_REPORT")) {
                            logShizukuEvent(line)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrackpadDebug", "getevent failed", e)
                updateDebugText("getevent error: ${e.message}\nMake sure Shizuku is running")
            }
        }
    }

    private fun logShizukuEvent(line: String) {
        handler.post {
            events.add(line.take(50) + "\n")
            while (events.size > 10) {
                events.removeAt(0)
            }
            debugTextView?.text = "Trackpad (Shizuku)\n\n" + events.joinToString("")
        }
    }

    private fun updateDebugText(text: String) {
        handler.post {
            debugTextView?.text = text
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        debugTextView = TextView(this).apply {
            text = "Trackpad Debug (Shizuku)\nChecking Shizuku...\n\n"
            textSize = 8f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF00FF00.toInt())
            setPadding(8, 8, 8, 8)
        }

        val scrollView = ScrollView(this).apply {
            addView(debugTextView)
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt())
            addView(scrollView)
        }

        val params = WindowManager.LayoutParams(
            350,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 100
        }

        windowManager?.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        geteventJob?.cancel()
        serviceScope.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
