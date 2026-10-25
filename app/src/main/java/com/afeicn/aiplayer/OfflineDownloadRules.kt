package com.afeicn.aiplayer

import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import java.security.MessageDigest

/**
 * 离线缓存下载的纯逻辑层：状态机、失败原因分类、重复任务判定、磁盘空间判断、缓存命中选择。
 * 全部不碰 Android 框架（只引用 Media3 的 int 常量，编译期内联），方便纯 JVM 单测。
 */

/** 下载任务的 UI 状态机（获取地址中/排队/下载中/暂停/完成/失败/删除）。 */
enum class DownloadTaskState(val label: String) {
    RESOLVING("获取地址中"),
    QUEUED("排队"),
    DOWNLOADING("下载中"),
    PAUSED("已暂停"),
    WAITING_NETWORK("等待网络"),
    WAITING_WIFI("等待 WiFi"),
    COMPLETED("已完成"),
    FAILED("失败"),
    REMOVED("已删除");

    fun allowedActions(): Set<OfflineAction> = when (this) {
        RESOLVING -> setOf(OfflineAction.DELETE)
        QUEUED -> setOf(OfflineAction.PAUSE, OfflineAction.DELETE)
        DOWNLOADING -> setOf(OfflineAction.PAUSE, OfflineAction.DELETE)
        PAUSED -> setOf(OfflineAction.RESUME, OfflineAction.DELETE)
        WAITING_NETWORK -> setOf(OfflineAction.RESUME, OfflineAction.DELETE)
        WAITING_WIFI -> setOf(OfflineAction.RESUME, OfflineAction.DELETE)
        COMPLETED -> setOf(OfflineAction.DELETE)
        FAILED -> setOf(OfflineAction.RETRY, OfflineAction.DELETE)
        REMOVED -> emptySet()
    }

    /** 状态迁移：非法动作保持原状态（幂等），合法动作落到目标态。 */
    fun transition(action: OfflineAction): DownloadTaskState = when (action) {
        OfflineAction.PAUSE -> if (OfflineAction.PAUSE in allowedActions()) PAUSED else this
        OfflineAction.RESUME -> if (OfflineAction.RESUME in allowedActions()) QUEUED else this
        OfflineAction.RETRY -> if (OfflineAction.RETRY in allowedActions()) QUEUED else this
        OfflineAction.DELETE -> if (OfflineAction.DELETE in allowedActions()) REMOVED else this
    }

    /**
     * 解析结果落地：只有 [RESOLVING]（获取地址中）会被解析结果推进——成功转 [QUEUED]、失败转 [FAILED]；
     * 其余状态保持原状（幂等）。真实解析由后台协程完成，这里只表达状态流转，便于纯 JVM 单测。
     */
    fun afterResolve(success: Boolean): DownloadTaskState = when {
        this == RESOLVING && success -> QUEUED
        this == RESOLVING && !success -> FAILED
        else -> this
    }
}

enum class OfflineAction { PAUSE, RESUME, DELETE, RETRY }

enum class OfflineFailureReason(val label: String) {
    NONE(""),
    RESOLVE_FAILED("获取地址失败"),
    NETWORK("网络断开，请检查网络后重试"),
    LINK_EXPIRED("资源链接失效，请重新搜索"),
    FORMAT_UNSUPPORTED("格式不支持，无法下载"),
    NO_SPACE("磁盘空间不足"),
    UNKNOWN("未知错误")
}

enum class EnqueueResult { STARTED, DUPLICATE, NO_SPACE, INVALID_URL, WAITING_NETWORK }

object OfflineDownloadRules {

    /** 用 URL 生成稳定下载 id：同一资源重复点复用同一任务，不重复建。 */
    fun downloadIdFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 重复任务判定：已有下载 id 集合里是否已含该 URL 对应的任务，命中则返回已有 id。 */
    fun findDuplicateId(existingIds: Set<String>, url: String): String? =
        downloadIdFor(url).takeIf { it in existingIds }

    /** 海报回填：只给同名且海报为空的条目补上，已有海报不覆盖。 */
    fun patchCover(item: OfflineItem, title: String, cover: String): OfflineItem =
        if (cover.isNotBlank() && item.cover.isBlank() && OfflineLibrary.sameTitle(item.title, title))
            item.copy(cover = cover) else item

    /** 简介回填：只给同名且简介为空的条目补上，已有简介不覆盖。 */
    fun patchBlurb(item: OfflineItem, title: String, blurb: String): OfflineItem =
        if (blurb.isNotBlank() && item.blurb.isBlank() && OfflineLibrary.sameTitle(item.title, title))
            item.copy(blurb = blurb) else item

    /** Media3 [Download] 状态 + 停因 → 本地 UI 状态。 */
    fun mapState(state: Int, stopReason: Int = Download.STOP_REASON_NONE): DownloadTaskState = when (state) {
        Download.STATE_QUEUED -> DownloadTaskState.QUEUED
        Download.STATE_DOWNLOADING -> DownloadTaskState.DOWNLOADING
        Download.STATE_RESTARTING -> DownloadTaskState.DOWNLOADING
        Download.STATE_STOPPED -> when (stopReason) {
            STOP_REASON_WAITING_NETWORK -> DownloadTaskState.WAITING_NETWORK
            STOP_REASON_WAITING_WIFI -> DownloadTaskState.WAITING_WIFI
            else -> DownloadTaskState.PAUSED
        }
        Download.STATE_COMPLETED -> DownloadTaskState.COMPLETED
        Download.STATE_FAILED -> DownloadTaskState.FAILED
        else -> DownloadTaskState.FAILED
    }

    /** 用户手动暂停（区别于网络闸门造成的等待）。 */
    const val STOP_REASON_PAUSED = 1
    /** 入队即被移动数据闸门拦下，等待网络（WiFi 或开启移动数据开关）。 */
    const val STOP_REASON_WAITING_NETWORK = 2
    /** 下载中切到移动数据被闸门暂停，等待 WiFi。 */
    const val STOP_REASON_WAITING_WIFI = 3

    /**
     * 失败原因分类：遍历异常链，命中 HTTP 状态码（403/404/410）判「链接失效」，
     * IO 磁盘错误判「空间不足」，网络异常判「网络断开」，解析/格式错误判「格式不支持」。
     */
    fun classifyFailure(throwable: Throwable?): OfflineFailureReason {
        if (throwable == null) return OfflineFailureReason.NONE
        val chain = generateSequence(throwable) { it.cause }.toList()
        val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
        return when {
            Regex("""\b(403|404|410)\b""").containsMatchIn(text) -> OfflineFailureReason.LINK_EXPIRED
            Regex("""ENOSPC|No space left|insufficient space|not enough space|空间不足""").containsMatchIn(text) -> OfflineFailureReason.NO_SPACE
            Regex("""UnknownHost|ConnectException|SocketTimeout|SocketException|Connection refused|failed to connect|Network is unreachable|Unable to resolve|ECONN|timed out|timeout""").containsMatchIn(text) -> OfflineFailureReason.NETWORK
            Regex("""UnrecognizedInputFormat|ParserException|did not match|Unsupported|not supported|extractor""").containsMatchIn(text) -> OfflineFailureReason.FORMAT_UNSUPPORTED
            else -> OfflineFailureReason.UNKNOWN
        }
    }

    /** 断点续传判定：已有下载字节且任务仍在非终态（可继续追加而非从头再来）。 */
    fun isResumable(downloadedBytes: Long, state: Int): Boolean =
        downloadedBytes > 0L && state in setOf(
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING, Download.STATE_STOPPED
        )

    /** 展示文案：进度状态 + 失败原因，特殊处理「因空间不足暂停」。 */
    fun statusLabel(state: DownloadTaskState, failure: OfflineFailureReason): String = when {
        failure == OfflineFailureReason.NO_SPACE &&
            state in setOf(DownloadTaskState.PAUSED, DownloadTaskState.FAILED) -> "因空间不足暂停"
        else -> state.label
    }

    /** 磁盘空间判断：先保证最低可用空间，已知体积再额外留 10% 余量。 */
    fun hasEnoughSpace(
        availableBytes: Long,
        contentLengthBytes: Long,
        minFreeBytes: Long = MIN_FREE_BYTES
    ): Boolean {
        if (availableBytes < minFreeBytes) return false
        if (contentLengthBytes > 0L) return availableBytes >= (contentLengthBytes * 11L) / 10L
        return true
    }

    /** 缓存命中模式（在线/离线两种）：离线用 BLOCK_ON_CACHE，命中不到立刻失败而不是偷跑网络。 */
    fun playbackCacheFlags(offlineOnly: Boolean): Int =
        if (offlineOnly) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0

    /** 离线可播判定：任务完成且进度拉满。 */
    fun isOfflinePlayable(state: Int, percentDownloaded: Float): Boolean =
        state == Download.STATE_COMPLETED && percentDownloaded >= 100f

    const val MIN_FREE_BYTES = 200L * 1024L * 1024L

    /** 可读字节格式化（设置页展示缓存占用）。 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "$bytes B" else String.format("%.1f %s", value, units[index])
    }
}
