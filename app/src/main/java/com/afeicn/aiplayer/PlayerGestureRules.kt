package com.afeicn.aiplayer

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 播放页手势的纯计算层（行为移植自 104vplayer 的 GesturePlayerView）：
 * 拖动方向锁定、双击分区、长按倍速档位、亮度/音量换算与横向拖动加速快进。
 * 不碰 Android 框架，方便纯 JVM 单测。
 */
object PlayerGestureRules {

    /** 拖动主轴：首次超过 touchSlop 时按占优轴锁定，整次手势不再改。 */
    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    /** 横向 vs 纵向：哪边位移绝对值大走哪边；两条轴都还没超过 touchSlop 时方向未定。 */
    fun axisOf(totalDx: Float, totalDy: Float, touchSlop: Float): Axis = when {
        abs(totalDx) <= touchSlop && abs(totalDy) <= touchSlop -> Axis.NONE
        abs(totalDx) > abs(totalDy) -> Axis.HORIZONTAL
        else -> Axis.VERTICAL
    }

    /** 竖向滑动的左右分侧：按下点在左半边调亮度，右半边调音量。 */
    enum class Side { BRIGHTNESS, VOLUME }

    fun sideOf(downX: Float, width: Float): Side =
        if (downX < width / 2f) Side.BRIGHTNESS else Side.VOLUME

    /** 双击落点分区：左右各 10% 快退/快进，中间播放暂停。 */
    enum class DoubleTapZone { BACK, FORWARD, CENTER }

    fun doubleTapZone(x: Float, width: Float): DoubleTapZone = when {
        x <= width * 0.1f -> DoubleTapZone.BACK
        x >= width * 0.9f -> DoubleTapZone.FORWARD
        else -> DoubleTapZone.CENTER
    }

    /** 双击左右边缘的跳转量（毫秒）。 */
    const val EDGE_SEEK_MS = 10_000L

    /** 长按倍速的档位阈值：56dp 的倍数（与 104vplayer 的 GesturePlayerView 一致）。 */
    const val LONG_PRESS_STEP_DP = 56f

    /**
     * 长按后相对按下点的竖直位移（dp，向上为负）→ 倍速档位：
     * 上滑一档 2x、两档 3x；下滑一档 0.75x、两档 0.5x；
     * 小幅移动（不足 0.15 档）不改速返回 null；其余（刚过阈值前的小段）为 1.5x。
     */
    fun longPressSpeed(offsetDp: Float, stepDp: Float = LONG_PRESS_STEP_DP): Float? = when {
        offsetDp <= -stepDp * 2f -> 3f
        offsetDp <= -stepDp -> 2f
        offsetDp >= stepDp * 2f -> 0.5f
        offsetDp >= stepDp -> 0.75f
        abs(offsetDp) < stepDp * 0.15f -> null
        else -> 1.5f
    }

    /** 竖向滑动调亮度/音量：起始值 + 相对屏高的位移比例 × 1.5，夹到 [min, 1]。 */
    fun adjustLevel(start: Float, distanceFraction: Float, min: Float = 0f): Float =
        (start + distanceFraction * 1.5f).coerceIn(min, 1f)
}

/**
 * 横向拖动快进快退的位移→时间换算（移植自 104vplayer SeekGestureRules）：
 * 累计位移前 500dp 内线性+二次+三次加速、之后每 dp 24 秒；
 * 反向拖动从零重新累计（预览位置不跳变）；与事件频率无关，结果夹在 [0, duration]。
 */
class AcceleratedSeek(startMs: Long, private val durationMs: Long) {
    private var position = startMs.toDouble()
    private var lastDistance = 0.0
    private var runDistance = 0.0
    private var direction = 0

    /** [distanceDp] 是距按下点的累计横向位移（dp，右正左负）。 */
    fun move(distanceDp: Float): Long {
        val delta = distanceDp.toDouble() - lastDistance
        lastDistance = distanceDp.toDouble()
        if (delta == 0.0) return position.roundToLong()
        val nextDirection = if (delta > 0) 1 else -1
        if (nextDirection != direction) {
            direction = nextDirection
            runDistance = 0.0
        }
        val nextDistance = runDistance + abs(delta)
        position = (position + direction * (curve(nextDistance) - curve(runDistance)))
            .coerceIn(0.0, durationMs.coerceAtLeast(0L).toDouble())
        runDistance = nextDistance
        return position.roundToLong()
    }

    private fun curve(distance: Double): Double {
        val d = min(distance, 500.0)
        return 100 * d + 0.8 * d * d + 0.002 * d * d * d + (distance - d) * 2400
    }
}
