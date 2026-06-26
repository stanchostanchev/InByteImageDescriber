package com.inbyte.imagedescriber.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary          = Color(0xFF1B6EF3),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    secondary        = Color(0xFF4A6FA5),
    tertiary         = Color(0xFF6750A4),
)

private val DarkColors = darkColorScheme(
    primary          = Color(0xFF9FBFFF),
    onPrimary        = Color(0xFF003063),
    primaryContainer = Color(0xFF0E4FAB),
    secondary        = Color(0xFF9FBFFF),
    tertiary         = Color(0xFFCFBCFF),
)

@Composable
fun InByteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else      -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content,
    )
}
