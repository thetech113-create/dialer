package com.caller

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import kotlin.math.abs

object CallBubbleOverlay {
    private const val TAG = "CallBubbleOverlay"
    private var view: View? = null
    private var pillContainer: LinearLayout? = null
    private var nameView: TextView? = null
    private var timerView: TextView? = null
    private var windowManagerRef: WindowManager? = null

    private var activeConnectedAtMillis: Long = 0L
    private var activeFallbackStatus: String = "Dialing..."
    private var receiverRegistered = false

    @Volatile
    var isCallActivityVisible: Boolean = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun isShowing(): Boolean = view != null

    private fun formatElapsed(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun resolveOverlayWindowContext(fallbackContext: Context): Context {
        // On Xiaomi MIUI/HyperOS (Redmi 13C), attaching the overlay via the active foreground
        // InCallService context prevents background window manager throttling.
        return MyInCallService.getInstance() ?: fallbackContext.applicationContext
    }

    private fun syncThemeFromUnifiedSystem(context: Context) {
        val palette = UnifiedUiSystem.palette(context)
        pillContainer?.background = UnifiedUiSystem.createPillBackground(context, palette)
        nameView?.setTextColor(palette.textPrimaryArgb)
        timerView?.setTextColor(palette.timerAccentArgb)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val currentTimerView = timerView ?: return
            if (view == null) return
            if (activeConnectedAtMillis > 0L) {
                val currentStartElapsed = SystemClock.elapsedRealtime() -
                    (System.currentTimeMillis() - activeConnectedAtMillis).coerceAtLeast(0L)
                currentTimerView.text = formatElapsed(SystemClock.elapsedRealtime() - currentStartElapsed)
                handler.postDelayed(this, 1000L)
            } else {
                currentTimerView.text = activeFallbackStatus
            }
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            when (intent?.action) {
                "FINISH_CALL_ACTIVITY" -> {
                    hide(ctx)
                }
                "CALL_STATE_UPDATED" -> {
                    val isInCall = intent.getBooleanExtra("IS_IN_CALL", false)
                    if (!isInCall) {
                        hide(ctx)
                        return
                    }
                    if (isCallActivityVisible) {
                        return
                    }
                    val primaryState = intent.getStringExtra("PRIMARY_STATE") ?: "IDLE"
                    val primaryName = intent.getStringExtra("PRIMARY_NAME").orEmpty()
                    val primaryNumber = intent.getStringExtra("PRIMARY_NUMBER").orEmpty()
                    val title = primaryName.takeIf { it.isNotBlank() && it != "Unknown" }
                        ?: formatPhoneNumberForDisplay(primaryNumber).ifBlank { "Ongoing call" }
                    val snapshotConnectMs = MyInCallService.peekLastCallUiSnapshot()?.connectTimeMillis ?: 0L

                    if (view != null) {
                        updateVisibleBubble(ctx, title, snapshotConnectMs, primaryState)
                    }
                }
            }
        }
    }

    private fun registerReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        runCatching {
            val appCtx = context.applicationContext
            val filter = IntentFilter().apply {
                addAction("CALL_STATE_UPDATED")
                addAction("FINISH_CALL_ACTIVITY")
            }
            ContextCompat.registerReceiver(appCtx, callStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    private fun unregisterReceiverIfNeeded(context: Context) {
        if (!receiverRegistered) return
        runCatching {
            context.applicationContext.unregisterReceiver(callStateReceiver)
        }
        receiverRegistered = false
    }

    /**
     * Called directly from CallActivity when the user presses Home, Back, or switches apps.
     * Bypasses UsageStatsManager delays so the bubble always appears on Xiaomi Redmi 13C.
     */
    fun showFromCallActivityExit(context: Context) {
        isCallActivityVisible = false
        val snapshot = MyInCallService.peekLastCallUiSnapshot()
        val rawTitle = snapshot?.primaryName?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: snapshot?.primaryNumber?.let { formatPhoneNumberForDisplay(it) }
            ?: "Ongoing call"
        val connectMs = snapshot?.connectTimeMillis ?: 0L
        val stateText = snapshot?.primaryState ?: "ACTIVE"

        show(
            context = context,
            title = rawTitle,
            connectedAtMillis = connectMs,
            statusText = stateText,
            onOpen = {
                val openIntent = Intent(context.applicationContext, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                runCatching { context.applicationContext.startActivity(openIntent) }
            },
            onHangUp = {
                MyInCallService.getInstance()?.disconnectCall()
            }
        )
    }

    private fun resolveStateAndConnectTime(
        connectedAtMillis: Long,
        statusText: String?
    ): Pair<Long, String> {
        val snapshotConnectMs = MyInCallService.peekLastCallUiSnapshot()?.connectTimeMillis ?: 0L
        val isStateActive = statusText?.equals("ACTIVE", ignoreCase = true) == true ||
            statusText?.equals("CONFERENCE", ignoreCase = true) == true

        val effectiveConnectedAt = when {
            connectedAtMillis > 0L -> connectedAtMillis
            snapshotConnectMs > 0L -> snapshotConnectMs
            isStateActive && activeConnectedAtMillis > 0L -> activeConnectedAtMillis
            isStateActive -> System.currentTimeMillis()
            else -> 0L
        }

        val fallback = when {
            statusText?.equals("RINGING", ignoreCase = true) == true -> "Ringing..."
            statusText?.equals("HOLDING", ignoreCase = true) == true -> "On hold"
            isStateActive -> ""
            statusText?.equals("DIALING", ignoreCase = true) == true -> "Dialing..."
            statusText?.equals("CONNECTING", ignoreCase = true) == true -> "Connecting..."
            statusText.orEmpty().isNotBlank() -> statusText.orEmpty()
            else -> "Dialing..."
        }
        return effectiveConnectedAt to fallback
    }

    private fun updateVisibleBubble(
        context: Context,
        title: String,
        connectedAtMillis: Long,
        statusText: String?
    ) {
        handler.post {
            val currentNameView = nameView ?: return@post
            val currentTimerView = timerView ?: return@post
            val (resolvedConnectMs, fallback) = resolveStateAndConnectTime(connectedAtMillis, statusText)

            syncThemeFromUnifiedSystem(context.applicationContext)
            currentNameView.text = title
            activeFallbackStatus = fallback
            activeConnectedAtMillis = resolvedConnectMs

            handler.removeCallbacks(ticker)
            if (resolvedConnectMs > 0L) {
                handler.post(ticker)
            } else {
                currentTimerView.text = activeFallbackStatus
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        context: Context,
        title: String,
        connectedAtMillis: Long,
        statusText: String? = null,
        onOpen: () -> Unit,
        onHangUp: () -> Unit
    ) {
        if (isCallActivityVisible) return
        if (!canShow(context)) {
            Log.w(TAG, "skip: overlay permission not granted")
            return
        }

        handler.post {
            if (isCallActivityVisible) return@post

            val overlayContext = resolveOverlayWindowContext(context)
            val appContext = context.applicationContext
            registerReceiverIfNeeded(appContext)

            if (view != null && nameView != null && timerView != null) {
                updateVisibleBubble(appContext, title, connectedAtMillis, statusText)
                return@post
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManagerRef = wm
            val palette = UnifiedUiSystem.palette(appContext)
            fun dp(v: Int) = UnifiedUiSystem.dp(appContext, v)

            val (resolvedConnectMs, fallback) = resolveStateAndConnectTime(connectedAtMillis, statusText)
            activeConnectedAtMillis = resolvedConnectMs
            activeFallbackStatus = fallback

            // Compact, zero-shadow transparent pill so minimal screen space is occupied
            val pill = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(5), dp(4))
                background = UnifiedUiSystem.createPillBackground(appContext, palette)
                elevation = 0f
            }
            pillContainer = pill

            val name = TextView(appContext).apply {
                text = title
                setTextColor(palette.textPrimaryArgb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(105)
            }

            val startElapsed = if (resolvedConnectMs > 0L) {
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - resolvedConnectMs).coerceAtLeast(0L)
            } else 0L

            val timer = TextView(appContext).apply {
                setTextColor(palette.timerAccentArgb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(6), 0, dp(6), 0)
                text = if (resolvedConnectMs > 0L) {
                    formatElapsed(SystemClock.elapsedRealtime() - startElapsed)
                } else {
                    activeFallbackStatus
                }
            }

            nameView = name
            timerView = timer

            val hangUp = android.widget.ImageView(appContext).apply {
                setImageResource(R.drawable.ic_call_end_white)
                contentDescription = "Hang up"
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.hangUpRedArgb)
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = UnifiedUiSystem.getBubbleDefaultTopYPx(appContext, wm)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragged = false

            pill.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                        if (dragged) {
                            params.x = startX + dx.toInt()
                            params.y = UnifiedUiSystem.clampBubbleYPx(
                                appContext,
                                wm,
                                startY + dy.toInt(),
                                v.height.coerceAtLeast(dp(36))
                            )
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
                handler.removeCallbacks(ticker)
                if (resolvedConnectMs > 0L) {
                    handler.post(ticker)
                }
            } catch (e: Exception) {
                Log.e(TAG, "show failed on WindowManager: ${e.message}", e)
            }
        }
    }

    fun hide(context: Context) {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiverIfNeeded(context)
        val current = view ?: return
        val wm = windowManagerRef
            ?: (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        view = null
        pillContainer = null
        nameView = null
        timerView = null
        windowManagerRef = null
        activeConnectedAtMillis = 0L
        runCatching {
            wm?.removeViewImmediate(current)
        }
    }
}
