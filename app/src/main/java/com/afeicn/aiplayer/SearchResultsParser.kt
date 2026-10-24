package com.afeicn.aiplayer

import java.net.URL

/**
 * 三种 CMS「搜索页 → 结果条目」的纯解析层。只依赖字符串与 java.net.URL，便于纯 JVM 单测
 * （注入假 HTML、不联网）。
 *
 * 入口 [SearchResultsParser.extract] 按 HTML 特征自动识别站点类型：
 *  - 帝国CMS：`<li class="post …">` 块，标题在 `<h2><a href="/xx/ID.html">`，
 *    年份/产地/备注在 `entry_post` 的 `◎年　　代　2023◎产　　地　中国大陆` 里；
 *  - MacCMS HTML：`<a href>` 含 `/voddetail/` 或 `vod/detail/id/` 详情链接；
 *  - WordPress：`<h2 class="entry-title">` 或 `<article>` 里的文章链接。
 */

data class SearchResultItem(
    val title: String,
    val url: String,
    val year: String = "",
    val area: String = "",
    val remarks: String = "",
    val pic: String = ""
)

object SearchResultsParser {

    /**
     * 属性区扫描：`>` 只有出现在引号外才是标签结束。
     *
     * 关键：站点的 `title` 属性里常带高亮标签（`title="<font color='red'>我不是大师</font>[全集]"`），
     * 旧写法 `([^>]*)` 会在属性值里的 `>` 处提前结束扫描，把 `…[全集]">…` 当成链接文字，
     * 于是脏标题（`我不是大师[全集]">我不是大师[全集]`）被落库。
     */
    private const val ATTRS = """(?:[^>"']|"[^"]*"|'[^']*')*"""
    private val A_TAG = Regex("""<a\b($ATTRS)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TITLE_ATTR = Regex("""title\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val H2 = Regex("""<h2\b[^>]*>(.*?)</h2>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LI_POST = Regex("""<li\b[^>]*\bclass\s*=\s*["'][^"']*\bpost\b[^"']*["'][^>]*>(.*?)</li>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IMG = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""◎\s*年\s*(?:代)?\s*[:：]?\s*([0-9]{4})""")
    private val AREA = Regex("""◎\s*产\s*地\s*[:：]?\s*([^◎<]{1,30})""")
    private val ENTRY = Regex("""entry_post|entry-content|post-content""", RegexOption.IGNORE_CASE)

    private val STATIC_EXTS = setOf(
        "css", "js", "mjs", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif",
        "woff", "woff2", "ttf", "otf", "eot", "xml", "txt", "pdf", "zip", "gz", "mp3", "mp4", "m3u8"
    )

    /** 按 HTML 特征识别站点类型并解析结果条目。 */
    fun extract(html: String, baseUrl: String): List<SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val empire = extractEmpireCms(html, baseUrl)
        if (empire.isNotEmpty()) return empire
        val mac = extractMacCms(html, baseUrl)
        if (mac.isNotEmpty()) return mac
        return extractWordPress(html, baseUrl)
    }

    /** 帝国CMS 结果页（`<li class="post">` 列表）。 */
    fun extractEmpireCms(html: String, baseUrl: String): List<SearchResultItem> {
        val blocks = LI_POST.findAll(html).mapNotNull { itemFromBlock(it.value, baseUrl) }.toList()
        if (blocks.isNotEmpty()) return dedupe(blocks)
        // 兜底：页面上任何 `<h2><a href="/xx/ID.html">` 都是详情链接。
        val fallback = H2.findAll(html).mapNotNull { h2 ->
            val a = A_TAG.find(h2.groupValues[1]) ?: return@mapNotNull null
            val href = hrefOf(a.groupValues[1])
            if (!looksLikeDetailLink(href)) return@mapNotNull null
            SearchResultItem(nameOf(a.groupValues[1], a.groupValues[2]), resolve(baseUrl, href))
        }
        return dedupe(fallback.toList())
    }

    /** MacCMS HTML 搜索页：`/voddetail/…` 或 `vod/detail/id/…` 链接。 */
    fun extractMacCms(html: String, baseUrl: String): List<SearchResultItem> {
        val items = A_TAG.findAll(html).mapNotNull { m ->
            val href = hrefOf(m.groupValues[1])
            val isMac = href.contains("/voddetail/", ignoreCase = true) ||
                href.contains("/vod/detail/", ignoreCase = true) ||
                href.contains("vod/detail/id/", ignoreCase = true) ||
                href.contains("/index.php/vod/detail/", ignoreCase = true)
            if (!isMac) return@mapNotNull null
            val title = nameOf(m.groupValues[1], m.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            SearchResultItem(title, resolve(baseUrl, href))
        }
        return dedupe(items.toList())
    }

    /** WordPress 搜索页：`<h2 class="entry-title">` 或 `<article>` 里的文章链接。 */
    fun extractWordPress(html: String, baseUrl: String): List<SearchResultItem> {
        val items = A_TAG.findAll(html).mapNotNull { m ->
            val href = hrefOf(m.groupValues[1])
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("about:", true)) {
                return@mapNotNull null
            }
            if (!looksLikeDetailLink(href)) return@mapNotNull null
            val title = nameOf(m.groupValues[1], m.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            SearchResultItem(title, resolve(baseUrl, href))
        }
        return dedupe(items.toList())
    }

    /** 判定一个 href 是否「指向详情页」（数字 id 的 .html、MacCMS 详情路径、或普通文章路径）。 */
    fun looksLikeDetailLink(href: String): Boolean {
        val path = href.substringBefore('?').substringBefore('#').trimEnd('/')
        if (path.isBlank()) return false
        if (href.startsWith("javascript:", true) || href.startsWith("about:", true) ||
            href.startsWith("magnet:", true) || href.startsWith("mailto:", true)
        ) return false
        if (path.startsWith("/e/tags") || path.contains("respond") || path.contains("/feed")) return false
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext in STATIC_EXTS) return false
        // 数字 id 的 .html（帝国CMS 详情 / 文章页）
        if (Regex("""/[0-9]+\.html$""").containsMatchIn(path)) return true
        // MacCMS 详情路径
        if (path.contains("/voddetail/", true) || path.contains("vod/detail/id/", true)) return true
        // WordPress 普通文章路径：至少两段、带 .html/.htm 或无扩展名的深层路径（排除纯目录导航）。
        if (path.count { it == '/' } >= 1 && Regex("""\.(html?|htm)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return true
        return false
    }

    /** 探测判定用：结果页正文里是否回显了关键词（含被 `<font>` 高亮拆开的情况）。 */
    fun containsKeyword(html: String, keyword: String): Boolean {
        if (keyword.isBlank()) return true
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return true
        // 直接包含（忽略大小写）
        if (html.lowercase().contains(needle)) return true
        // 关键词可能被高亮标签拆成多段（如 <font>流浪</font>地球）
        val withoutTags = TAG.replace(html, "")
        return withoutTags.lowercase().contains(needle)
    }

    private fun itemFromBlock(block: String, baseUrl: String): SearchResultItem? {
        val h2 = H2.find(block) ?: return null
        val a = A_TAG.find(h2.groupValues[1]) ?: return null
        val href = hrefOf(a.groupValues[1])
        if (href.isEmpty()) return null
        val title = nameOf(a.groupValues[1], a.groupValues[2])
        if (title.isBlank()) return null
        val bodyText = TAG.replace(block, " ").replace('　', ' ')
        val year = YEAR.find(bodyText)?.groupValues?.get(1)?.trim().orEmpty()
        val area = AREA.find(bodyText)?.groupValues?.get(1)?.trim().orEmpty()
        val pic = IMG.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        return SearchResultItem(title, resolve(baseUrl, href), year, area, remarks = "", pic = pic)
    }

    /**
     * 链接显示名：优先 `<a>` 的 `title` 属性，其次内文；两者都只取纯文本（去标签 / 解实体 / 去重复）。
     *
     * 帝国CMS 的搜索结果把高亮标签塞进 `title` 属性（`title="<font>我不是大师</font>[全集]"`），
     * 取 `title` 属性比取内文更稳（内文可能只剩一张封面图）。
     */
    private fun nameOf(attrs: String, inner: String): String {
        val attrTitle = MediaTitle.clean(titleOf(attrs))
        if (attrTitle.isNotEmpty()) return attrTitle
        return MediaTitle.clean(inner)
    }

    /** `href` 值（兼容单双引号两种写法）。 */
    private fun hrefOf(attrs: String): String {
        val match = HREF.find(attrs) ?: return ""
        return match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
    }

    /** `title` 属性值（兼容单双引号；值里可以有高亮标签）。 */
    private fun titleOf(attrs: String): String {
        val match = TITLE_ATTR.find(attrs) ?: return ""
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun resolve(baseUrl: String, href: String): String =
        runCatching { URL(URL(baseUrl), href).toString() }.getOrDefault(href)

    private fun dedupe(items: List<SearchResultItem>): List<SearchResultItem> {
        val seen = LinkedHashMap<String, SearchResultItem>()
        items.forEach { seen.putIfAbsent(it.url, it) }
        return seen.values.filter { it.title.isNotBlank() }
    }
}
