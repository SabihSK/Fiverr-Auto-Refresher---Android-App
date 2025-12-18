package com.sabih.touchautomation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlin.math.abs

class FloatingTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var nextRefreshAtMs: Long? = null
    private var isAutomationRunning: Boolean = false

    private val ticker = object : Runnable {
        override fun run() {
            updateTimerText()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService()
        createOverlayIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        isAutomationRunning = intent?.getBooleanExtra(EXTRA_IS_RUNNING, false) == true
        val incomingNextAt = intent?.getLongExtra(EXTRA_NEXT_REFRESH_AT, -1L) ?: -1L
        nextRefreshAtMs = incomingNextAt.takeIf { it > 0 }

        if (!isAutomationRunning) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay()
        startTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlayIfNeeded() {
        if (overlayView != null || windowManager == null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_floating_timer, null)
        val params = buildLayoutParams()

        attachTouchHandlers(view, params)

        overlayView = view
        layoutParams = params
    }

    private fun showOverlay() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return

        if (view.windowToken == null) {
            wm.addView(view, params)
        }
        updateTimerText()
    }

    private fun removeOverlay() {
        val wm = windowManager
        val view = overlayView ?: return
        if (wm != null) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
                // No-op if already removed.
            }
        }
        overlayView = null
        layoutParams = null
    }

    private fun attachTouchHandlers(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                        dragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        openApp()
                    } else {
                        snapToEdge(params)
                        savePosition(params.x, params.y)
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val saved = loadPosition()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved.first
            y = saved.second
        }
    }

    private fun startTicker() {
        handler.removeCallbacksAndMessages(null)
        handler.post(ticker)
    }

    private fun updateTimerText() {
        val textView = overlayView?.findViewById<TextView>(R.id.textFloatingTimer) ?: return
        val target = nextRefreshAtMs

        val display = if (!isAutomationRunning || target == null) {
            "--:--"
        } else {
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0) {
                "Now"
            } else {
                val minutes = remaining / 60_000
                val seconds = (remaining / 1_000) % 60
                String.format("%d:%02d", minutes, seconds)
            }
        }
        textView.text = display
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewWidth = overlayView?.width ?: 0
        val viewHeight = overlayView?.height ?: 0
        val midpoint = screenWidth / 2

        params.x = if (params.x + viewWidth / 2 > midpoint) {
            (screenWidth - viewWidth).coerceAtLeast(0)
        } else {
            0
        }
        params.y = params.y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
        windowManager?.updateViewLayout(overlayView, params)
    }

    private fun savePosition(x: Int, y: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putInt(KEY_POS_X, x)
            putInt(KEY_POS_Y, y)
        }
    }

    private fun loadPosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultX = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val defaultY = (resources.displayMetrics.heightPixels * 0.3f).toInt()
        val x = prefs.getInt(KEY_POS_X, defaultX)
        val y = prefs.getInt(KEY_POS_Y, defaultY)
        return x to y
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    companion object {
        private const val EXTRA_NEXT_REFRESH_AT = "extra_next_refresh_at"
        private const val EXTRA_IS_RUNNING = "extra_is_running"
        private const val PREFS_NAME = "floating_timer_prefs"
        private const val KEY_POS_X = "floating_pos_x"
        private const val KEY_POS_Y = "floating_pos_y"
        private const val TOUCH_SLOP = 8
        private const val TICK_MS = 1_000L

        fun updateTimer(context: Context, isRunning: Boolean, nextRefreshAtMs: Long?) {
            if (!Settings.canDrawOverlays(context)) {
                return
            }

            val intent = Intent(context, FloatingTimerService::class.java).apply {
                putExtra(EXTRA_IS_RUNNING, isRunning)
                putExtra(EXTRA_NEXT_REFRESH_AT, nextRefreshAtMs ?: -1L)
            }

            if (isRunning) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
