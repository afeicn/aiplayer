package com.afeicn.aiplayer

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * UI（MediaController）与 [PlaybackService] 之间的自定义命令。
 *
 * 队列不能直接通过 `MediaController.setMediaItems` 传：请求头 / offlineOnly 挂在
 * [MediaItem.LocalConfiguration.tag] 上，跨 Binder 传递要求 Parcelable；这里改成把
 * [MediaEntry] 逐字段编码进 Bundle，由服务端按同一份数据重建播放列表。
 */
internal object PlaybackCommands {
    const val SET_QUEUE = "com.afeicn.aiplayer.cmd.SET_QUEUE"
    const val SAVE_PROGRESS = "com.afeicn.aiplayer.cmd.SAVE_PROGRESS"
    const val STOP = "com.afeicn.aiplayer.cmd.STOP"

    const val KEY_SYNCHRONOUS = "synchronous"
    const val KEY_START_INDEX = "startIndex"
    const val KEY_COUNT = "count"

    private const val ENTRY_PREFIX = "entry."
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "name"
    private const val KEY_KIND = "kind"
    private const val KEY_FOLDER = "folder"
    private const val KEY_EXTENSION = "extension"
    private const val KEY_SIZE = "size"
    private const val KEY_LAST_MODIFIED = "lastModified"
    private const val KEY_TITLE = "title"
    private const val KEY_LIVE = "live"
    private const val KEY_OFFLINE_ONLY = "offlineOnly"
    private const val KEY_EPISODE_NUMBER = "episodeNumber"
    private const val KEY_HEADER_KEYS = "headerKeys"
    private const val KEY_HEADER_VALUES = "headerValues"

    /** MediaItem 里附带条目元信息：服务重启 / 队列丢失时仍能保存集号。 */
    const val EXTRA_ENTRY_NAME = "aiplayer.entry.name"
    const val EXTRA_ENTRY_KIND = "aiplayer.entry.kind"
    const val EXTRA_ENTRY_TITLE = "aiplayer.entry.title"
    const val EXTRA_EPISODE_NUMBER = "aiplayer.entry.episodeNumber"

    data class QueueStart(val entries: List<MediaEntry>, val startIndex: Int)

    fun encodeQueue(entries: List<MediaEntry>, startIndex: Int): Bundle = Bundle().apply {
        putInt(KEY_START_INDEX, startIndex)
        putInt(KEY_COUNT, entries.size)
        entries.forEachIndexed { index, entry ->
            val prefix = "$ENTRY_PREFIX$index."
            putString(prefix + KEY_URI, entry.uri.toString())
            putString(prefix + KEY_NAME, entry.name)
            putString(prefix + KEY_KIND, entry.kind.name)
            putString(prefix + KEY_FOLDER, entry.folder)
            putString(prefix + KEY_EXTENSION, entry.extension)
            putLong(prefix + KEY_SIZE, entry.sizeBytes)
            putLong(prefix + KEY_LAST_MODIFIED, entry.lastModified)
            putString(prefix + KEY_TITLE, entry.title)
            putBoolean(prefix + KEY_LIVE, entry.isLive)
            putBoolean(prefix + KEY_OFFLINE_ONLY, entry.offlineOnly)
            putInt(prefix + KEY_EPISODE_NUMBER, entry.episodeNumber ?: 0)
            // 同一个 Map 的 keys / values 迭代顺序一致，按下标对齐即可。
            putStringArrayList(prefix + KEY_HEADER_KEYS, ArrayList(entry.networkHeaders.keys))
            putStringArrayList(prefix + KEY_HEADER_VALUES, ArrayList(entry.networkHeaders.values))
        }
    }

    /** 解码失败（包被截断 / 版本不匹配）返回 null，服务端安全忽略而不是崩。 */
    fun decodeQueue(bundle: Bundle): QueueStart? {
        val count = bundle.getInt(KEY_COUNT, -1)
        if (count <= 0) return null
        val entries = ArrayList<MediaEntry>(count)
        for (index in 0 until count) {
            val prefix = "$ENTRY_PREFIX$index."
            val uri = bundle.getString(prefix + KEY_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: return null
            val kind = runCatching { MediaKind.valueOf(bundle.getString(prefix + KEY_KIND).orEmpty()) }
                .getOrDefault(MediaKind.VIDEO)
            val keys = bundle.getStringArrayList(prefix + KEY_HEADER_KEYS).orEmpty()
            val values = bundle.getStringArrayList(prefix + KEY_HEADER_VALUES).orEmpty()
            val headers = buildMap {
                keys.forEachIndexed { headerIndex, key ->
                    values.getOrNull(headerIndex)?.let { put(key, it) }
                }
            }
            entries += MediaEntry(
                uri = uri,
                name = bundle.getString(prefix + KEY_NAME).orEmpty(),
                kind = kind,
                folder = bundle.getString(prefix + KEY_FOLDER).orEmpty(),
                extension = bundle.getString(prefix + KEY_EXTENSION).orEmpty(),
                sizeBytes = bundle.getLong(prefix + KEY_SIZE),
                lastModified = bundle.getLong(prefix + KEY_LAST_MODIFIED),
                title = bundle.getString(prefix + KEY_TITLE).orEmpty(),
                networkHeaders = headers,
                isLive = bundle.getBoolean(prefix + KEY_LIVE),
                offlineOnly = bundle.getBoolean(prefix + KEY_OFFLINE_ONLY),
                episodeNumber = bundle.getInt(prefix + KEY_EPISODE_NUMBER).takeIf { it > 0 }
            )
        }
        return QueueStart(entries, bundle.getInt(KEY_START_INDEX, 0))
    }
}

/** 条目元信息 → MediaItem extras；进度保存不依赖内存里的队列。 */
internal fun MediaEntry.metadataExtras(): Bundle = Bundle().apply {
    putString(PlaybackCommands.EXTRA_ENTRY_NAME, name)
    putString(PlaybackCommands.EXTRA_ENTRY_KIND, kind.name)
    putString(PlaybackCommands.EXTRA_ENTRY_TITLE, title)
    putInt(PlaybackCommands.EXTRA_EPISODE_NUMBER, episodeNumber ?: 0)
}

/** 从 MediaItem 还原用于存进度的最小条目信息（队列丢失时的兜底）。 */
internal fun MediaItem.toEntryOrNull(): MediaEntry? {
    val uri = localConfiguration?.uri ?: return null
    val extras = mediaMetadata.extras
    val kind = runCatching {
        MediaKind.valueOf(extras?.getString(PlaybackCommands.EXTRA_ENTRY_KIND).orEmpty())
    }.getOrDefault(MediaKind.VIDEO)
    return MediaEntry(
        uri = uri,
        name = extras?.getString(PlaybackCommands.EXTRA_ENTRY_NAME)
            ?: mediaMetadata.title?.toString()
            ?: uri.lastPathSegment.orEmpty(),
        kind = kind,
        folder = "",
        extension = "",
        title = extras?.getString(PlaybackCommands.EXTRA_ENTRY_TITLE).orEmpty(),
        episodeNumber = extras?.getInt(PlaybackCommands.EXTRA_EPISODE_NUMBER)?.takeIf { it > 0 }
    )
}

/** 给通知 / 锁屏用的媒体元信息：大标题用剧名，副标题用集名。 */
internal fun MediaEntry.metadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(name.ifBlank { displayTitle })
    .setSubtitle(title.takeIf { it.isNotBlank() })
    .setExtras(metadataExtras())
    .build()
