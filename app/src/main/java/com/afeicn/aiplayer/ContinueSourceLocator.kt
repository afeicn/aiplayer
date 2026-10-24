package com.afeicn.aiplayer

import java.net.URI

/**
 * 「继续下载」的兜底定位：老数据没有 [OfflineItem.detailUrl]，只有剧名 + 站点（真实数据把站点地址
 * 写进了 `sourceName`，如 `https://cms.example.com/`）。
 *
 * 做法完全复用现有的站内搜索链路，不新开一套：
 *   [SourceProber.probe]（首页表单探测 + 标准路径兜底）→ [SearchResultsParser]（结果页解析）
 *   → 按剧名匹配挑最像的一条 → 返回详情页地址。
 *
 * 匹配规则（[pick]）：先比清洗后的完整剧名，再比去掉 `[全集]`/`（第一季）` 的主干，
 * 最后才是包含关系；同域名的结果优先。全都不像就返回 null——宁可提示「重新搜索」，
 * 也不能拿别的剧的详情页顶上去。
 */
data class SourceLookup(
    val detailUrl: String,
    val title: String,
    val sourceName: String,
    val baseUrl: String
)

object ContinueSourceLocator {

    /** 兜底定位最多顺带试几个已配置的其他源（避免一次点击等太久）。 */
    const val MAX_EXTRA_SOURCES = 3

    /**
     * 从 `sourceName` 里取站点基址。真实数据里 `sourceName` 就是站点地址
     * （`https://cms.example.com/`），也兼容裸域名（`cms.example.com`）。
     * 展示用的源名（如站名简称）取不出基址，返回空串。
     */
    fun baseUrlOf(source: String): String {
        val raw = source.trim()
        if (raw.isEmpty()) return ""
        val candidate = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> return ""
        }
        return runCatching {
            val uri = URI(candidate)
            val host = uri.host?.lowercase().orEmpty()
            if (host.isBlank()) return@runCatching ""
            val authority = if (uri.port > 0) "$host:${uri.port}" else host
            "${uri.scheme?.lowercase() ?: "https"}://$authority"
        }.getOrDefault("")
    }

    /** 搜索时依次尝试的关键词：剧名主干（去掉 `[全集]` 等）优先，完整清洗后的剧名兜底。 */
    fun keywordsOf(title: String): List<String> {
        val clean = MediaTitle.clean(title)
        if (clean.isEmpty()) return emptyList()
        val core = MediaTitle.core(clean)
        return listOf(core, clean).filter { it.isNotEmpty() }.distinct()
    }

    /** 候选源：先由 `sourceName` 推出站点，再顺带试已配置的其它源（最多 [MAX_EXTRA_SOURCES] 个）。 */
    fun candidateSources(
        target: ContinueTarget,
        extraSources: List<OnlineVideoSource> = emptyList(),
        maxExtraSources: Int = MAX_EXTRA_SOURCES
    ): List<OnlineVideoSource> {
        val base = baseUrlOf(target.sourceName)
        val derived = base.takeIf { it.isNotBlank() }?.let {
            OnlineVideoSource(
                id = "continue-lookup",
                name = target.sourceName.trim().ifBlank { it },
                base = it,
                referer = "$it/",
                type = if (target.isAudio) SourceType.AUDIO else SourceType.VIDEO
            )
        }
        val extras = extraSources
            .filter { it.enabled && it.normalizedBase.isNotBlank() }
            .filter { derived == null || !it.normalizedBase.equals(derived.normalizedBase, ignoreCase = true) }
            .take(maxExtraSources)
        return listOfNotNull(derived) + extras
    }

    /**
     * 定位详情页：已有 [ContinueTarget.detailUrl] 直接返回（调用方据此跳过搜索）；
     * 否则按剧名 + 站点搜一次，挑最匹配的结果。
     *
     * 全程只依赖 [SearchTransport]（单测注入假实现，不联网）。
     */
    fun locate(
        target: ContinueTarget,
        transport: SearchTransport,
        extraSources: List<OnlineVideoSource> = emptyList()
    ): SourceLookup? {
        val title = MediaTitle.clean(target.title)
        if (title.isEmpty()) return null
        if (!ContinueDownloadRules.needsLookup(target.detailUrl)) {
            return SourceLookup(target.detailUrl.trim(), title, target.sourceName, baseUrlOf(target.sourceName))
        }
        val keywords = keywordsOf(title)
        if (keywords.isEmpty()) return null
        for (source in candidateSources(target, extraSources)) {
            for (keyword in keywords) {
                val items = runCatching {
                    SourceProber.probe(source, transport, keyword = keyword).items
                }.getOrDefault(emptyList())
                val picked = pick(items, title, source.normalizedBase) ?: continue
                return SourceLookup(
                    detailUrl = picked.url,
                    title = picked.title,
                    sourceName = source.name.ifBlank { target.sourceName },
                    baseUrl = source.normalizedBase
                )
            }
        }
        return null
    }

    /** 从搜索结果里挑最像这部剧的一条；都不像返回 null（不硬凑）。 */
    fun pick(items: List<SearchResultItem>, title: String, baseUrl: String = ""): SearchResultItem? =
        items.filter { it.url.startsWith("http", ignoreCase = true) }
            .map { it to score(it, title, baseUrl) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    /** 匹配分：完整剧名 > 剧名主干 > 包含关系；同域名加权。 */
    fun score(candidate: SearchResultItem, title: String, baseUrl: String = ""): Int {
        val wanted = MediaTitle.normalize(title)
        val found = MediaTitle.normalize(candidate.title)
        if (wanted.isEmpty() || found.isEmpty()) return 0
        val base = when {
            found == wanted -> 100
            else -> {
                val foundCore = MediaTitle.normalize(MediaTitle.core(candidate.title))
                val wantedCore = MediaTitle.normalize(MediaTitle.core(title))
                when {
                    foundCore.isNotEmpty() && foundCore == wantedCore -> 80
                    foundCore.length >= 2 && wantedCore.length >= 2 &&
                        (foundCore.contains(wantedCore) || wantedCore.contains(foundCore)) -> 50
                    found.length >= 2 && wanted.length >= 2 &&
                        (found.contains(wanted) || wanted.contains(found)) -> 40
                    else -> 0
                }
            }
        }
        if (base == 0) return 0
        return if (baseUrl.isNotBlank() && sameHost(candidate.url, baseUrl)) base + 5 else base
    }

    private fun sameHost(url: String, baseUrl: String): Boolean = runCatching {
        val a = URI(url).host?.lowercase()
        val b = URI(baseUrl).host?.lowercase()
        !a.isNullOrBlank() && a == b
    }.getOrDefault(false)
}
