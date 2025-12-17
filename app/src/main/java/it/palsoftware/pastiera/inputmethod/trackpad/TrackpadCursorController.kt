package it.palsoftware.pastiera.inputmethod.trackpad

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import it.palsoftware.pastiera.TrackpadCursorOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Controller for trackpad cursor mode. Listens to raw trackpad position events
 * and updates the cursor overlay service.
 */
class TrackpadCursorController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventDevice: String = "/dev/input/event7",
    private val trackpadMaxX: Int = 1440,
    private val trackpadMaxY: Int = 900
) {
    private var geteventJob: Job? = null
    private var cursorService: TrackpadCursorOverlayService? = null
    private var isBound = false

    private var currentX = 0
    private var currentY = 0
    private var lastUpdateTime = 0L
    private val updateThrottleMs = 16L // ~60fps

    private var isTouching = false
    private var touchDownTime = 0L

    companion object {
        private const val TAG = "TrackpadCursor"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as? TrackpadCursorOverlayService.CursorBinder
            cursorService = binder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            cursorService = null
            isBound = false
        }
    }

    /**
     * Start cursor mode: bind to overlay service and start reading trackpad events.
     * Returns true if started successfully, false if permission is needed.
     */
    fun start(): Boolean {
        if (geteventJob?.isActive == true) {
            Log.d(TAG, "Already running")
            return true
        }

        // Check for overlay permission
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted")
            requestOverlayPermission()
            return false
        }

        // Start and bind to overlay service
        val serviceIntent = Intent(context, TrackpadCursorOverlayService::class.java)
        context.startService(serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Start reading trackpad events
        geteventJob = scope.launch(Dispatchers.IO) {
            try {
                // Wait a bit for service to bind
                kotlinx.coroutines.delay(500)

                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                Log.d(TAG, "Starting getevent for cursor tracking on device $eventDevice")
                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("getevent", "-l", eventDevice),
                    null,
                    null
                ) as Process

                Log.d(TAG, "getevent process started, reading events...")
                var lineCount = 0
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        lineCount++
                        if (lineCount % 100 == 0) {
                            Log.d(TAG, "Processed $lineCount events")
                        }
                        parseTrackpadPosition(line)
                    }
                }
                Log.d(TAG, "getevent loop ended after $lineCount events")
            } catch (e: Exception) {
                Log.e(TAG, "getevent failed: ${e.message}", e)
            }
        }
        Log.d(TAG, "Cursor mode started")
        return true
    }

    /**
     * Request overlay permission by opening Settings.
     */
    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Toast.makeText(
                context,
                "Please grant overlay permission to use trackpad cursor mode",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request overlay permission", e)
            Toast.makeText(
                context,
                "Failed to open permission settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Stop cursor mode: unbind from service, stop reading events.
     */
    fun stop() {
        Log.d(TAG, "Stopping cursor mode...")

        geteventJob?.cancel()
        geteventJob = null

        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isBound = false
        }

        val serviceIntent = Intent(context, TrackpadCursorOverlayService::class.java)
        try {
            context.stopService(serviceIntent)
            Log.d(TAG, "Service stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }

        cursorService = null
        Log.d(TAG, "Cursor mode stopped")
    }

    fun isRunning(): Boolean {
        return geteventJob?.isActive == true
    }

    private fun parseTrackpadPosition(line: String) {
        var updated = false
        when {
            line.contains("ABS_MT_POSITION_X") -> {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val hexValue = parts.last()
                    val newX = hexValue.toIntOrNull(16)
                    if (newX != null && newX != currentX) {
                        currentX = newX
                        updated = true
                    }
                }
            }

            line.contains("ABS_MT_POSITION_Y") -> {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val hexValue = parts.last()
                    val newY = hexValue.toIntOrNull(16)
                    if (newY != null && newY != currentY) {
                        currentY = newY
                        updated = true
                    }
                }
            }

            line.contains("BTN_TOUCH") || line.contains("BTN_LEFT") -> {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val value = parts.last()
                    if (value == "DOWN") {
                        isTouching = true
                        touchDownTime = System.currentTimeMillis()
                        Log.d(TAG, "Touch down detected")
                    } else if (value == "UP" && isTouching) {
                        val touchDuration = System.currentTimeMillis() - touchDownTime
                        isTouching = false
                        // Only trigger tap if touch was brief (< 500ms)
                        if (touchDuration < 500) {
                            performTap()
                        }
                        Log.d(TAG, "Touch up detected (duration: ${touchDuration}ms)")
                    }
                }
            }

            line.contains("ABS_MT_TRACKING_ID") -> {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val hexValue = parts.last()
                    val trackingId = hexValue.toLongOrNull(16)
                    if (trackingId != null) {
                        if (trackingId != -1L && !isTouching) {
                            isTouching = true
                            touchDownTime = System.currentTimeMillis()
                            Log.d(TAG, "Touch down detected (tracking ID: $trackingId)")
                        } else if (trackingId == -1L && isTouching) {
                            val touchDuration = System.currentTimeMillis() - touchDownTime
                            isTouching = false
                            // Only trigger tap if touch was brief (< 500ms)
                            if (touchDuration < 500) {
                                performTap()
                            }
                            Log.d(TAG, "Touch up detected (duration: ${touchDuration}ms)")
                        }
                    }
                }
            }
        }

        // Update cursor position if coordinates changed and enough time has passed
        if (updated) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= updateThrottleMs) {
                updateCursorPosition()
                lastUpdateTime = now
            }
        }
    }

    private fun updateCursorPosition() {
        if (cursorService == null) {
            Log.w(TAG, "Cannot update cursor - service not bound yet")
            return
        }
        cursorService?.setCursorAbsolutePosition(currentX, currentY, trackpadMaxX, trackpadMaxY)
        Log.d(TAG, "Cursor updated to trackpad position ($currentX, $currentY)")
    }

    private fun performTap() {
        scope.launch(Dispatchers.IO) {
            try {
                // Get screen dimensions
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Convert trackpad coordinates to screen coordinates
                val screenX = (currentX.toFloat() / trackpadMaxX) * screenWidth
                val screenY = (currentY.toFloat() / trackpadMaxY) * screenHeight

                Log.d(TAG, "Performing tap at screen position ($screenX, $screenY)")

                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("input", "tap", screenX.toInt().toString(), screenY.toInt().toString()),
                    null,
                    null
                ) as Process

                process.waitFor()
                Log.d(TAG, "Tap completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform tap: ${e.message}", e)
            }
        }
    }

    /**
     * Perform a left click at the current cursor position.
     */
    fun performLeftClick() {
        scope.launch(Dispatchers.IO) {
            try {
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                val screenX = (currentX.toFloat() / trackpadMaxX) * screenWidth
                val screenY = (currentY.toFloat() / trackpadMaxY) * screenHeight

                Log.d(TAG, "Performing left click at ($screenX, $screenY)")

                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("input", "tap", screenX.toInt().toString(), screenY.toInt().toString()),
                    null,
                    null
                ) as Process

                process.waitFor()
                Log.d(TAG, "Left click completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform left click: ${e.message}", e)
            }
        }
    }

    /**
     * Perform a right click (long press) at the current cursor position.
     */
    fun performRightClick() {
        scope.launch(Dispatchers.IO) {
            try {
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                val screenX = (currentX.toFloat() / trackpadMaxX) * screenWidth
                val screenY = (currentY.toFloat() / trackpadMaxY) * screenHeight

                Log.d(TAG, "Performing right click (long press) at ($screenX, $screenY)")

                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                // Use swipe with 0 duration for long press
                val process = newProcessMethod.invoke(
                    null,
                    arrayOf(
                        "input", "swipe",
                        screenX.toInt().toString(),
                        screenY.toInt().toString(),
                        screenX.toInt().toString(),
                        screenY.toInt().toString(),
                        "1000"  // 1 second long press
                    ),
                    null,
                    null
                ) as Process

                process.waitFor()
                Log.d(TAG, "Right click completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform right click: ${e.message}", e)
            }
        }
    }
}
