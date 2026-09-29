package com.ticksync.clock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 校准状态配色 */
val StatusOk = Color(0xFF00E676)
val StatusStale = Color(0xFFFFC107)
val StatusFailed = Color(0xFFFF5252)

/** 时间数字统一使用等宽字体，避免字符宽度差异导致跳动 */
val MonoFont = FontFamily.Monospace

/**
 * 固定深色配色。
 *
 * 不跟随系统亮色主题：抢票场景集中在弱光环境，深色是唯一合理选择；
 * 同时避免维护两套配色带来的对比度回归问题。
 */
private val TickSyncColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFF8CFFB8),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF003731),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAEE),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE6EAEE),
    surfaceVariant = Color(0xFF1F2530),
    onSurfaceVariant = Color(0xFFAFB6C0),
    outline = Color(0xFF39414D),
    outlineVariant = Color(0xFF262C36),
    error = Color(0xFFFF5252),
    onError = Color(0xFF3A0000)
)

private val TickSyncTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 56.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.2.sp)
    )
}

@Composable
fun TickSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TickSyncColorScheme,
        typography = TickSyncTypography,
        content = content
    )
}
