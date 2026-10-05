package com.pchuri.returnfairy.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C6E49),
    secondary = Color(0xFF4C956C),
    tertiary = Color(0xFF1D3557),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BC896),
    secondary = Color(0xFF9CCFB0),
    tertiary = Color(0xFFA8DADC),
)

/**
 * Dashboard palette, the same as the `songpa --html` card view and the iPhone widget so every
 * view of the data reads alike. Light colors are darkened to stay readable on white.
 */
data class DashColors(
    val bg: Color, val card: Color, val border: Color, val text: Color, val sub: Color,
    val chip: Color, val chipCount: Color, val badgeBg: Color,
    val green: Color, val red: Color, val orange: Color, val sky: Color, val yellow: Color, val yellowBg: Color,
    val tintGreen: Color, val tintSky: Color, val tintRed: Color, val tintRedBorder: Color,
)

private val LightDash = DashColors(
    bg = Color(0xFFF2F2F7), card = Color.White, border = Color(0xFFE5E5EA), text = Color.Black, sub = Color(0xFF6C6C70),
    chip = Color(0xFFE5E5EA), chipCount = Color(0x14000000), badgeBg = Color(0x0F000000),
    green = Color(0xFF248A3D), red = Color(0xFFD70015), orange = Color(0xFFC93400), sky = Color(0xFF0071A4),
    yellow = Color(0xFF9A6700), yellowBg = Color(0xFFFFD60A),
    tintGreen = Color(0x1F248A3D), tintSky = Color(0x1F0071A4), tintRed = Color(0x14D70015), tintRedBorder = Color(0x4DD70015),
)

private val DarkDash = DashColors(
    bg = Color.Black, card = Color(0xFF1C1C1E), border = Color(0xFF2C2C2E), text = Color.White, sub = Color(0xFF8E8E93),
    chip = Color(0xFF2C2C2E), chipCount = Color(0x33FFFFFF), badgeBg = Color(0x1FFFFFFF),
    green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A), sky = Color(0xFF64D2FF),
    yellow = Color(0xFFFFD60A), yellowBg = Color(0xFFFFD60A),
    tintGreen = Color(0x2E30D158), tintSky = Color(0x2E64D2FF), tintRed = Color(0x26FF453A), tintRedBorder = Color(0x59FF453A),
)

@Composable
fun dashColors(): DashColors = if (isSystemInDarkTheme()) DarkDash else LightDash

@Composable
fun ReturnFairyTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
