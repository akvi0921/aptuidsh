package com.aptuidsh.kui.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** DeepSeek 品牌主色。 */
val DeepSeekBlue = Color(0xFF4D6BFE)

/** 连接成功语义绿。 */
val SuccessGreen = Color(0xFF2E9E5B)

/** 错误语义红。 */
val ErrorRed = Color(0xFFE5484D)

/**
 * 当前是否暗色(由 APTUIDSHTheme 的 darkTheme 提供)。
 * 各组件颜色判断统一用本 CompositionLocal,使「外观」设置(浅色/深色/跟随系统)真正生效。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = DeepSeekBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6FF),
    onPrimaryContainer = Color(0xFF1A2E8F),
    secondary = Color(0xFF5B6478),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1D26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D26),
    surfaceVariant = Color(0xFFEEF0F5),
    onSurfaceVariant = Color(0xFF5B6478),
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA2FF),
    onPrimary = Color(0xFF16204F),
    primaryContainer = Color(0xFF2A3AA0),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFFA6ADBF),
    background = Color(0xFF101218),
    onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF161922),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF222634),
    onSurfaceVariant = Color(0xFFA6ADBF),
    error = Color(0xFFFF7A80),
)

/** APTUIDSH 主题:亮/暗跟随系统(或外观设置)。 */
@Composable
fun APTUIDSHTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
