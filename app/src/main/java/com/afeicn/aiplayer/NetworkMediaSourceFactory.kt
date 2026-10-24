package com.afeicn.aiplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.Factory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory

/**
 * 包一层 [DefaultMediaSourceFactory]：
 *
 * 1. 给“带自定义请求头”的网络媒体条目注入 Referer / User-Agent（tag = [NetworkRequestHeaders]）。
 * 2. 网络条目统一走 [CacheDataSource]（命中离线缓存）：在线播放命中缓存不重复下载，
 *    离线播放（offlineOnly=true）用 FLAG_BLOCK_ON_CACHE 只读缓存，断网也能播、绝不回源网络。
 *
 * Media3 的 [MediaItem] 没有按条目携带 HTTP 请求头的字段，所以把 [NetworkRequestHeaders]
 * 挂在 `MediaItem.LocalConfiguration.tag` 上；没有 tag 的本地条目原样走底层工厂。
 *
 * HLS（.m3u8）能力由 media3-exoplayer-hls 模块提供：DefaultMediaSourceFactory 反射加载
 * HlsMediaSource.Factory，本类不用再手动接 HLS。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class NetworkMediaSourceFactory(
    context: Context,
    private val extractorsFactory: ExtractorsFactory,
    private val cache: Cache? = null
) : Factory {
    private val context = context.applicationContext
    private val base = DefaultMediaSourceFactory(context, extractorsFactory)

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Factory {
        base.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): Factory {
        base.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun getSupportedTypes(): IntArray = base.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val isNetwork = uri?.scheme == "http" || uri?.scheme == "https"
        val tag = mediaItem.localConfiguration?.tag as? NetworkRequestHeaders
        val headers = tag?.headers.orEmpty()
        val offlineOnly = tag?.offlineOnly == true

        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = when {
            cache != null && isNetwork -> {
                val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                val upstream = DefaultDataSource.Factory(context, httpFactory)
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(OfflineDownloadRules.playbackCacheFlags(offlineOnly))
            }
            isNetwork && headers.isNotEmpty() -> {
                DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers))
            }
            else -> null
        }
        return if (dataSourceFactory == null) {
            base.createMediaSource(mediaItem)
        } else {
            // 用同一份 extractorsFactory（WMA / APE 扩展），给这条换一个走缓存的 DataSource。
            DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem)
        }
    }
}
