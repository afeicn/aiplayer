package com.afeicn.aiplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * 全局唯一的 ExoPlayer：媒体源工厂包了 [NetworkMediaSourceFactory]，
 * 网络条目自动走离线缓存（在线命中缓存不重复下载；offlineOnly 断网只读缓存）。
 *
 * 进程内单例由 [PlaybackService]（MediaSessionService）持有，服务销毁时 release；
 * UI 侧只通过 MediaController 操作，不直接拿这个实例。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlayerEngine {
    private var player: ExoPlayer? = null

    fun player(context: Context): ExoPlayer {
        player?.let { return it }
        val cached = NetworkMediaSourceFactory(context, DefaultExtractorsFactory(), OfflineDownloadManager.cache(context))
        return ExoPlayer.Builder(context).setMediaSourceFactory(cached).build().also { player = it }
    }

    fun release() {
        player?.release()
        player = null
    }

    /**
     * 已有实例才返回，没有就返回 null。
     * 存进度这类操作不该为了读一个位置凭空创建播放器。
     */
    fun existing(): ExoPlayer? = player

    /** 是否已创建且在播：退后台静音前先问一句，不为暂停凭空创建实例。 */
    fun isActive(): Boolean = player?.isPlaying == true
}
