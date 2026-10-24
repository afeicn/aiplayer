package com.afeicn.aiplayer

import android.net.Uri

/** 媒体类型。v1 界面只做视频，AUDIO 保留用于数据兼容（历史离线条目/音频源）。 */
enum class MediaKind(val label: String) {
    VIDEO("视频"),
    AUDIO("音频")
}

/**
 * 播放链路用的媒体条目（在线流 / 离线缓存两条路都经它进 ExoPlayer）。
 * 本地媒体库不在 v1 范围内，这里只保留在线搜索与离线播放用到的字段。
 */
data class MediaEntry(
    val uri: Uri,
    val name: String,
    val kind: MediaKind,
    val folder: String,
    val extension: String,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
    val title: String = "",
    // 网络流专用字段（离线/本地条目保持默认值；不落库，只存活在单次播放会话里）。
    val networkHeaders: Map<String, String> = emptyMap(),
    val isLive: Boolean = false,
    // 离线缓存专用：只从缓存读（BLOCK_ON_CACHE），断网也能播，绝不回源网络。
    val offlineOnly: Boolean = false
) {
    val isNetwork: Boolean get() = uri.scheme == "http" || uri.scheme == "https"
    val displayTitle: String get() = title.ifBlank { name.substringBeforeLast('.') }
}
