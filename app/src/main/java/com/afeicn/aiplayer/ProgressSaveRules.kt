package com.afeicn.aiplayer

import kotlin.math.abs

/**
 * 播放中的兜底存盘规则：onPause / onStop 都可能被系统跳过（划掉任务、强杀进程），
 * 所以播放页每 [INTERVAL_MS] 主动存一次，保证最坏情况只丢这么久的进度。
 */
internal object ProgressSaveRules {
    const val INTERVAL_MS = 5_000L

    /** 位置至少挪动这么多才值得写盘：暂停、缓冲时位置不动，不必空写。 */
    const val MIN_DELTA_MS = 3_000L

    fun shouldSave(lastSavedMs: Long, currentMs: Long): Boolean =
        abs(currentMs - lastSavedMs) >= MIN_DELTA_MS
}
