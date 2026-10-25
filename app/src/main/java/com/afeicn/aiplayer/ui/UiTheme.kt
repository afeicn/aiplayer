package com.afeicn.aiplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 浅色主题的完整 token 表。对比度均按 WCAG 2.1 计算，正文 ≥ 4.5:1、次要文字 ≥ 4.5:1、
 * 图标/分割线按非文字要求 ≥ 3:1；具体数值由 ThemeContrastTest 在单测里固定住。
 */
internal val LightPalette = AppPalette(
    primary = Color(0xFF6B3FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DEFE),
    onPrimaryContainer = Color(0xFF14182B),
    inversePrimary = Color(0xFFC9B8FF),
    secondary = Color(0xFF555C74),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7F2),
    onSecondaryContainer = Color(0xFF171B2D),
    tertiary = Color(0xFF5D5F7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E1F5),
    onTertiaryContainer = Color(0xFF1A1B35),
    background = Color(0xFFF5F5FD),
    onBackground = Color(0xFF14182B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B2D),
    surfaceVariant = Color(0xFFEDEEF8),
    onSurfaceVariant = Color(0xFF5C6278),
    surfaceTint = Color(0xFF6B3FD8),
    inverseSurface = Color(0xFF2E3145),
    inverseOnSurface = Color(0xFFF2F3FA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF80869E),
    outlineVariant = Color(0xFFDDE0EF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEDFEA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFF),
    surfaceContainer = Color(0xFFF2F2FA),
    surfaceContainerHigh = Color(0xFFECEDF7),
    surfaceContainerHighest = Color(0xFFE6E8F4),
    success = Color(0xFF177A55),
    rankFirst = Color(0xFFC22B30),
    rankSecond = Color(0xFFA95400),
    rankThird = Color(0xFF8A6100),
)

/** 深色主题：主色提亮、容器压暗；文字/次要文字/分割线逐级补齐，避免落到 M3 默认紫色。 */
internal val DarkPalette = AppPalette(
    primary = Color(0xFFB9A2FF),
    onPrimary = Color(0xFF241A45),
    primaryContainer = Color(0xFF3A2D5C),
    onPrimaryContainer = Color(0xFFE9DEFF),
    inversePrimary = Color(0xFF6B3FD8),
    secondary = Color(0xFFC6CBE0),
    onSecondary = Color(0xFF1A2034),
    secondaryContainer = Color(0xFF333C57),
    onSecondaryContainer = Color(0xFFE8EAFF),
    tertiary = Color(0xFFC5C4DD),
    onTertiary = Color(0xFF2F2F49),
    tertiaryContainer = Color(0xFF45455F),
    onTertiaryContainer = Color(0xFFE2E1F5),
    background = Color(0xFF0C1325),
    onBackground = Color(0xFFF4F3FF),
    surface = Color(0xFF182039),
    onSurface = Color(0xFFF4F3FF),
    surfaceVariant = Color(0xFF27304B),
    onSurfaceVariant = Color(0xFFBFC6DB),
    surfaceTint = Color(0xFFB9A2FF),
    inverseSurface = Color(0xFFE6E8F4),
    inverseOnSurface = Color(0xFF2A2F42),
    error = Color(0xFFFF7E87),
    onError = Color(0xFF3B0910),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B95B2),
    outlineVariant = Color(0xFF3D4762),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333D57),
    surfaceDim = Color(0xFF0C1325),
    surfaceContainerLowest = Color(0xFF080E1D),
    surfaceContainerLow = Color(0xFF121A2F),
    surfaceContainer = Color(0xFF1B2338),
    surfaceContainerHigh = Color(0xFF222B43),
    surfaceContainerHighest = Color(0xFF29334D),
    success = Color(0xFF5FD3A6),
    rankFirst = Color(0xFFFF8085),
    rankSecond = Color(0xFFFFA65C),
    rankThird = Color(0xFFFFD166),
)

/** Material 之外的一小组语义色（成功态、热搜名次），同样分深浅两套，交给 LocalAppColors 下发。 */
@Immutable
data class AppColors(
    val success: Color,
    val rankFirst: Color,
    val rankSecond: Color,
    val rankThird: Color,
)

internal val LightAppColors = AppColors(
    success = LightPalette.success,
    rankFirst = LightPalette.rankFirst,
    rankSecond = LightPalette.rankSecond,
    rankThird = LightPalette.rankThird,
)

internal val DarkAppColors = AppColors(
    success = DarkPalette.success,
    rankFirst = DarkPalette.rankFirst,
    rankSecond = DarkPalette.rankSecond,
    rankThird = DarkPalette.rankThird,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

@Composable
fun AIPlayerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = palette.toColorScheme(dark)
    // 关键：Material3 的 MaterialTheme 不设置 LocalContentColor，默认是黑色。
    // 之前根节点没有 Surface，深色模式下所有没显式写颜色的文字都会黑底黑字；
    // 在主题根部把默认内容色绑定到 onBackground，页面内的 Surface 仍会各自覆盖成 onSurface/onPrimary。
    CompositionLocalProvider(
        LocalContentColor provides palette.onBackground,
        LocalAppColors provides if (dark) DarkAppColors else LightAppColors
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 主题 token 的纯数据形态：不依赖 MaterialTheme，单测可直接计算对比度。 */
@Immutable
internal data class AppPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val success: Color,
    val rankFirst: Color,
    val rankSecond: Color,
    val rankThird: Color,
)

private fun AppPalette.toColorScheme(dark: Boolean): ColorScheme {
    // 以对应明暗的 M3 默认方案为底（覆盖未列出的 fixed 系列），再把项目 token 全量拷贝进去。
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        outline = outline, outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright, surfaceDim = surfaceDim,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}
