package com.afeicn.aiplayer

/**
 * 破坏性操作的统一策略层：哪些操作必须二次确认、哪些用「删除后撤销」代替确认弹窗，
 * 以及离线缓存多选删除的勾选集合与体积汇总。全部是纯函数（只依赖数据类与字符串），
 * 方便纯 JVM 单测，UI 层直接复用，避免「危险操作一键执行」的事故再次出现。
 */

/** 应用里的破坏性操作清单（每加一个危险按钮，都要在这里登记并决定它的确认机制）。 */
enum class DestructiveOp {
    /** 设置页/离线页的「清空全部离线缓存」。 */
    CLEAR_ALL_OFFLINE,
    /** 离线缓存多选模式的「删除选中」。 */
    DELETE_SELECTED_OFFLINE,
    /** 设置页「清空 AI 歌词缓存」。 */
    CLEAR_AI_LYRICS,
    /** 源管理页管理模式的多选「删除选中」。 */
    DELETE_SELECTED_SOURCES,
    /** 单条离线缓存（下载任务 / 已下载条目）删除。 */
    DELETE_SINGLE_OFFLINE,
    /** 在线搜索历史：单条删除（长按「删除这条」）。 */
    DELETE_SINGLE_SEARCH_HISTORY,
    /** 在线搜索历史：管理模式「删除选中（N 条）」。 */
    DELETE_SELECTED_SEARCH_HISTORY
}

/** 破坏性操作的防护机制。 */
enum class DestructiveMechanism {
    /** 必须先弹二次确认弹窗，确认文案说明删什么、删多少、是否可恢复。 */
    CONFIRM_DIALOG,
    /** 先执行删除，再给 5 秒「撤销」入口，不弹确认框。 */
    UNDO
}

/** 破坏性操作 → 防护机制的映射（单条删除用撤销，其余批量/清空类必须弹确认）。 */
object DestructiveActionPolicy {
    fun mechanism(op: DestructiveOp): DestructiveMechanism = when (op) {
        DestructiveOp.DELETE_SINGLE_OFFLINE -> DestructiveMechanism.UNDO
        DestructiveOp.DELETE_SINGLE_SEARCH_HISTORY -> DestructiveMechanism.UNDO
        else -> DestructiveMechanism.CONFIRM_DIALOG
    }

    fun requiresConfirmDialog(op: DestructiveOp): Boolean = mechanism(op) == DestructiveMechanism.CONFIRM_DIALOG

    fun usesUndo(op: DestructiveOp): Boolean = mechanism(op) == DestructiveMechanism.UNDO
}

/**
 * 离线缓存多选模式的勾选集合。不可变：每次 toggle / selectAll 都返回新实例，便于 Compose
 * 状态更新与单测。空集合 + 空列表时「全选」仍是空集合（不会误判为已全选）。
 */
data class OfflineSelection(val selected: Set<String> = emptySet()) {

    val count: Int get() = selected.size

    fun toggle(id: String): OfflineSelection =
        if (id in selected) OfflineSelection(selected - id) else OfflineSelection(selected + id)

    fun selectAll(ids: Collection<String>): OfflineSelection = OfflineSelection(ids.toSet())

    fun clear(): OfflineSelection = OfflineSelection()

    fun isSelected(id: String): Boolean = id in selected

    /** 只有「非空列表且全部勾选」才算全选；空列表不算全选（避免把空态显示成已全选）。 */
    fun isAllSelected(ids: Collection<String>): Boolean =
        ids.isNotEmpty() && selected.containsAll(ids)

    /** 全选 / 取消全选的切换结果：已全选则清空，否则选中全部。 */
    fun toggleAll(ids: Collection<String>): OfflineSelection =
        if (isAllSelected(ids)) clear() else selectAll(ids)
}

/** 多选删除的体积汇总与文案（纯函数）。 */
object OfflineSelectionRules {

    /**
     * 单条下载真正占用的字节数：有已下载字节按已下载字节算；已完成但字节为 0 时退回总字节；
     * 排队中未下任何字节按 0 算（不虚报占用）。
     */
    fun sizeBytes(download: OfflineDownload): Long = when {
        download.downloadedBytes > 0L -> download.downloadedBytes
        download.state == DownloadTaskState.COMPLETED -> download.totalBytes
        else -> 0L
    }

    /** 勾选集合对应的总占用字节数。 */
    fun selectedSizeBytes(downloads: List<OfflineDownload>, selected: Set<String>): Long =
        downloads.filter { it.item.id in selected }.sumOf(::sizeBytes)

    /** 勾选集合对应的可读占用文案（如「1.2 GB」）。 */
    fun selectedSizeText(downloads: List<OfflineDownload>, selected: Set<String>): String =
        OfflineDownloadRules.formatBytes(selectedSizeBytes(downloads, selected))
}

/**
 * 订阅源管理模式（长按源行进入）的勾选集合。语义与 [OfflineSelection] 一致：
 * 不可变，每次操作返回新实例；空列表不算全选。
 */
data class SourceSelection(val selected: Set<String> = emptySet()) {

    val count: Int get() = selected.size

    fun toggle(id: String): SourceSelection =
        if (id in selected) SourceSelection(selected - id) else SourceSelection(selected + id)

    fun selectAll(ids: Collection<String>): SourceSelection = SourceSelection(ids.toSet())

    fun clear(): SourceSelection = SourceSelection()

    fun isSelected(id: String): Boolean = id in selected

    /** 只有「非空列表且全部勾选」才算全选；空列表不算全选。 */
    fun isAllSelected(ids: Collection<String>): Boolean =
        ids.isNotEmpty() && selected.containsAll(ids)

    /** 全选 / 取消全选的切换结果：已全选则清空，否则选中全部。 */
    fun toggleAll(ids: Collection<String>): SourceSelection =
        if (isAllSelected(ids)) clear() else selectAll(ids)
}

/** 二次确认弹窗与提示的文案（纯函数，单测锁死口径）。 */
object DestructiveCopy {
    fun deleteSelectedTitle(n: Int): String = "删除 $n 项？"

    fun deleteSelectedBody(n: Int, sizeText: String): String =
        "将删除 $n 项离线缓存，共 $sizeText。删除后需要重新下载。"

    /**
     * 媒体库「已下载」页签的「管理 → 删除选中」确认文案。
     *
     * 与 [deleteSelectedTitle]/[deleteSelectedBody] 同一口径，只是计量单位换成「集」——
     * 这一处删的全是剧集（已下载的集 + 还在下载的任务），说「项」用户不知道删了多少集。
     */
    fun deleteEpisodesTitle(n: Int): String = "删除 $n 集？"

    fun deleteEpisodesBody(n: Int, sizeText: String): String =
        "将删除 $n 集离线缓存，共 $sizeText。删除后需要重新下载。"

    fun clearAllTitle(): String = "清空全部离线缓存？"

    fun clearAllBody(sizeText: String): String =
        "将清空全部离线缓存，共 $sizeText。删除后需要重新下载。"

    fun clearLyricsTitle(): String = "清空歌词缓存？"

    fun clearLyricsBody(n: Int): String =
        "将清空本机 AI 歌词缓存（$n 首），已生成的时间轴需要重新分析。"

    /** 源管理模式「删除选中」的确认文案：说清删几个、影响是什么。 */
    fun deleteSourcesTitle(n: Int): String = "删除 $n 个订阅源？"

    fun deleteSourcesBody(): String =
        "仅移除这些源，已下载视频不会受到影响。删除后需要重新添加才能搜索。"

    fun deletedSourcesToast(n: Int): String = "已删除 $n 个订阅源"

    fun singleDeleteUndoMessage(): String = "已删除，5 秒内可撤销"

    /** 搜索历史单条删除的撤销条文案（带上关键词，用户能看出删的是哪条）。 */
    fun singleDeleteHistoryUndoMessage(keyword: String): String = "已删除「$keyword」，5 秒内可撤销"

    fun deletedCountToast(n: Int): String = "已删除 $n 项"

    /** 「已下载」页签批量删除后的 toast（单位是集）。 */
    fun deletedEpisodeCountToast(n: Int): String = "已删除 $n 集"

    /** 搜索历史批量删除的确认文案：说清删什么、删几条、是否可恢复。 */
    fun deleteSelectedHistoryTitle(n: Int): String = "删除 $n 条搜索历史？"

    fun deleteSelectedHistoryBody(): String =
        "只删除本机的搜索关键词记录，不影响离线缓存、片源和播放历史。"

    fun deletedHistoryCountToast(n: Int): String = "已删除 $n 条搜索历史"
}
