package com.caller

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object UnifiedUiSystem {

    data class Palette(
        val isDark: Boolean,
        val bgTopArgb: Int,
        val bgMidArgb: Int,
        val bgBottomArgb: Int,
        val cardSurfaceArgb: Int,
        val pillSurfaceArgb: Int,
        val pillBorderArgb: Int,
        val textPrimaryArgb: Int,
        val textSecondaryArgb: Int,
        val timerAccentArgb: Int,
        val hangUpRedArgb: Int
    ) {
        val bgTop: Color get() = Color(bgTopArgb)
        val bgMid: Color get() = Color(bgMidArgb)
        val bgBottom: Color get() = Color(bgBottomArgb)
        val cardSurface: Color get() = Color(cardSurfaceArgb)
        val textPrimary: Color get() = Color(textPrimaryArgb)
        val textSecondary: Color get() = Color(textSecondaryArgb)
        val timerAccent: Color get() = Color(timerAccentArgb)
        val hangUpRed: Color get() = Color(hangUpRedArgb)

        val screenBackgroundBrush: Brush
            get() = Brush.verticalGradient(listOf(bgTop, bgMid, bgBottom))

        val radialGlowColor: Color
            get() = if (isDark) Color(0xFF1E293B).copy(alpha = 0.35f) else Color(0xFFCBD5E1).copy(alpha = 0.55f)

        val tileIdleBg: Color
            get() = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

        val tileActiveBg: Color
            get() = if (isDark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.18f)

        val tileDisabledBg: Color
            get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f)

        val tileDisabledText: Color
            get() = textPrimary.copy(alpha = 0.35f)
    }

    // Transparent glass pillSurfaceArgb (#59 = 35% alpha in dark, #66 = 40% alpha in light)
    private val DarkPalette = Palette(
        isDark = true,
        bgTopArgb = AndroidColor.parseColor("#080B11"),
        bgMidArgb = AndroidColor.parseColor("#10141E"),
        bgBottomArgb = AndroidColor.parseColor("#0A0D14"),
        cardSurfaceArgb = AndroidColor.parseColor("#161B26"),
        pillSurfaceArgb = AndroidColor.parseColor("#5910141E"),
        pillBorderArgb = AndroidColor.parseColor("#38FFFFFF"),
        textPrimaryArgb = AndroidColor.parseColor("#FFFFFF"),
        textSecondaryArgb = AndroidColor.parseColor("#D0D5DD"),
        timerAccentArgb = AndroidColor.parseColor("#7CE38B"),
        hangUpRedArgb = AndroidColor.parseColor("#CCFF3B30")
    )

    private val LightPalette = Palette(
        isDark = false,
        bgTopArgb = AndroidColor.parseColor("#F8FAFC"),
        bgMidArgb = AndroidColor.parseColor("#F1F5F9"),
        bgBottomArgb = AndroidColor.parseColor("#E2E8F0"),
        cardSurfaceArgb = AndroidColor.parseColor("#FFFFFF"),
        pillSurfaceArgb = AndroidColor.parseColor("#66FFFFFF"),
        pillBorderArgb = AndroidColor.parseColor("#30000000"),
        textPrimaryArgb = AndroidColor.parseColor("#0F172A"),
        textSecondaryArgb = AndroidColor.parseColor("#475569"),
        timerAccentArgb = AndroidColor.parseColor("#15803D"),
        hangUpRedArgb = AndroidColor.parseColor("#CCF03E3E")
    )

    fun isDarkMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(context: Context): Palette =
        if (isDarkMode(context)) DarkPalette else LightPalette

    @Composable
    fun rememberPalette(): Palette {
        val configuration = LocalConfiguration.current
        val context = LocalContext.current
        val isDark = remember(configuration.uiMode) { isDarkMode(context) }
        val palette = if (isDark) DarkPalette else LightPalette

        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (context as? Activity)?.window ?: return@SideEffect
                window.statusBarColor = AndroidColor.TRANSPARENT
                window.navigationBarColor = AndroidColor.TRANSPARENT
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !palette.isDark
                controller.isAppearanceLightNavigationBars = !palette.isDark
            }
        }
        return palette
    }

    fun dp(context: Context, valueDp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            valueDp.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun createPillBackground(context: Context, palette: Palette = palette(context)): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, 20).toFloat()
            setColor(palette.pillSurfaceArgb)
            setStroke(dp(context, 1).coerceAtLeast(1), palette.pillBorderArgb)
        }

    fun getSafeTopInsetPx(context: Context, wm: WindowManager? = null): Int {
        val windowManager = wm ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        var topInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    )
                topInset = insets.top
            }
        }
        if (topInset <= 0) {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) {
                topInset = context.resources.getDimensionPixelSize(resId)
            }
        }
        return topInset.coerceAtLeast(dp(context, 28))
    }

    fun getSafeBottomInsetPx(context: Context, wm: WindowManager? = null): Int {
        val windowManager = wm ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        var bottomInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            runCatching {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                bottomInset = insets.bottom
            }
        }
        if (bottomInset <= 0) {
            val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resId > 0) {
                bottomInset = context.resources.getDimensionPixelSize(resId)
            }
        }
        return bottomInset.coerceAtLeast(0)
    }

    fun getBubbleDefaultTopYPx(context: Context, wm: WindowManager): Int =
        getSafeTopInsetPx(context, wm) + dp(context, 6)

    fun clampBubbleYPx(context: Context, wm: WindowManager, requestedY: Int, pillHeightPx: Int): Int {
        val minTop = getBubbleDefaultTopYPx(context, wm)
        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.bounds.height() }
                .getOrDefault(context.resources.displayMetrics.heightPixels)
        } else {
            context.resources.displayMetrics.heightPixels
        }
        val maxBottom = (screenHeight - getSafeBottomInsetPx(context, wm) - pillHeightPx - dp(context, 12))
            .coerceAtLeast(minTop)
        return requestedY.coerceIn(minTop, maxBottom)
    }

    @Composable
    fun Modifier.safeNotificationShadePadding(): Modifier {
        val context = LocalContext.current
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val topDp = remember(configuration) {
            with(density) { getSafeTopInsetPx(context).toDp() }
        }
        val bottomDp = remember(configuration) {
            with(density) { getSafeBottomInsetPx(context).toDp() }
        }
        return this.padding(top = topDp, bottom = bottomDp)
    }
}
