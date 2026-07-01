package com.inbyte.imagedescriber.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary             = Color(0xFF363F22),   // deep olive green (app icon base)
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFA6B884),   // mid olive
    onPrimaryContainer  = Color(0xFF13190A),
    secondary           = Color(0xFF4C3216),   // deep wood brown
    secondaryContainer  = Color(0xFFA6B884),   // olive (selected-tab indicator)
    onSecondaryContainer = Color(0xFF13190A),
    tertiary            = Color(0xFF63250F),   // deep rust
    tertiaryContainer   = Color(0xFFE8C4AE),   // light rust (user avatar chip)
    onTertiaryContainer = Color(0xFF3D1105),
    background          = Color(0xFFF7F8EE),   // warm off-white, olive-tinted
    onBackground        = Color(0xFF1B1F12),
    surface             = Color(0xFFF7F8EE),
    onSurface           = Color(0xFF1B1F12),
    surfaceVariant      = Color(0xFFE6E9D6),   // olive-tinted card/nav-bar surface
    onSurfaceVariant    = Color(0xFF454E38),
    outline             = Color(0xFF7A8062),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFF1F2E7),
    surfaceContainer        = Color(0xFFEBEDDE),   // NavigationBar container
    surfaceContainerHigh    = Color(0xFFE5E7D6),
    surfaceContainerHighest = Color(0xFFDFE2CD),
    surfaceBright           = Color(0xFFF7F8EE),
    surfaceDim              = Color(0xFFD8DBC5),
)

private val DarkColors = darkColorScheme(
    primary             = Color(0xFF7C8D59),   // muted olive
    onPrimary           = Color(0xFF1B2000),
    primaryContainer    = Color(0xFF262C16),   // deep olive container
    onPrimaryContainer  = Color(0xFFD4E0B4),
    secondary           = Color(0xFF9A7B4F),   // muted tan brown
    secondaryContainer  = Color(0xFF404A28),   // olive (selected-tab indicator)
    onSecondaryContainer = Color(0xFFD4E0B4),
    tertiary            = Color(0xFFB3603F),   // muted rust
    tertiaryContainer   = Color(0xFF4A2415),   // deep rust (user avatar chip)
    onTertiaryContainer = Color(0xFFF0D3C0),
    background          = Color(0xFF14170D),   // near-black, olive-tinted
    onBackground        = Color(0xFFE4E9D6),
    surface             = Color(0xFF14170D),
    onSurface           = Color(0xFFE4E9D6),
    surfaceVariant      = Color(0xFF262B18),   // olive-tinted card/nav-bar surface
    onSurfaceVariant    = Color(0xFFC4CCAF),
    outline             = Color(0xFF8B9270),
    surfaceContainerLowest  = Color(0xFF0D0F08),
    surfaceContainerLow     = Color(0xFF1B1F12),
    surfaceContainer        = Color(0xFF202415),   // NavigationBar container
    surfaceContainerHigh    = Color(0xFF2A2F1C),
    surfaceContainerHighest = Color(0xFF353B24),
    surfaceBright           = Color(0xFF3A4126),
    surfaceDim              = Color(0xFF14170D),
)

@Composable
fun InByteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color intentionally disabled: the app always uses its own olive-green
    // palette (matching the app icon) rather than colors derived from the device wallpaper.
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content,
    )
}
