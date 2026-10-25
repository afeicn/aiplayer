package com.afeicn.aiplayer.ui

import androidx.compose.ui.graphics.Color

/**
 * 播放页的固定色板：整页永远压在黑色视频画布上，不随应用深浅主题切换。
 * 数值按「浮层压在最亮（纯白）视频帧上」的最坏情况取值，保证白字仍有 WCAG AA 对比度；
 * 需要跟随主题的页面元素（弹窗、设置、列表）一律走 MaterialTheme.colorScheme，不用这里的颜色。
 */
internal object MediaOverlayColors {
    /** 视频画布：无画面时也是黑底，两套主题一致。 */
    val Canvas = Color.Black

    /** 控件栏上下渐变：顶部 64% 黑、底部 74% 黑，保证按钮和进度条压住亮画面。 */
    val ControlGradient = listOf(Color(0xA3000000), Color.Transparent, Color(0xBC000000))

    /** 手势浮层底：70% 黑；压在纯白画面上时白字仍约 8:1，品牌紫进度条也有 3.6:1。 */
    val Scrim = Color.Black.copy(alpha = 0.70f)

    /** 中央播放钮圆底：45% 黑，亮画面上图标仍 ≥3:1（非文字要求）。 */
    val ControlCircle = Color.Black.copy(alpha = 0.45f)

    val TextPrimary = Color.White

    /** 次要文字（副标题/快进方向/离线标记）：80% 白，压 70% 黑底仍 ≥4.5:1。 */
    val TextSecondary = Color.White.copy(alpha = 0.80f)

    /** 半透明按钮底（顶部倍速胶囊），只作背景，不承载文字对比度。 */
    val ButtonTint = Color.White.copy(alpha = 0.15f)


    /** 进度条未激活轨道。 */
    val TrackInactive = Color.White.copy(alpha = 0.45f)
    val TrackOnScrim = Color.White.copy(alpha = 0.30f)

    /** 不可用态（上一集/下一集到头）：禁用控件不受 AA 约束，保持统一 38% 白。 */
    val Disabled = Color.White.copy(alpha = 0.38f)

    /** 进度条/滑块强调色：固定品牌紫，在黑色画布上对比度充足。 */
    val Accent = Color(0xFFBB9AFE)
}
