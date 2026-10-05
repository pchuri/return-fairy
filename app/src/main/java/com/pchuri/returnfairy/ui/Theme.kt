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

object StatusColors {
    val urgent = Color(0xFFD62828)
    val approaching = Color(0xFFF77F00)
    val waiting = Color(0xFF3557A5)
    val waitingDark = Color(0xFF8FAEE8)
    val urgentDark = Color(0xFFFF7B7B)
    val approachingDark = Color(0xFFFFB25C)
}

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
