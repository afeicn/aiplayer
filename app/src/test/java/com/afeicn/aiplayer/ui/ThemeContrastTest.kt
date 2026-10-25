package com.afeicn.aiplayer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

/**
 * 用代码锁定两套主题的 WCAG 2.1 对比度：正文/按钮文字 ≥4.5:1，次要文字 ≥4.5:1（高于 3:1 底线），
 * 边框/图标等非文字元素 ≥3:1。数据直接取自 Theme 的 token，改配色后这里会先红。
 */
class ThemeContrastTest {

    private fun channel(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(c: Color): Float =
        0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** 半透明前景压在背景上的实际颜色（用于模拟浮层/热词条的混合底）。 */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            fg.red * a + bg.red * (1 - a),
            fg.green * a + bg.green * (1 - a),
            fg.blue * a + bg.blue * (1 - a),
            1f
        )
    }

    private fun assertPair(name: String, fg: Color, bg: Color, min: Float = 4.5f) {
        val ratio = contrast(fg, bg)
        assertTrue("$name 对比度 ${"%.2f".format(ratio)}:1，低于要求 $min:1", ratio >= min)
    }

    private fun assertPalette(tag: String, p: AppPalette) {
        val pairs = listOf(
            "onBackground/background" to (p.onBackground to p.background),
            "onSurface/surface" to (p.onSurface to p.surface),
            "onSurface/surfaceVariant" to (p.onSurface to p.surfaceVariant),
            "onSurface/surfaceContainerHigh" to (p.onSurface to p.surfaceContainerHigh),
            "onSurfaceVariant/background" to (p.onSurfaceVariant to p.background),
            "onSurfaceVariant/surface" to (p.onSurfaceVariant to p.surface),
            "onSurfaceVariant/surfaceVariant" to (p.onSurfaceVariant to p.surfaceVariant),
            "onSurfaceVariant/surfaceContainerHigh" to (p.onSurfaceVariant to p.surfaceContainerHigh),
            "primary/background" to (p.primary to p.background),
            "primary/surface" to (p.primary to p.surface),
            "primary/surfaceVariant" to (p.primary to p.surfaceVariant),
            // 对话框底色是 surfaceContainerHigh，按钮文字正好压在上面。
            "primary/surfaceContainerHigh" to (p.primary to p.surfaceContainerHigh),
            "primary/primaryContainer" to (p.primary to p.primaryContainer),
            "onPrimary/primary" to (p.onPrimary to p.primary),
            "onPrimaryContainer/primaryContainer" to (p.onPrimaryContainer to p.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (p.onSecondaryContainer to p.secondaryContainer),
            "error/background" to (p.error to p.background),
            "error/surface" to (p.error to p.surface),
            "error/surfaceVariant" to (p.error to p.surfaceVariant),
            "onError/error" to (p.onError to p.error),
            "onErrorContainer/errorContainer" to (p.onErrorContainer to p.errorContainer),
            "success/background" to (p.success to p.background),
            "success/surface" to (p.success to p.surface),
            "success/surfaceVariant" to (p.success to p.surfaceVariant),
        )
        pairs.forEach { (name, pair) -> assertPair("$tag $name", pair.first, pair.second) }
        // 输入框/描边属非文字元素：与相邻底色 ≥3:1。
        assertPair("$tag outline/surface", p.outline, p.surface, 3f)
        assertPair("$tag outline/background", p.outline, p.background, 3f)
    }

    @Test
    fun 浅色主题所有文字与背景达到AA() = assertPalette("浅色", LightPalette)

    @Test
    fun 深色主题所有文字与背景达到AA() = assertPalette("深色", DarkPalette)

    @Test
    fun 热搜名次色在混合底色上达到AA() {
        listOf("浅色" to LightPalette, "深色" to DarkPalette).forEach { (tag, p) ->
            // 热词条底色是 surfaceVariant 以 55% 叠在 background 上。
            val blended = over(p.surfaceVariant.copy(alpha = 0.55f), p.background)
            assertPair("$tag 第一名/热词底", p.rankFirst, blended)
            assertPair("$tag 第二名/热词底", p.rankSecond, blended)
            assertPair("$tag 第三名/热词底", p.rankThird, blended)
            // 选中胶囊等实底场景也要成立。
            assertPair("$tag 第一名/surfaceVariant", p.rankFirst, p.surfaceVariant)
            assertPair("$tag 第二名/surfaceVariant", p.rankSecond, p.surfaceVariant)
            assertPair("$tag 第三名/surfaceVariant", p.rankThird, p.surfaceVariant)
        }
    }

    @Test
    fun 播放页浮层白字压最亮视频帧仍达到AA() {
        val white = Color.White
        // 最坏情况：视频是纯白帧，浮层 70% 黑 / 控件栏上下渐变压上去之后的实际底色。
        val scrimBg = over(MediaOverlayColors.Scrim, white)
        val topBar = over(MediaOverlayColors.ControlGradient.first(), white)
        val bottomBar = over(MediaOverlayColors.ControlGradient.last(), white)
        assertPair("白字/62%黑浮层", MediaOverlayColors.TextPrimary, scrimBg)
        assertPair("次要白字/62%黑浮层", over(MediaOverlayColors.TextSecondary, scrimBg), scrimBg)
        assertPair("白字/顶部渐变", MediaOverlayColors.TextPrimary, topBar)
        assertPair("白字/底部渐变", MediaOverlayColors.TextPrimary, bottomBar)
        assertPair("次要白字/顶部渐变", over(MediaOverlayColors.TextSecondary, topBar), topBar)
        // 图标与进度条是非文字元素，3:1 即可。
        val circle = over(MediaOverlayColors.ControlCircle, white)
        assertPair("播放图标/圆底", MediaOverlayColors.TextPrimary, circle, 3f)
        assertPair("进度强调色/浮层底", MediaOverlayColors.Accent, scrimBg, 3f)
    }
}
