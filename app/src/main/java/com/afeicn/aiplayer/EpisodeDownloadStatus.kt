package com.afeicn.aiplayer

/**
 * 选集网格「按当前实际下载状态渲染角标 + 防重复」的纯逻辑层。
 *
 * 把某部剧每集在离线下载体系里的真实状态（来自 [OfflineLibrary.episodeStatuses]）映射成
 * 网格角标（已下载 ✓ / 获取地址中 ⋯ / 下载中 ↓ / 可下载可选），并把「用户选中集合」拆成
 * 「可加入集合 / 需跳过集合 / 提示文案」。全部不碰 Android 框架，方便纯 JVM 单测。
 */

/** 选集网格里一集的展示状态。 */
enum class EpisodeGridStatus(val label: String) {
    /** 已下载完成 → 勾角标，不可再选。 */
    DOWNLOADED("已下载"),
    /** 正在逐集解析真实地址 → ⋯ 角标，不可再选。 */
    RESOLVING("获取地址中"),
    /** 已在下载队列（排队/下载中/暂停等待） → ↓ 角标，不可再选。 */
    ACTIVE("下载中"),
    /** 没有任何下载记录（或已失败可重下） → 正常可选。 */
    AVAILABLE("可下载")
}

/** 选集提交前的拆分结果：哪些可以加入下载，哪些要跳过，以及对应的提示文案。 */
data class EpisodeSelectionPartition(
    val addable: Set<Int>,
    val skipped: Set<Int>,
    val hints: List<String>
) {
    val addableCount: Int get() = addable.size
    val skippedCount: Int get() = skipped.size
}

object EpisodeDownloadRules {

    /** 单集下载状态 → 网格角标状态；null（无记录）与 FAILED（可重下）都视为可下载。 */
    fun statusOf(state: DownloadTaskState?): EpisodeGridStatus = when (state) {
        DownloadTaskState.COMPLETED -> EpisodeGridStatus.DOWNLOADED
        DownloadTaskState.RESOLVING -> EpisodeGridStatus.RESOLVING
        DownloadTaskState.QUEUED,
        DownloadTaskState.DOWNLOADING,
        DownloadTaskState.PAUSED,
        DownloadTaskState.WAITING_NETWORK,
        DownloadTaskState.WAITING_WIFI -> EpisodeGridStatus.ACTIVE
        DownloadTaskState.FAILED,
        DownloadTaskState.REMOVED,
        null -> EpisodeGridStatus.AVAILABLE
    }

    /** 某一集不可选时给用户的提示文案。 */
    fun hintFor(index: Int, state: DownloadTaskState): String = when (state) {
        DownloadTaskState.COMPLETED -> "第 $index 集已下载"
        DownloadTaskState.RESOLVING -> "第 $index 集正在获取地址"
        DownloadTaskState.QUEUED,
        DownloadTaskState.DOWNLOADING -> "第 $index 集已在下载中"
        DownloadTaskState.PAUSED,
        DownloadTaskState.WAITING_NETWORK,
        DownloadTaskState.WAITING_WIFI -> "第 $index 集已在下载队列"
        else -> "第 $index 集已在下载中"
    }

    /**
     * 把「用户选中的集合」按当前下载状态拆成「可加入 / 需跳过」。
     *
     * 只有 [EpisodeGridStatus.AVAILABLE] 的集算可加入；已下载/获取中/下载中/排队等待的集
     * 一律跳过，并逐集产出提示文案（按集号升序）。纯函数：输入不变输出不变。
     */
    fun partition(
        selected: Set<Int>,
        statuses: Map<Int, DownloadTaskState>
    ): EpisodeSelectionPartition {
        val addable = mutableSetOf<Int>()
        val skipped = mutableSetOf<Int>()
        val hints = mutableListOf<String>()
        for (index in selected.sorted()) {
            val state = statuses[index]
            if (statusOf(state) == EpisodeGridStatus.AVAILABLE) {
                addable += index
            } else {
                skipped += index
                if (state != null) hints += hintFor(index, state)
            }
        }
        return EpisodeSelectionPartition(addable, skipped, hints)
    }

    /** 可下载的集号集合（网格里允许被选中的那些，供全选/反选/前 N 集快捷按钮使用）。 */
    fun availableIndices(total: Int, statuses: Map<Int, DownloadTaskState>): Set<Int> =
        (1..total).filter { statusOf(statuses[it]) == EpisodeGridStatus.AVAILABLE }.toSet()
}
