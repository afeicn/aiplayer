package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 兜底解析的网络 + 编排层。
 *
 * [LlmTransport] 抽象「一次对话补全」：给 system+user，返回助手文本内容。真实实现
 * [HttpLlmTransport] 走 DeepSeek 的 OpenAI 兼容接口（`/chat/completions`，Bearer 鉴权，
 * 强制 JSON 输出）；单测注入假 transport 返回固定文本，不联网。
 *
 * [DeepSeekClient] 只做编排：组 prompt → transport → [DeepSeekResponseParser] 校验。
 */

/** 一次对话补全抽象：返回助手回复的文本内容（失败抛异常）。 */
fun interface LlmTransport {
    fun complete(systemPrompt: String, userPrompt: String, model: String, apiKey: String): String
}

class DeepSeekClient(
    private val transport: LlmTransport,
    private val apiKey: String,
    private val model: String
) {
    /** 搜索结果页 → 校验后的影片链接；Key 空或异常返回空。 */
    fun extractResults(keyword: String, html: String, base: String): List<ExtractedResult> {
        if (apiKey.isBlank()) return emptyList()
        val raw = runCatching {
            transport.complete(DeepSeekPrompt.searchSystem(), DeepSeekPrompt.searchUser(keyword, html), model, apiKey)
        }.getOrNull() ?: return emptyList()
        return DeepSeekResponseParser.parseResults(raw, base)
    }

    /** 详情页 → 校验后的剧集链接；Key 空或异常返回空。 */
    fun extractEpisodes(title: String, html: String, base: String): List<ExtractedEpisode> {
        if (apiKey.isBlank()) return emptyList()
        val raw = runCatching {
            transport.complete(DeepSeekPrompt.episodeSystem(), DeepSeekPrompt.episodeUser(title, html), model, apiKey)
        }.getOrNull() ?: return emptyList()
        return DeepSeekResponseParser.parseEpisodes(raw, base)
    }

    /**
     * 用户粘的页面 HTML（首页/列表/详情/播放页皆可）→ 候选搜索规则（带 `{kw}` 模板、同域）。
     *
     * 返回的只是**候选**：调用方必须逐条实测后才能认定源可用，别把「模型给了模板」当成「源可用」。
     */
    fun suggestSearchRules(pageUrl: String, html: String, siteBase: String): List<SearchRule> {
        if (apiKey.isBlank() || html.isBlank()) return emptyList()
        val raw = runCatching {
            transport.complete(DeepSeekPrompt.searchRuleSystem(), DeepSeekPrompt.searchRuleUser(pageUrl, html), model, apiKey)
        }.getOrNull() ?: return emptyList()
        return DeepSeekResponseParser.parseSearchRules(raw, siteBase)
    }

    /** 站点页面 → 简短站名（展示用）；Key 空、页面空或模型认不出返回 null，调用方自行退回标题/域名。 */
    fun suggestSiteName(pageUrl: String, html: String): String? {
        if (apiKey.isBlank() || html.isBlank()) return null
        val raw = runCatching {
            transport.complete(DeepSeekPrompt.siteNameSystem(), DeepSeekPrompt.siteNameUser(pageUrl, html), model, apiKey)
        }.getOrNull() ?: return null
        return DeepSeekResponseParser.parseSiteName(raw)
    }
}

/** DeepSeek OpenAI 兼容接口真实实现：POST JSON + Bearer，返回 choices[0].message.content。 */
class HttpLlmTransport(
    private val endpoint: String = "https://api.deepseek.com/chat/completions",
    private val timeoutMs: Int = 20_000
) : LlmTransport {
    override fun complete(systemPrompt: String, userPrompt: String, model: String, apiKey: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("max_tokens", 2000)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
        }.toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            check(code in 200..299) { "DeepSeek HTTP $code" }
            return JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
