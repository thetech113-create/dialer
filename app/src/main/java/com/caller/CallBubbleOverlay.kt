package com.caller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating call pill shown over other apps while a call answered from the banner is running:
 * caller name, running timer and a red Hang up. Tap the pill = open the full call screen.
 * Can be dragged up/down. Needs "Display over other apps"; without it nothing is shown
 * (the timer is still in the call notification).
 */
object CallBubbleOverlay {
    private const val TAG = "CallBubbleOverlay"
    private var view: View? = null
    private var nameView: TextView? = null
    private var timerView: TextView? = null
    private var activeConnectedAtMillis: Long = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun isShowing(): Boolean = view != null

    /** "0:07", "12:34", "1:02:03". */
    private fun formatElapsed(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * @param connectedAtMillis wall-clock time the call connected (0 = dialing / ringing).
     * @param statusText optional status label when dialing/ringing (e.g. "Dialing...", "Ringing...").
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(
        context: Context,
        title: String,
        connectedAtMillis: Long,
        statusText: String? = null,
        onOpen: () -> Unit,
        onHangUp: () -> Unit
    ) {
        if (!canShow(context)) {
            Log.w(TAG, "skip: overlay permission not granted")
            return
        }

        val isConnected = connectedAtMillis > 0L
        val fallbackStatus = when {
            statusText?.equals("RINGING", ignoreCase = true) == true -> "Ringing..."
            statusText?.equals("ACTIVE", ignoreCase = true) == true -> ""
            statusText?.equals("DIALING", ignoreCase = true) == true -> "Dialing..."
            statusText?.equals("CONNECTING", ignoreCase = true) == true -> "Connecting..."
            statusText.orEmpty().isNotBlank() -> statusText
            else -> "Dialing..."
        }

        val startElapsed = if (isConnected) {
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connectedAtMillis).coerceAtLeast(0L)
        } else 0L

        // If overlay is already attached and visible, update views in-place without removing or resetting drag position!
        val currentView = view
        val currentNameView = nameView
        val currentTimerView = timerView
        if (currentView != null && currentNameView != null && currentTimerView != null) {
            currentNameView.text = title
            handler.removeCallbacksAndMessages(null)
            if (isConnected) {
                activeConnectedAtMillis = connectedAtMillis
                val ticker = object : Runnable {
                    override fun run() {
                        if (view == null) return
                        val currentElapsed = SystemClock.elapsedRealtime() - startElapsed
                        currentTimerView.text = formatElapsed(currentElapsed)
                        handler.postDelayed(this, 1000L)
                    }
                }
                handler.post(ticker)
            } else {
                currentTimerView.text = fallbackStatus
            }
            return
        }

        hide(context)
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), appContext.resources.displayMetrics).toInt()

        val pill = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#E6202124"))
            }
            elevation = dp(6).toFloat()
        }
        val name = TextView(appContext).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(150)
        }

        val timer = TextView(appContext).apply {
            setTextColor(Color.parseColor("#7CE38B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), 0, dp(12), 0)
            text = if (isConnected) formatElapsed(SystemClock.elapsedRealtime() - startElapsed) else fallbackStatus
        }

        activeConnectedAtMillis = connectedAtMillis
        nameView = name
        timerView = timer

        val ticker = object : Runnable {
            override fun run() {
                if (view == null) return
                if (activeConnectedAtMillis > 0L) {
                    val currentStartElapsed = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - activeConnectedAtMillis).coerceAtLeast(0L)
                    timer.text = formatElapsed(SystemClock.elapsedRealtime() - currentStartElapsed)
                    handler.postDelayed(this, 1000L)
                } else {
                    timer.text = fallbackStatus
                }
            }
        }
        handler.removeCallbacksAndMessages(null)
        if (isConnected) {
            handler.post(ticker)
        }

        val hangUp = android.widget.ImageView(appContext).apply {
            setImageResource(R.drawable.ic_call_end_white)
            contentDescription = "Hang up"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E5484D"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener {
                hide(appContext)
                onHangUp()
            }
        }
        pill.addView(name)
        pill.addView(timer)
        pill.addView(hangUp)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }

        var downY = 0f
        var startY = 0
        var dragged = false
        pill.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY; startY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        params.y = (startY + dy.toInt()).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        hide(appContext)
                        onOpen()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(pill, params)
            view = pill
        } catch (e: Exception) {
            Log.e(TAG, "show failed: ${e.message}")
        }
    }

    fun hide(context: Context) {
        handler.removeCallbacksAndMessages(null)
        val current = view ?: return
        view = null
        nameView = null
        timerView = null
        activeConnectedAtMillis = 0L
        runCatching {
            (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(current)
        }
    }
}
