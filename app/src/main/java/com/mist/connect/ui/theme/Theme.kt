package com.mist.connect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = MistBlue,
    onPrimary = Color.White,
    primaryContainer = MistBlueSoft,
    onPrimaryContainer = MistBlueDark,
    secondary = MistTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBEB),
    onSecondaryContainer = Color(0xFF2A4545),
    tertiary = MistAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E6D0),
    onTertiaryContainer = Color(0xFF5A3A12),
    background = PaperWhite,
    onBackground = InkCharcoal,
    surface = PaperSurface,
    onSurface = InkCharcoal,
    surfaceVariant = Color(0xFFEFEBE3),
    onSurfaceVariant = InkMuted,
    surfaceContainerHighest = Color(0xFFE8E4DB),
    outline = MistLine,
    outlineVariant = Color(0xFFD0CBC0),
    error = MistError,
    onError = Color.White,
    errorContainer = Color(0xFFF5D6D6),
    onErrorContainer = Color(0xFF5A1E1E)
)

private val DarkColorScheme = darkColorScheme(
    primary = MistBlueNight,
    onPrimary = Color(0xFF0F1A22),
    primaryContainer = MistBlueNightSoft,
    onPrimaryContainer = MistBlueSoft,
    secondary = MistTealNight,
    onSecondary = Color(0xFF0F1A1A),
    secondaryContainer = Color(0xFF2A3F3F),
    onSecondaryContainer = Color(0xFFD0E8E8),
    tertiary = Color(0xFFD4B07A),
    onTertiary = Color(0xFF2A1C08),
    tertiaryContainer = Color(0xFF4A3818),
    onTertiaryContainer = Color(0xFFF5E6D0),
    background = NightBg,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF2E3032),
    onSurfaceVariant = NightMuted,
    surfaceContainerHighest = Color(0xFF343638),
    outline = NightLine,
    outlineVariant = Color(0xFF4A4C4E),
    error = Color(0xFFE08080),
    onError = Color(0xFF3A1010),
    errorContainer = Color(0xFF5A2020),
    onErrorContainer = Color(0xFFF5D6D6)
)

@Composable
fun MistConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 关 dynamicColor：固定品牌雾蓝，不跟系统壁纸变紫
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
