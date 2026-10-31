package com.afeicn.aiplayer

/**
 * 播放页形态规则：竖屏是默认形态，横屏（系统自动旋转或手动按钮）才进全屏。
 *
 * 抽成纯函数，JVM 单测覆盖方向状态机；Activity 只负责把结果翻译成
 * `ActivityInfo.SCREEN_ORIENTATION_*`。
 */
internal enum class PlayerOrientation {
    /** 跟随系统重力感应（竖屏为主，转横屏自动全屏）。 */
    UNSPECIFIED,

    /** 音频条目固定竖屏：避免转横屏后只剩一块黑画面。 */
    PORTRAIT,

    /** 手动全屏按钮：请求横屏。 */
    SENSOR_LANDSCAPE
}

internal object PlayerDisplayRules {

    /**
     * 是否渲染全屏播放器。
     *
     * 音频条目永远不自动全屏（v1 没有音频横屏界面）；视频在「手动全屏」或
     * 「系统已经转到横屏」时全屏。宽屏设备（≥600dp）上 requestedOrientation 可能被忽略，
     * 布局仍以这里的判断为准，不依赖方向锁定。
     */
    fun isFullscreen(kind: MediaKind?, manualFullscreen: Boolean, systemLandscape: Boolean): Boolean =
        kind == MediaKind.VIDEO && (manualFullscreen || systemLandscape)

    fun orientationRequest(
        isPlayerPage: Boolean,
        kind: MediaKind?,
        manualFullscreen: Boolean,
        isInPip: Boolean
    ): PlayerOrientation = when {
        !isPlayerPage -> PlayerOrientation.UNSPECIFIED
        // PiP 里方向由系统窗口决定，不跟系统抢。
        isInPip -> PlayerOrientation.UNSPECIFIED
        kind == MediaKind.AUDIO -> PlayerOrientation.PORTRAIT
        manualFullscreen -> PlayerOrientation.SENSOR_LANDSCAPE
        else -> PlayerOrientation.UNSPECIFIED
    }
}

/**
 * 播放队列的映射规则：队列是 Media3 的播放列表，游标下标与 [MediaEntry] 列表一一对应。
 * 服务端与 UI 端都用这一套，避免集号/续播位置错位。
 */
internal object PlaybackQueueRules {

    /** 起播下标兜底：空队列给 0，越界收敛到首/尾。 */
    fun startIndex(requested: Int, size: Int): Int =
        if (size <= 0) 0 else requested.coerceIn(0, size - 1)

    fun entryAt(queue: List<MediaEntry>, index: Int): MediaEntry? = queue.getOrNull(index)

    /**
     * index 兜底集号：只有整条队列从第 1 集连续且每集都带集号时才允许用 index + 1。
     * 离线队列只含已下载的集，缺集时 index 不等于真实集号，不能反推。
     */
    fun fallbackEpisodeNumber(episodeNumbers: List<Int?>, index: Int): Int? {
        if (episodeNumbers.size <= 1 || index !in episodeNumbers.indices) return null
        val complete = episodeNumbers.indices.all { episodeNumbers[it] == it + 1 }
        return if (complete) index + 1 else null
    }

    /** 当前集的真实集号：优先条目自带集号，其次连续队列兜底。 */
    fun episodeNumber(episodeNumbers: List<Int?>, index: Int): Int? =
        episodeNumbers.getOrNull(index)?.takeIf { it > 0 }
            ?: fallbackEpisodeNumber(episodeNumbers, index)

    fun episodeNumberOf(queue: List<MediaEntry>, index: Int): Int? =
        episodeNumber(queue.map { it.episodeNumber }, index)

    /** 详情区展示用的「下一集」名称；没有下一集返回 null。 */
    fun nextEntryName(names: List<String>, index: Int): String? =
        if (index in names.indices) names.getOrNull(index + 1) else null

    fun nextNameOf(queue: List<MediaEntry>, index: Int): String? =
        nextEntryName(queue.map { it.name }, index)
}

/**
 * 画中画规则：只有「播放页 + 视频 + 正在播放 + 系统支持」四件事同时成立才允许进 PiP。
 * 音频条目、列表页、暂停态都不进。
 */
internal object PipRules {
    const val MIN_SDK = 26

    fun eligible(
        isPlayerPage: Boolean,
        kind: MediaKind?,
        isPlaying: Boolean,
        sdkInt: Int,
        hasPipFeature: Boolean
    ): Boolean = sdkInt >= MIN_SDK && hasPipFeature && isPlayerPage && isVideo(kind) && isPlaying

    private fun isVideo(kind: MediaKind?): Boolean = kind == MediaKind.VIDEO

    /**
     * PiP 宽高比：优先用视频实际尺寸，非法尺寸退 16:9，
     * 并收敛到系统允许的 [1:2.39, 2.39:1] 区间。
     */
    fun aspectRatio(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
        val ratio = videoWidth.toDouble() / videoHeight
        val minRatio = 1.0 / 2.39
        val maxRatio = 2.39
        return when {
            ratio < minRatio -> 100 to 239
            ratio > maxRatio -> 239 to 100
            else -> videoWidth to videoHeight
        }
    }

    /** 从 PiP 回到 App 时是否要请求全屏：只有「刚才在 PiP、现在不在」才算。 */
    fun requestFullscreenOnExit(wasInPip: Boolean, isInPip: Boolean): Boolean =
        wasInPip && !isInPip
}
