package com.afeicn.aiplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * HLS 播放列表纯解析（主列表/分片列表），无 Android 依赖，供 [ParallelHlsDownloader] 与单测复用。
 *
 * 与 [M3uParser]（直播频道列表）不同，这里处理的是媒体播放列表：
 * 主列表（#EXT-X-STREAM-INF）选码率、媒体列表取分片，并做相对地址解析。
 */
object HlsPlaylist {

    private val STREAM_INF = Regex("""#EXT-X-STREAM-INF:[^\n]*""")
    private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val EXTINF_DURATION = Regex("""#EXTINF:\s*([\d.]+)""")

    fun isMaster(text: String): Boolean = text.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

    /** 主列表里选 BANDWIDTH 最高的变体，返回相对 base 解析后的绝对地址。 */
    fun bestVariantUri(text: String, baseUri: String): String? {
        var best: Pair<Long, String>? = null
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = (i + 1 until lines.size).firstOrNull { !lines[it].isBlank() && !lines[it].trim().startsWith("#") }
                ?.let { lines[it].trim() } ?: continue
            if (best == null || bandwidth > best!!.first) best = bandwidth to resolveUri(baseUri, uri)
        }
        return best?.second
    }

    /** 变体列表里该变体的 BANDWIDTH（主列表没有时返回 -1）。 */
    fun variantBandwidth(text: String): Long =
        STREAM_INF.find(text)?.let { BANDWIDTH.find(it.value)?.groupValues?.get(1)?.toLongOrNull() } ?: -1L

    /** 主列表所有变体里的最高 BANDWIDTH（与 [bestVariantUri] 的选择一致）。 */
    fun maxBandwidth(text: String): Long {
        var best = -1L
        STREAM_INF.findAll(text).forEach { m ->
            BANDWIDTH.find(m.value)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it > best) best = it }
        }
        return best
    }

    /** 媒体列表的分片地址（跳过注释与空行），相对 base 解析为绝对地址。 */
    fun segmentUris(text: String, baseUri: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUri(baseUri, it) }
            .toList()

    /** 用 EXTINF 时长总和 × 码率估算总字节数；信息不足返回 -1（未知）。 */
    fun estimateBytes(playlistText: String, bandwidthBps: Long): Long {
        if (bandwidthBps <= 0) return -1L
        var seconds = 0.0
        var count = 0
        EXTINF_DURATION.findAll(playlistText).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let { seconds += it; count++ }
        }
        if (count == 0) return -1L
        return (seconds * bandwidthBps / 8.0).toLong()
    }

    /** 相对地址解析：绝对地址原样返回；解析失败退回「目录拼接」的朴素做法。 */
    fun resolveUri(baseUri: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(baseUri).resolve(ref).toString() }
            .getOrElse {
                val dir = baseUri.substringBeforeLast('/', "")
                if (dir.isBlank()) ref else "$dir/${ref.trimStart('/')}"
            }
    }
}

/**
 * HLS 并发分片下载器：替换 Media3 官方 HlsDownloader 的「单连接逐片串行」。
 *
 * 实测这类 CDN 按连接限速（单连接约 0.14 MB/s），8~16 条连接并发可近乎线性提速。
 * 实现：解析主列表→选最高码率变体→把主列表/变体/每个分片作为独立缓存条目，
 * 用固定线程池 + [SegmentThrottle] 全局信号量并行写入同一个 [SimpleCache]
 * （SimpleCache 线程安全，分片 URL 互不相同，天然可并行）。
 *
 * 与 DownloadManager 的契约：[download] 正常返回=完成；抛 IOException=失败（框架会重试）；
 * [cancel] 后尽快以 InterruptedException 返回（框架按停止原因处理）。
 */
@OptIn(UnstableApi::class)
class ParallelHlsDownloader(
    private val request: DownloadRequest,
    private val cache: SimpleCache,
    upstreamFactory: DataSource.Factory,
    private val segmentConcurrency: Int = 12
) : Downloader {

    private val countingFactory = CountingDataSourceFactory(upstreamFactory)
    private val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(countingFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val canceled = AtomicBoolean(false)
    private val activeWriters = Collections.synchronizedList(mutableListOf<CacheWriter>())

    override fun download(listener: Downloader.ProgressListener?) {
        val report = listener ?: object : Downloader.ProgressListener {
            override fun onProgress(downloadedBytes: Long, totalBytes: Long, progressPercent: Float) {}
        }
        val masterUrl = request.uri.toString()
        val masterText = fetchText(masterUrl)
        val variantUrl: String
        val variantText: String
        if (HlsPlaylist.isMaster(masterText)) {
            variantUrl = HlsPlaylist.bestVariantUri(masterText, masterUrl)
                ?: throw IOException("主播放列表里没有可用变体")
            variantText = fetchText(variantUrl)
        } else {
            variantUrl = masterUrl
            variantText = masterText
        }
        val segments = HlsPlaylist.segmentUris(variantText, variantUrl)
        if (segments.isEmpty()) throw IOException("播放列表里没有分片")
        if (canceled.get()) throw InterruptedException()

        // 两级播放列表先入缓存：离线播放（BLOCK_ON_CACHE）要能离线解析清单。
        cacheResource(masterUrl)
        if (variantUrl != masterUrl) cacheResource(variantUrl)
        if (canceled.get()) throw InterruptedException()

        val totalBytes = HlsPlaylist.estimateBytes(variantText, HlsPlaylist.maxBandwidth(masterText))
        val downloaded = AtomicLong(0L)
        val firstError = AtomicReference<IOException?>()
        val queue = ConcurrentLinkedQueue(segments)

        val pool = Executors.newFixedThreadPool(segmentConcurrency.coerceAtLeast(1))
        try {
            repeat(segmentConcurrency.coerceAtLeast(1)) {
                pool.execute {
                    while (!canceled.get() && firstError.get() == null) {
                        val url = queue.poll() ?: break
                        val tookDegraded = try {
                            SegmentThrottle.acquire()
                        } catch (e: InterruptedException) {
                            canceled.set(true); break
                        }
                        try {
                            if (canceled.get()) break
                            cacheResourceWithRetry(url, firstError)
                            val done = downloaded.addAndGet(countingFactory.snapshotAndReset())
                            val percent = if (totalBytes > 0) done.toFloat() / totalBytes else 0f
                            report.onProgress(done, totalBytes, percent)
                        } finally {
                            SegmentThrottle.release(tookDegraded)
                        }
                    }
                    // 收尾把剩余计数并入进度，避免少报。
                    val rest = countingFactory.snapshotAndReset()
                    if (rest > 0) {
                        val done = downloaded.addAndGet(rest)
                        report.onProgress(done, totalBytes, if (totalBytes > 0) done.toFloat() / totalBytes else 0f)
                    }
                }
            }
            pool.shutdown()
            while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                // 分片级重试都在 worker 内部，这里只等；外部 interrupt 会抛 InterruptedException 向上传。
            }
        } finally {
            if (!pool.isTerminated) pool.shutdownNow()
        }

        if (canceled.get()) throw InterruptedException()
        firstError.get()?.let { throw it }
        if (!queue.isEmpty()) throw IOException("仍有分片未下载完成")
    }

    override fun cancel() {
        canceled.set(true)
        synchronized(activeWriters) { activeWriters.forEach { it.cancel() } }
    }

    override fun remove() {
        cancel()
        val masterUrl = request.uri.toString()
        val keys = linkedSetOf(masterUrl)
        // 尽力从缓存里读回清单，把变体与分片键一并清掉；读不回（已离线/已删）只清主列表键。
        runCatching {
            val masterText = readTextFromCache(masterUrl) ?: return@runCatching
            val variantUrl = if (HlsPlaylist.isMaster(masterText)) {
                HlsPlaylist.bestVariantUri(masterText, masterUrl)?.also {
                    keys += it
                    HlsPlaylist.segmentUris(readTextFromCache(it) ?: "", it).forEach(keys::add)
                }
            } else {
                HlsPlaylist.segmentUris(masterText, masterUrl).forEach(keys::add)
                null
            }
            variantUrl
        }
        keys.forEach { key -> runCatching { cache.removeResource(key) } }
    }

    // ---- 内部 ----

    /** 单分片下载：最多 3 次尝试；仍失败则记录首个错误（框架层还会再整任务重试）。 */
    private fun cacheResourceWithRetry(url: String, firstError: AtomicReference<IOException?>) {
        var attempt = 0
        while (!canceled.get()) {
            val writer = CacheWriter(cacheFactory.createDataSource(), DataSpec(Uri.parse(url)), null, null)
            activeWriters.add(writer)
            try {
                writer.cache()
                return
            } catch (e: InterruptedIOException) {
                // CacheWriter.cancel() 会抛这个：视为取消，不再重试。
                return
            } catch (e: Cache.CacheException) {
                // 缓存写入失败（磁盘问题等），重试无意义：记录首个错误。
                if (firstError.get() == null) firstError.compareAndSet(null, e)
                return
            } catch (e: IOException) {
                // 403 = 源站防护触发：全局并发降到 2，且本段退避更久再试。
                val forbidden = e is HttpDataSource.InvalidResponseCodeException && e.responseCode == 403
                if (forbidden) SegmentThrottle.noteForbidden()
                attempt++
                if (attempt >= 3) {
                    firstError.compareAndSet(null, e)
                    return
                }
                try {
                    Thread.sleep(if (forbidden) 2000L * attempt else 400L * attempt)
                } catch (ie: InterruptedException) {
                    canceled.set(true)
                    return
                }
            } finally {
                activeWriters.remove(writer)
            }
        }
    }

    /** 播放列表等资源直接入缓存（失败即整体失败：没有清单没法继续）。 */
    private fun cacheResource(url: String) {
        val writer = CacheWriter(cacheFactory.createDataSource(), DataSpec(Uri.parse(url)), null, null)
        activeWriters.add(writer)
        try {
            writer.cache()
        } catch (e: InterruptedIOException) {
            throw InterruptedException()
        } catch (e: Cache.CacheException) {
            throw IOException("缓存播放列表失败: $url", e)
        } catch (e: IOException) {
            throw IOException("拉取播放列表失败: $url", e)
        } finally {
            activeWriters.remove(writer)
        }
    }

    /** 经 CacheDataSource 读取文本（缓存命中不联网）。 */
    private fun fetchText(url: String): String {
        val dataSource = cacheFactory.createDataSource()
        try {
            dataSource.open(DataSpec(Uri.parse(url), 0, C.LENGTH_UNSET.toLong()))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = dataSource.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /** 只从缓存读（remove 用，不联网）。 */
    private fun readTextFromCache(url: String): String? = runCatching {
        val readOnly = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DataSource.Factory { throw IOException("offline") })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()
        try {
            readOnly.open(DataSpec(Uri.parse(url), 0, C.LENGTH_UNSET.toLong()))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = readOnly.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        } finally {
            runCatching { readOnly.close() }
        }
    }.getOrNull()

    /** 累计上游真实读取字节（缓存命中不计），供进度上报。 */
    private class CountingDataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
        private val counter = AtomicLong(0L)

        fun snapshotAndReset(): Long = counter.getAndSet(0L)

        override fun createDataSource(): DataSource {
            val inner = upstream.createDataSource()
            return object : DataSource {
                override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = inner.read(buffer, offset, length)
                    if (n > 0) counter.addAndGet(n.toLong())
                    return n
                }

                override fun addTransferListener(listener: TransferListener) = inner.addTransferListener(listener)
                override fun getUri(): Uri? = inner.uri
                override fun close() = inner.close()
            }
        }
    }
}

/** 全局分片并发闸门：所有下载任务合计的连接数上限，防止多任务同时 12 并发打爆 CDN/手机。 */
internal object SegmentThrottle {
    /** 实测单连接约 0.14 MB/s、16 连接约 2 MB/s 仍未饱和；24 为多任务共享上限。 */
    const val MAX_CONCURRENT_SEGMENTS = 24

    private val permits = Semaphore(MAX_CONCURRENT_SEGMENTS)

    /** 源站出现持续 403 后退化：全局并发压到 2（保守、单向，进程内不再回升）。 */
    private val degraded = AtomicBoolean(false)
    private val degradedPermits = Semaphore(2)

    /**
     * 实测该 CDN 防护是按 IP 概率/速率性的：偶发单个 403 靠分片级重试就能扛，
     * 不该因此永久降并发；累计 [FORBIDDEN_DEGRADE_THRESHOLD] 次才触发全局退化。
     */
    private const val FORBIDDEN_DEGRADE_THRESHOLD = 3
    private val forbiddenCount = AtomicInteger(0)

    /** 记录一次 403；达到阈值即触发退化。 */
    fun noteForbidden() {
        if (forbiddenCount.incrementAndGet() >= FORBIDDEN_DEGRADE_THRESHOLD) degrade()
    }

    /** 占用闸门；返回是否额外占用了退化闸门名额（release 需原样传回）。 */
    @Throws(InterruptedException::class)
    fun acquire(): Boolean {
        permits.acquire()
        if (degraded.get()) {
            degradedPermits.acquire()
            return true
        }
        return false
    }

    fun release(tookDegradedPermit: Boolean) {
        if (tookDegradedPermit) degradedPermits.release()
        permits.release()
    }

    fun degrade() {
        degraded.set(true)
    }
}
