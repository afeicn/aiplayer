package com.afeicn.aiplayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 搜索引擎所需的源仓库抽象：便于纯 JVM 单测注入内存实现，生产用 [OnlineSourceStore]。
 */
interface SourceRegistry {
    fun sources(): List<OnlineVideoSource>
    fun health(id: String): SourceHealth
    fun save(source: OnlineVideoSource)
    fun recordSuccess(id: String)
    fun recordFailure(id: String)
}

/**
 * 源的持久化（名称 + base + 类型 + 搜索规则 + 可选 Referer/User-Agent + 启用开关）与健康度记录。
 * 不内置任何真实源，首次只放一个禁用的占位示例，用户自行添加。
 */
class OnlineSourceStore(context: Context) : SourceRegistry {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("online_video_sources", Context.MODE_PRIVATE)
    private val healthPrefs = context.getSharedPreferences("online_source_health", Context.MODE_PRIVATE)

    override fun sources(): List<OnlineVideoSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        OnlineVideoSource(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            base = item.optString("base"),
                            referer = item.optString("referer"),
                            userAgent = item.optString("userAgent"),
                            enabled = item.optBoolean("enabled", true),
                            type = runCatching { SourceType.valueOf(item.optString("type")) }.getOrDefault(SourceType.VIDEO),
                            searchRule = SearchRule.fromJson(item.optString("searchRule"))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun save(source: OnlineVideoSource) {
        val cleaned = source.copy(
            id = source.id.trim().ifBlank { java.util.UUID.randomUUID().toString() },
            name = source.name.trim(),
            base = source.base.trim()
        )
        val list = sources().filterNot { it.id == cleaned.id } + cleaned
        persist(list)
    }

    fun delete(id: String) = persist(sources().filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) =
        persist(sources().map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** 导出全部源为 JSON 文本（含类型与已探测/手填的搜索规则）。 */
    fun exportJson(): String {
        val array = JSONArray()
        sources().forEach { array.put(sourceToJson(it)) }
        return array.toString()
    }

    /** 从 JSON 文本导入源（逐条叠加，同 id 覆盖），返回导入条数；非法输入返回 null。 */
    fun importJson(raw: String): Int? {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        var count = 0
        val existing = sources().associateBy { it.id }
        val merged = existing.toMutableMap()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val source = OnlineVideoSource(
                id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = item.optString("name"),
                base = item.optString("base"),
                referer = item.optString("referer"),
                userAgent = item.optString("userAgent"),
                enabled = item.optBoolean("enabled", true),
                type = runCatching { SourceType.valueOf(item.optString("type")) }.getOrDefault(SourceType.VIDEO),
                searchRule = SearchRule.fromJson(item.optString("searchRule"))
            )
            if (source.name.isBlank() || source.base.isBlank()) continue
            merged[source.id] = source
            count++
        }
        persist(merged.values.toList())
        return count
    }

    fun ensureDefaults() {
        if (sources().isEmpty()) {
            save(
                OnlineVideoSource(
                    id = "example",
                    name = "示例源（占位，请编辑或删除）",
                    base = "https://example.com",
                    enabled = false
                )
            )
        }
    }

    private fun sourceToJson(source: OnlineVideoSource): JSONObject = JSONObject().apply {
        put("id", source.id)
        put("name", source.name)
        put("base", source.base)
        put("referer", source.referer)
        put("userAgent", source.userAgent)
        put("enabled", source.enabled)
        put("type", source.type.name)
        put("searchRule", SearchRule.toJson(source.searchRule))
    }

    private fun persist(list: List<OnlineVideoSource>) {
        val array = JSONArray()
        list.forEach { source ->
            array.put(
                sourceToJson(source)
            )
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    // ---- 健康度 ----

    override fun health(id: String): SourceHealth {
        val raw = healthPrefs.getString(id, null) ?: return SourceHealth()
        return runCatching {
            val item = JSONObject(raw)
            SourceHealth(
                consecutiveFailures = item.optInt("consecutiveFailures", 0),
                cooldownUntil = item.optLong("cooldownUntil", 0L),
                lastSuccessAt = item.optLong("lastSuccessAt", 0L),
                lastFailureAt = item.optLong("lastFailureAt", 0L)
            )
        }.getOrDefault(SourceHealth())
    }

    override fun recordSuccess(id: String) = saveHealth(id, SourceHealthPolicy.recordSuccess(System.currentTimeMillis()))

    override fun recordFailure(id: String) = saveHealth(
        id,
        SourceHealthPolicy.recordFailure(System.currentTimeMillis(), health(id))
    )

    fun recover(id: String) = saveHealth(id, SourceHealthPolicy.recover())

    private fun saveHealth(id: String, value: SourceHealth) {
        healthPrefs.edit().putString(
            id,
            JSONObject().apply {
                put("consecutiveFailures", value.consecutiveFailures)
                put("cooldownUntil", value.cooldownUntil)
                put("lastSuccessAt", value.lastSuccessAt)
                put("lastFailureAt", value.lastFailureAt)
            }.toString()
        ).apply()
    }

    private companion object {
        const val KEY_SOURCES = "sources_v1"
    }
}

data class SourceSearchOutcome(
    val source: OnlineVideoSource,
    val success: Boolean,
    val latencyMs: Long,
    val itemCount: Int,
    val items: List<VodItem> = emptyList(),
    val error: String? = null,
    val detailUrls: List<String> = emptyList(),
    val aiAssisted: Boolean = false
)

data class SearchReport(val hits: List<SourceHit>, val outcomes: List<SourceSearchOutcome>)

/** 多源并行搜索（HTML 探测 + MacCMS JSON 回退）+ 连通性测试。 */
class OnlineSearchEngine(private val store: OnlineSourceStore) {

    fun detailUrl(source: OnlineVideoSource, keyword: String): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return "${source.normalizedBase}/api.php/provide/vod/?ac=detail&wd=$encoded"
    }

    fun testUrl(source: OnlineVideoSource): String {
        val encoded = URLEncoder.encode("测试", "UTF-8")
        return "${source.normalizedBase}/api.php/provide/vod/?ac=detail&wd=$encoded"
    }

    /** 并行查所有「启用且不在冷却期」的源；单源失败不影响其它源，并记入健康度。给了 [aiFallback] 时确定性失败后走 AI 兜底。 */
    suspend fun search(
        keyword: String,
        now: Long = System.currentTimeMillis(),
        aiFallback: AiSearchFallback? = null
    ): SearchReport {
        val raw = CrossSourceSearchEngine.search(store, keyword, HttpSearchTransport(), now, aiFallback)
        val outcomes = raw.map { outcome ->
            SourceSearchOutcome(
                source = outcome.source,
                success = outcome.success,
                latencyMs = outcome.latencyMs,
                itemCount = outcome.itemCount,
                items = outcome.hits.map { it.item },
                error = outcome.error,
                detailUrls = outcome.hits.map { it.detailUrl },
                aiAssisted = outcome.aiAssisted
            )
        }
        return SearchReport(
            hits = raw.flatMap { it.hits },
            outcomes = outcomes
        )
    }

    /** 「测试连通性」：按源规则搜一次「测试」，给出耗时与条数（HTML 探测或 JSON 接口）。 */
    suspend fun testConnectivity(source: OnlineVideoSource): SourceSearchOutcome = withContext(Dispatchers.IO) {
        val outcome = CrossSourceSearchEngine.searchSource(source, store, "测试", HttpSearchTransport())
        SourceSearchOutcome(
            source = source,
            success = outcome.success,
            latencyMs = outcome.latencyMs,
            itemCount = outcome.itemCount,
            items = outcome.hits.map { it.item },
            error = outcome.error,
            detailUrls = outcome.hits.map { it.detailUrl }
        )
    }
}

/**
 * 一次搜索结果的纯分类（可单测，不碰网络）：
 * 只有网络错误/超时/非 JSON/HTTP 错误才算失败；
 * 合法 JSON 但空列表算「该源正常，只是没这片」，不误伤源健康度。
 */
fun classifySearchOutcome(source: OnlineVideoSource, fetched: Result<String>, latencyMs: Long): SourceSearchOutcome {
    val text = fetched.getOrElse { error ->
        return SourceSearchOutcome(source, false, latencyMs, 0, error = error.message ?: "网络错误")
    }
    if (!MacCmsParser.looksLikeValidResponse(text)) {
        return SourceSearchOutcome(source, false, latencyMs, 0, error = "返回的不是合法 JSON")
    }
    val items = MacCmsParser.parseSearchResponse(text)
    return SourceSearchOutcome(source, true, latencyMs, items.size, items)
}

/** 通用 GET 文本抓取，带超时与自定义请求头（Referer / User-Agent）。 */
fun fetchHttpText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): String {
    val connection = (URL(url).openConnection() as HttpURLConnection)
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code" }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
