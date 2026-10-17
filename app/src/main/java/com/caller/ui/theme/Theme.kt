@file:Suppress(
    "all", 
    "WARNINGS", 
    "unused", 
    "UNUSED_VARIABLE",
    "UnusedImport", 
    "RemoveRedundantQualifierName", 
    "NAME_SHADOWING", 
    "LiftReturnOrAssignment", 
    "RegExpSimplifiable",
    "UNNECESSARY_SAFE_CALL",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "UselessCallOnNotNull",
    "RedundantSafeCall",
    "DEPRECATION",
    "IfThenToIfEmpty",
    "ifEmpty {...}",
    "SpellCheckingInspection",
    "Typo"
)

package com.caller.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Samsung One UI colours (see OneUi.kt): grey page, white/near-black cards, blue accent.
private val DarkColorScheme = darkColorScheme(
    primary = OneUi.Blue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = OneUi.Dark.textSecondary,
    tertiary = OneUi.CallGreen,
    background = OneUi.Dark.background,
    onBackground = OneUi.Dark.textPrimary,
    surface = OneUi.Dark.card,
    onSurface = OneUi.Dark.textPrimary,
    surfaceVariant = OneUi.Dark.chip,
    onSurfaceVariant = OneUi.Dark.textSecondary,
    outline = OneUi.Dark.divider,
    error = OneUi.EndRed
)

private val LightColorScheme = lightColorScheme(
    primary = OneUi.Blue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = OneUi.Light.textSecondary,
    tertiary = OneUi.CallGreen,
    background = OneUi.Light.background,
    onBackground = OneUi.Light.textPrimary,
    surface = OneUi.Light.card,
    onSurface = OneUi.Light.textPrimary,
    surfaceVariant = OneUi.Light.chip,
    onSurfaceVariant = OneUi.Light.textSecondary,
    outline = OneUi.Light.divider,
    error = OneUi.EndRed
)

@Composable
fun CallerTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    // Off: wallpaper colours would override the One UI look.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // --- GLOBAL UI SCALER (Maintained for layout safety) ---
    val systemDensity = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    
    val autoScaledDensity = androidx.compose.runtime.remember(systemDensity, configuration) {
        val scaleFactor = if (configuration.screenWidthDp < 380) {
            configuration.screenWidthDp.toFloat() / 380f
        } else 1f
        
        androidx.compose.ui.unit.Density(
            density = systemDensity.density * scaleFactor,
            fontScale = 1f 
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides autoScaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}