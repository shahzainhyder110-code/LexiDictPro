package com.lexidict.pro.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ═══════════════════════════════════════════════════════════════
//  CUSTOM COLOR SCHEME — Deep Ink & Amber
// ═══════════════════════════════════════════════════════════════

private val LexiDarkColorScheme = darkColorScheme(
    primary          = Color(0xFF8AB4F8),  // Soft cornflower blue
    onPrimary        = Color(0xFF0D2B6E),
    primaryContainer = Color(0xFF1A3A8A),
    onPrimaryContainer = Color(0xFFD0E4FF),

    secondary        = Color(0xFFFFB74D),  // Warm amber
    onSecondary      = Color(0xFF3D2000),
    secondaryContainer = Color(0xFF562E00),
    onSecondaryContainer = Color(0xFFFFDDB3),

    tertiary         = Color(0xFF80CBC4),  // Teal
    onTertiary       = Color(0xFF003734),
    tertiaryContainer = Color(0xFF00504B),
    onTertiaryContainer = Color(0xFFB2DFDB),

    background       = Color(0xFF0F0F14),  // Near-black
    surface          = Color(0xFF1A1A22),
    surfaceVariant   = Color(0xFF252532),
    onSurface        = Color(0xFFE8E8F0),
    onSurfaceVariant = Color(0xFF9E9EAE),

    outline          = Color(0xFF3D3D50),
    error            = Color(0xFFFF6B6B)
)

private val LexiLightColorScheme = lightColorScheme(
    primary          = Color(0xFF1A56C4),  // Deep royal blue
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF0A2A6A),

    secondary        = Color(0xFFE65100),  // Deep orange-amber
    onSecondary      = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF4A1500),

    tertiary         = Color(0xFF00695C),  // Deep teal
    onTertiary       = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF003830),

    background       = Color(0xFFF8F8FC),
    surface          = Color(0xFFFFFFFF),
    surfaceVariant   = Color(0xFFF0F0F8),
    onSurface        = Color(0xFF1A1A2E),
    onSurfaceVariant = Color(0xFF606070),

    outline          = Color(0xFFB0B0C0),
    error            = Color(0xFFD32F2F)
)

// ═══════════════════════════════════════════════════════════════
//  THEME COMPOSABLE
// ═══════════════════════════════════════════════════════════════

@Composable
fun LexiDictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color (Android 12+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme  -> LexiDarkColorScheme
        else       -> LexiLightColorScheme
    }

    // Status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LexiTypography,
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════
//  TYPOGRAPHY — Serif display + clean body
// ═══════════════════════════════════════════════════════════════

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Using system serif for the literary feel, clean sans for body
// In production: add custom fonts to res/font/ and reference here
val LexiTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
