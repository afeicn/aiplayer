package com.afeicn.aiplayer

import java.net.URL

/**
 * 「粘贴剧集详情页 → 解析出集数 → 选集播放」的纯逻辑层。
 *
 * 只依赖字符串与 java.net.URL，不碰 Android 框架，便于纯 JVM 单测（注入假 HTML、不联网）。
 * 页面类型识别与集数提取见 [EpisodeListParser]；集号解析见 [EpisodeNumber] / [EpisodeListParser]。
 *
 * 识别优先级（[EpisodeListParser.extract]）：
 *   1. 帝国CMS：`<div class="playlist">`（或 class/id 含 playlist 的容器）里的 `<a>`，文本即集名；
 *   2. MacCMS：`href` 形如 `/vodplay/<id>-<源>-<集序号>.html`；
 *   3. 通用兜底：同域名 `<a>`，且链接文字匹配「第N集/第N期/第N话」或 href 里带序号。
 * 输出 `[{index, name, url}]`，按集序号排序、按 URL 与集序号去重（同一集号的多个线路只留第一个）。
 */

/** 一条解析出的剧集链接：集序号（从 1 起）、显示名、绝对地址。 */
data class EpisodeLink(val index: Int, val name: String, val url: String)

/** 中文 / 阿拉伯数字集号解析（「第01集」「EP01」「1」「粤语01」「第十一集」）。 */
object EpisodeNumber {
    private val DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    fun parse(raw: String): Int? {
        val s = raw.trim().replace('两', '二').replace('〇', '零')
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        if (s.all { it in DIGITS }) {
            return s.map { DIGITS.getValue(it) }.joinToString("").toIntOrNull()?.takeIf { it > 0 }
        }
        var total = 0
        var section = 0
        var digit = 0
        var any = false
        for (ch in s) when {
            ch in DIGITS -> { digit = DIGITS.getValue(ch); any = true }
            ch == '十' -> { section += (if (digit == 0) 1 else digit) * 10; digit = 0; any = true }
            ch == '百' -> { section += (if (digit == 0) 1 else digit) * 100; digit = 0; any = true }
            ch == '千' -> { section += (if (digit == 0) 1 else digit) * 1000; digit = 0; any = true }
            ch == '万' -> { total += (section + digit) * 10000; section = 0; digit = 0; any = true }
            else -> return null
        }
        return (total + section + digit).takeIf { any && it > 0 }
    }
}

object EpisodeListParser {

    /** 判定为「剧集列表页」所需的最少「像播放页的链接」数。 */
    const val MIN_EPISODES_FOR_LIST = 3
    /** 判定为「站点首页」所需的最少「不同影片内容链接」数（在没有任何集数链接时）。 */
    const val HOMEPAGE_MIN_LINKS = 5

    /**
     * 属性区扫描：`>` 只有出现在引号外才是标签结束。
     * 站点的 `title` 属性里可能带高亮标签（`title="<font color='red'>第01集</font>"`），
     * 旧写法 `([^>]*)` 会提前结束扫描，把属性尾巴当成集名/标题。
     */
    private const val ATTRS = """(?:[^>"']|"[^"]*"|'[^']*')*"""
    private val A_TAG = Regex(
        """<a\b($ATTRS)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""title\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /** 帝国CMS 播放列表容器：class 或 id 里含 playlist 的 div（到第一个 </div> 为止）。 */
    private val PLAYLIST_CONTAINER = Regex(
        """<div\b[^>]*\b(?:class|id)\s*=\s*["'][^"']*playlist[^"']*["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val MACCMS_HREF = Regex("""/vodplay/""", RegexOption.IGNORE_CASE)

    /** href 尾部集序号：`-N.html`（MacCMS `/vodplay/…-N.html`、通用 `-N.html`）。
     *  注意：`_N.html`（如 `index_2.html`）是分页不是集数，裸 `id.html` 是影片详情页，都不算。 */
    private val TRAILING_EP = Regex("""-0*([0-9]+)\.html$""", RegexOption.IGNORE_CASE)
    /** 分页链接：`index_2.html` / `list-3.html` / `page_4.html` / `p5.html`，不是集数。 */
    private val PAGINATION_HREF = Regex("""(?:^|/)(?:index|list|page|p)[_-]?0*[0-9]+\.html$""", RegexOption.IGNORE_CASE)
    /** 帝国CMS DownSys 的 `pathidN=M` 序号（pathid 从 0 起，所以 +1）。 */
    private val PATHID_EP = Regex("""[?&]pathid\d+=0*([0-9]+)""", RegexOption.IGNORE_CASE)
    /** 文字里的「第N集 / 第N期 / 第N话 / 第N回」。 */
    private val CN_EP = Regex("""第\s*([0-9〇零一二两三四五六七八九十百千]+)\s*[集期话回]""")
    /** 文字里的 EP01 / EP 01 / E01。 */
    private val EP_PREFIX = Regex("""(?i)(?:^|[^A-Za-z])[Ee][Pp]?\s*0*([0-9]+)""")
    /** 文字尾部纯数字（如「粤语01」「1」）。 */
    private val TRAILING_DIGITS = Regex("""0*([0-9]+)$""")

    private val STATIC_EXTS = setOf(
        "css", "js", "mjs", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif",
        "woff", "woff2", "ttf", "otf", "eot", "xml", "txt", "pdf", "zip", "gz",
        "mp3", "mp4", "m3u8", "m3u", "ts", "flv", "webm", "mkv", "mov", "wmv", "avi"
    )

    private data class RawEpisode(val name: String, val url: String, val parsedIndex: Int?)

    /** 从文字里解析集序号：「第01集」→1、「EP01」→1、「1」→1、「粤语01」→1、「第十一集」→11。 */
    fun indexFromName(name: String): Int? {
        val text = name.trim()
        if (text.isEmpty()) return null
        CN_EP.find(text)?.let { return EpisodeNumber.parse(it.groupValues[1]) }
        EP_PREFIX.find(text)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 } }
        TRAILING_DIGITS.find(text)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 } }
        return null
    }

    /** 强集名信号：明确的「第N集/期/话/回」或「EP N」。纯数字名（如分页页码“2”）、电影名尾数字（如“流浪地球2”）都不算。 */
    fun isStrongEpisodeName(name: String): Boolean {
        val text = name.trim()
        if (text.isEmpty()) return false
        if (CN_EP.containsMatchIn(text)) return true
        return EP_PREFIX.containsMatchIn(text)
    }

    /** 从 href 里解析集序号（MacCMS `/vodplay/…-N.html`、通用 `-N.html`、`pathidN=M`）。
     *  注意：`_N.html`（分页）、裸 `id.html`（影片详情页）都不是集数，返回 null。 */
    fun indexFromHref(href: String): Int? {
        if (PAGINATION_HREF.containsMatchIn(href)) return null
        val path = href.substringBefore('?').substringBefore('#').trimEnd('/')
        TRAILING_EP.find(path)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 } }
        PATHID_EP.find(href)?.let { return it.groupValues[1].toIntOrNull()?.plus(1)?.takeIf { n -> n > 0 } }
        return null
    }

    /** 按优先级提取剧集链接：帝国CMS → MacCMS → 通用兜底，返回排好序、去重后的列表。 */
    fun extract(html: String, pageUrl: String): List<EpisodeLink> {
        val empire = extractFromPlaylistContainer(html, pageUrl)
        if (empire.isNotEmpty()) return finalize(empire)
        val mac = extractMacCms(html, pageUrl)
        if (mac.isNotEmpty()) return finalize(mac)
        return finalize(extractGeneric(html, pageUrl))
    }

    /** 帝国CMS：playlist 容器里的所有 `<a>`，文本即集名。 */
    private fun extractFromPlaylistContainer(html: String, pageUrl: String): List<RawEpisode> =
        PLAYLIST_CONTAINER.findAll(html)
            .flatMap { m -> linksIn(m.groupValues[1], pageUrl, requireSameDomain = false) }
            .toList()

    /** MacCMS：href 形如 `/vodplay/<id>-<源>-<集序号>.html`，href 序号优先于文字。 */
    private fun extractMacCms(html: String, pageUrl: String): List<RawEpisode> =
        A_TAG.findAll(html).mapNotNull { m ->
            val href = hrefOf(m.groupValues[1])
            if (!MACCMS_HREF.containsMatchIn(href)) return@mapNotNull null
            val abs = resolve(pageUrl, href)
            val name = nameOf(m.groupValues[1], m.groupValues[2])
            val index = indexFromHref(href) ?: indexFromName(name)
            RawEpisode(name, abs, index)
        }.toList()

    /** 通用兜底：同域名 `<a>`，且要么有强集名信号（第N集/EP/纯数字），要么 href 带集序号（pathidN / -N.html）。 */
    private fun extractGeneric(html: String, pageUrl: String): List<RawEpisode> =
        A_TAG.findAll(html).mapNotNull { m ->
            val href = hrefOf(m.groupValues[1])
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("about:", true)) {
                return@mapNotNull null
            }
            val abs = resolve(pageUrl, href)
            if (!sameDomain(pageUrl, abs)) return@mapNotNull null
            val name = nameOf(m.groupValues[1], m.groupValues[2])
            val hrefIndex = indexFromHref(href)
            val nameIndex = if (isStrongEpisodeName(name)) indexFromName(name) else null
            val index = hrefIndex ?: nameIndex
            if (index == null) return@mapNotNull null
            RawEpisode(name, abs, index)
        }.toList()

    /** 从一个 HTML 片段里抽取「像剧集链接」的 `<a>`。 */
    private fun linksIn(html: String, pageUrl: String, requireSameDomain: Boolean): List<RawEpisode> =
        A_TAG.findAll(html).mapNotNull { m ->
            val href = hrefOf(m.groupValues[1])
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("about:", true)) {
                return@mapNotNull null
            }
            val abs = resolve(pageUrl, href)
            if (requireSameDomain && !sameDomain(pageUrl, abs)) return@mapNotNull null
            val name = nameOf(m.groupValues[1], m.groupValues[2])
            val index = indexFromName(name) ?: indexFromHref(href)
            // 必须「文字像集数」或「href 带序号」，否则不是剧集链接。
            if (index == null) return@mapNotNull null
            RawEpisode(name, abs, index)
        }.toList()

    /**
     * 链接显示名：集名优先用内文（它才带得出「第N集/EP」这类集数信号），
     * 但两者都只取纯文本；内文没有集数信号而 `title` 属性有的话，用 `title`。
     */
    private fun nameOf(attrs: String, inner: String): String {
        val innerText = MediaTitle.clean(inner)
        val attrText = MediaTitle.clean(titleOf(attrs))
        if (innerText.isNotEmpty() && (attrText.isEmpty() || indexFromName(innerText) != null)) return innerText
        return attrText.ifEmpty { innerText }
    }

    /** `href` 值（兼容单双引号两种写法）。 */
    private fun hrefOf(attrs: String): String {
        val match = HREF.find(attrs) ?: return ""
        return match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
    }

    /** `title` 属性值（兼容单双引号；值里可以有高亮标签）。 */
    private fun titleOf(attrs: String): String {
        val match = TITLE.find(attrs) ?: return ""
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    /** 去重 + 排序：先按 URL 去重（保序），未解析出集号的按文档顺序补号，再按集号去重，最后排序。 */
    private fun finalize(raw: List<RawEpisode>): List<EpisodeLink> {
        val byUrl = LinkedHashMap<String, RawEpisode>()
        raw.forEach { byUrl.putIfAbsent(it.url, it) }
        val unique = byUrl.values.toList()
        if (unique.isEmpty()) return emptyList()

        val parsedSet = unique.mapNotNull { it.parsedIndex }.toSet()
        var fallback = 1
        val assigned = unique.map { r ->
            val index = r.parsedIndex ?: run {
                var candidate = fallback
                while (candidate in parsedSet) candidate++
                fallback = candidate + 1
                candidate
            }
            EpisodeLink(index, r.name, r.url)
        }

        // 同一集号的多个线路（如粤语/国语）只留第一个。
        val byIndex = LinkedHashMap<Int, EpisodeLink>()
        assigned.forEach { byIndex.putIfAbsent(it.index, it) }
        return byIndex.values.sortedWith(compareBy<EpisodeLink> { it.index }.thenBy { it.name })
    }

    /** 是否「站点首页」：没有任何集数链接时，页面上有大量不同影片的内容链接。 */
    fun looksLikeHomepage(html: String, pageUrl: String, episodeUrls: Set<String>): Boolean {
        val content = A_TAG.findAll(html).mapNotNull { m ->
            val href = HREF.find(m.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("about:", true)) {
                return@mapNotNull null
            }
            val abs = resolve(pageUrl, href)
            if (!sameDomain(pageUrl, abs)) return@mapNotNull null
            if (isStaticAsset(abs)) return@mapNotNull null
            abs
        }.toSet()
        return (content - episodeUrls).size >= HOMEPAGE_MIN_LINKS
    }

    private fun isStaticAsset(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        return ext in STATIC_EXTS
    }

    private fun resolve(pageUrl: String, href: String): String =
        runCatching { WebLinkParser.resolveUrl(pageUrl, href) }.getOrDefault(href)

    private fun sameDomain(pageUrl: String, url: String): Boolean = runCatching {
        URL(pageUrl).host.equals(URL(url).host, ignoreCase = true)
    }.getOrDefault(false)

    /** 取域名（小写 host），用于「按剧名+域名」记上次看到第几集。 */
    fun domainOf(pageUrl: String): String = runCatching { URL(pageUrl).host.lowercase() }.getOrDefault("")

    /** 从页面标题里剥出剧名：先清洗 HTML 残渣，再取第一个「- | _ — ·」之前的部分。 */
    fun dramaTitle(rawTitle: String): String {
        val cleaned = MediaTitle.clean(rawTitle)
        val head = cleaned.split(Regex("""[-|_—·:：]""")).first().trim()
        return head.ifBlank { cleaned }
    }
}
