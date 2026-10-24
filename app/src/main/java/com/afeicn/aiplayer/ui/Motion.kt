package com.afeicn.aiplayer.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * 全局动效 Token：所有时长/曲线收在这里，禁止各 Composable 自写时长。
 * 时长用 Int（毫秒），配合 `tween(Motion.X)` 使用；
 * 系统「动画缩放 = 0」时 [rememberMotionEnabled] 返回 false，各处时长归零。
 */
object Motion {
    val TAP = 120              // 点击反馈（M3 按压已覆盖，仅备用）
    val ICON_STATE = 150       // 图标/格子颜色与状态过渡
    val TAB_SWITCH = 220       // 首页↔发现交叉淡化、胶囊选中态变色
    val PAGE_ENTER = 260       // 二级页进入（右移 24dp + 淡入）
    val PAGE_EXIT = 200        // 二级页退出（反向）
    val DETAIL_ENTER = 360    // 详情海报转场（共享元素形变时长）
    val DETAIL_CONTENT_DELAY = 180 // 详情文案在转场后段才浮现，让海报先落位
    val SHEET_OPEN = 300
    val SHEET_CLOSE = 240
    val DIALOG = 220           // 对话框淡入 + 0.92→1 缩放
    val EXPAND = 240           // 卡片展开/收起高度动画
    val CONTROLS = 180         // 播放器控制层淡入淡出（进出播放器页同用）
    val ITEM_REMOVE = 220      // 列表行删除收缩补位
    val PROGRESS = 900         // 下载进度连续动画（Linear，掩盖 800ms 轮询台阶）
    val EMPTY_STATE = 240      // 空状态 0.97→1 缩放淡入
    val THEME_SWITCH = 200

    /** M3 标准进场曲线：先快后缓，落点稳。 */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    /** M3 标准出场曲线：先慢后快，离开干脆。 */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** 系统动画缩放是否为 0（无障碍「移除动画」）；关闭时所有动画时长归零。 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}

/** 时长取值：动画关闭时归零，立即完成。 */
fun motionDuration(motionOn: Boolean, ms: Int): Int = if (motionOn) ms else 0

/** 按开关取 tween，关闭时时长 0；泛型让同一 helper 适配颜色/透明度/位移等不同动画类型。 */
fun <T> motionTween(motionOn: Boolean, ms: Int,
    easing: androidx.compose.animation.core.Easing = Motion.EmphasizedDecelerate
): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    tween(motionDuration(motionOn, ms), easing = easing)

/**
 * 转场后段内容入场：页面出现后延迟 [delayMs] 再淡入 [durationMs]。
 * 共享元素（海报）浮在页面 alpha 之上不受影响，观感上是海报先飞到位、
 * 标题按钮随后浮现，而不是文案在海报飞行途中就到场。
 */
@Composable
fun Modifier.revealDelayed(delayMs: Int, durationMs: Int): Modifier {
    val motionOn = rememberMotionEnabled()
    if (!motionOn) return this
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        alpha.animateTo(1f, motionTween(motionOn, durationMs))
    }
    return this.alpha(alpha.value)
}

/**
 * 空状态进场：0.97→1 缩放 + 淡入 240ms，只做一次，不做持续悬浮/呼吸。
 * 用在「暂无任务」「还没有订阅源」这类首次出现的占位内容上。
 */
@Composable
fun RevealOnEnter(content: @Composable () -> Unit) {
    val motionOn = rememberMotionEnabled()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(motionDuration(motionOn, Motion.EMPTY_STATE))) +
            scaleIn(initialScale = 0.97f, animationSpec = tween(motionDuration(motionOn, Motion.EMPTY_STATE))),
        exit = fadeOut(tween(motionDuration(motionOn, Motion.EMPTY_STATE))),
        label = "reveal"
    ) { content() }
}
