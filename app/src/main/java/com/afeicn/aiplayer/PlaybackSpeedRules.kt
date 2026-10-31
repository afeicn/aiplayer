package com.afeicn.aiplayer

import kotlin.math.abs

/**
 * 倍速控件的纯规则：档位、显示文案、从播放器当前速度回推选中项。
 *
 * 播放页只有一个倍速控件，按钮本身显示当前档位；UI 复用这里的 [label]，
 * 避免「一处显示 1.0x、另一处再显示一个倍速按钮」。
 */
internal object PlaybackSpeedRules {

    val OPTIONS = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    val DEFAULT = 1.0f

    /** 1.0f → "1.0x"，1.25f → "1.25x"（Float.toString 正好不丢有效位）。 */
    fun label(speed: Float): String = "${speed}x"

    /**
     * 从任意播放速度回推最近的档位。
     * 手势长按可能把速度临时改到 1.5x/2x，回到页面时按最近档位显示。
     */
    fun indexOf(speed: Float): Int {
        if (!speed.isFinite()) return 0
        return OPTIONS.indices.minByOrNull { abs(OPTIONS[it] - speed) } ?: 0
    }

    /** 越界下标回落默认档，避免 UI 状态脏了之后崩溃。 */
    fun speedAt(index: Int): Float = OPTIONS.getOrElse(index) { DEFAULT }
}
