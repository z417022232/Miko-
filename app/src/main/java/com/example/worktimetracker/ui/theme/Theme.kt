package com.example.worktimetracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 设计 token 体系（Apple HIG 取向）
 *
 * 分三层，禁止在页面里再写裸值：
 *   1. AppPalette —— 语义色（light / dark 两套），经 CompositionLocal 下发
 *   2. AppTypography / MaterialTheme.typography —— 字号与行高刻度
 *   3. AppShapes / AppSpacing / AppElevation —— 圆角随组件尺寸成比例、间距与层级
 *
 * 硬约束（HIG「什么时候不用」条款）：本 App 是数据密集的考勤工具，
 * 日历格子与统计表**不适用大留白**，一切间距以"不降低一屏信息量"为前提。
 */

// ---------------------------------------------------------------------------
// 1. 语义调色板
// ---------------------------------------------------------------------------

/**
 * 语义调色板。深色不是浅色的机械反转：强调色必须**提亮**才能在深底上保持对比度，
 * 卡片靠"面抬高"而非阴影表达层级（OLED 上阴影几乎不可见）。
 */
@Immutable
data class AppPalette(
    /** 主强调色：白班 / 可点击 / 选中 */
    val blue: Color,
    /** 次强调色：夜班 */
    val purple: Color,
    /** 正向：手动 / 请假 / 已确认 */
    val green: Color,
    /** 提醒：外出 / 调休上班 */
    val orange: Color,
    /** 风险：早退 / 到岗异常 / 法定节日 */
    val red: Color,
    /** 次级文字与图标 */
    val muted: Color,
    /** 分隔线 */
    val divider: Color,
    /** 卡片面 */
    val cardSurface: Color,
    /** 页面底色 */
    val pageBackground: Color,
    /** 正文主色 */
    val textPrimary: Color,
    /** 浮层遮罩 */
    val scrim: Color,
    val isDark: Boolean
)

private val LightPalette = AppPalette(
    blue = Color(0xFF2F6BFF),
    purple = Color(0xFF7257E7),
    green = Color(0xFF16A06A),
    orange = Color(0xFFE88922),
    red = Color(0xFFE34D59),
    muted = Color(0xFF7D889B),
    divider = Color(0xFFE9EDF3),
    cardSurface = Color.White,
    pageBackground = Color(0xFFF4F7FB),
    textPrimary = Color(0xFF172033),
    scrim = Color(0x66000000),
    isDark = false
)

private val DarkPalette = AppPalette(
    blue = Color(0xFF6C9BFF),
    purple = Color(0xFF9B87F5),
    green = Color(0xFF3FBF87),
    orange = Color(0xFFF0A34A),
    red = Color(0xFFF26874),
    muted = Color(0xFF97A1B2),
    divider = Color(0xFF262B36),
    cardSurface = Color(0xFF171A21),
    pageBackground = Color(0xFF0B0D12),
    textPrimary = Color(0xFFF2F4F8),
    scrim = Color(0x99000000),
    isDark = true
)

private val LocalAppPalette = staticCompositionLocalOf { LightPalette }

/** 统一入口：页面写 `AppTheme.colors.blue`，不写裸 `Color(0xFF...)`。 */
object AppTheme {
    val colors: AppPalette
        @Composable @ReadOnlyComposable get() = LocalAppPalette.current
}

// ---------------------------------------------------------------------------
// 2. 排版刻度
// ---------------------------------------------------------------------------

/** 中文字形方正、字面率大，行高需比拉丁文更松；大字号收紧字距才不会显散。 */
private val LineStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun cjk(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = LineStyle
)

val AppTypography = Typography(
    // 屏幕大标题：22sp / 字距 -0.2（HIG：大标题收紧）
    headlineSmall = cjk(22, 28, FontWeight.SemiBold, -0.2),
    // 次级标题 / 卡片标题
    titleMedium = cjk(16, 22, FontWeight.SemiBold, -0.1),
    titleSmall = cjk(15, 20, FontWeight.Medium),
    // 正文
    bodyLarge = cjk(15, 22, FontWeight.Normal),
    bodyMedium = cjk(14, 20, FontWeight.Normal),
    bodySmall = cjk(12, 17, FontWeight.Normal),
    // 标签与按钮
    labelLarge = cjk(13, 18, FontWeight.Medium),
    labelMedium = cjk(12, 16, FontWeight.Medium),
    labelSmall = cjk(11, 15, FontWeight.Medium)
)

// ---------------------------------------------------------------------------
// 3. 圆角 / 间距 / 层级
// ---------------------------------------------------------------------------

/**
 * 圆角随组件尺寸成比例（HIG：Corner 与组件尺寸成正比）——
 * 大容器大圆角、小组件小圆角，杜绝"大卡片 22dp、小徽标也 22dp"的失比例。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // 徽标、药丸、缩略块
    small = RoundedCornerShape(10.dp),       // 图标底托、输入框
    medium = RoundedCornerShape(14.dp),      // 按钮、内层容器
    large = RoundedCornerShape(18.dp),       // 卡片
    extraLarge = RoundedCornerShape(24.dp)   // 浮层、底部抽屉
)

object AppSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 26.dp

    /** 数据密集区（日历格子、统计表）专用：比常规更紧，避免降低扫描效率。 */
    val denseRow = 6.dp
    val denseGap = 4.dp
}

/**
 * 层级（HIG：阴影表达层级，但只在浮层）。
 * 正文卡片一律 [flat]——靠面色与底色差分区，而不是给每张卡加投影。
 */
object AppElevation {
    val flat = 0.dp
    val raised = 1.dp
    val floating = 3.dp
    val sheet = 8.dp
}

// ---------------------------------------------------------------------------
// 4. 主题入口
// ---------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = LightPalette.blue,
    primaryContainer = Color(0xFFE8F0FF),
    secondary = LightPalette.purple,
    secondaryContainer = Color(0xFFEEEAFE),
    background = LightPalette.pageBackground,
    surface = LightPalette.cardSurface,
    surfaceVariant = Color(0xFFF0F3F8),
    onPrimary = Color.White,
    onBackground = LightPalette.textPrimary,
    onSurface = LightPalette.textPrimary,
    onSurfaceVariant = Color(0xFF5B6579),
    outline = Color(0xFFDCE2EC),
    outlineVariant = LightPalette.divider,
    error = LightPalette.red
)

private val DarkColors = darkColorScheme(
    primary = DarkPalette.blue,
    primaryContainer = Color(0xFF1E2A44),
    secondary = DarkPalette.purple,
    secondaryContainer = Color(0xFF2A2440),
    background = DarkPalette.pageBackground,
    surface = DarkPalette.cardSurface,
    surfaceVariant = Color(0xFF1E222B),
    onPrimary = Color(0xFF0A1020),
    onBackground = DarkPalette.textPrimary,
    onSurface = DarkPalette.textPrimary,
    onSurfaceVariant = Color(0xFFB4BCC9),
    outline = Color(0xFF39414F),
    outlineVariant = DarkPalette.divider,
    error = DarkPalette.red
)

@Composable
fun WorkTimeTrackerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
