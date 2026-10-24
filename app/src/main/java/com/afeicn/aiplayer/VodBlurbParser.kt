package com.afeicn.aiplayer

/**
 * 从作品详情页 HTML 里提取剧情简介（纯函数，不碰 Android 框架，方便纯 JVM 单测）。
 *
 * 影视站的详情页简介位置五花八门，按可靠性依次尝试：
 *  1. `<meta name="description">` / `og:description`——绝大多数 CMS 都会把简介写进 meta；
 *  2. 正文里「剧情简介 / 影片简介 / 简介」标记之后的段落——去掉标签后按标记定位，
 *     遇到「演职员 / 上映 / 播放地址」这类下一区块标题即截断。
 *
 * 结果统一过 [MediaTitle.clean]（去标签、解实体、归一空白），超长截到 [MAX_CHARS] 字。
 */
object VodBlurbParser {

    const val MAX_CHARS = 600

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val NAME_ATTR = Regex("""\b(?:name|property)\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val CONTENT_ATTR = Regex("""\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    // Java 的 \s 不含全角空格/不换行空格，站点排版「◎简　　介」用的正是全角空格。
    private val WS = Regex("""[\s 　]+""")

    private val MARKERS = listOf("剧情简介", "影片简介", "电影简介", "内容简介", "简介")
    /**
     * 标记按「字间可夹空白」匹配：不少站点把标题排成「◎简 介」「剧情 简介」
     * 这类拉开字距的样式，直接 indexOf 会漏掉真正的简介段。
     */
    private val MARKER_PATTERNS = MARKERS.map { marker ->
        Regex(marker.map { Regex.escape(it.toString()) }.joinToString("""\s*"""))
    }
    /** 简介段落常见的下一区块标题，遇到即截断，避免把演员表/下载链接当简介。 */
    private val STOPS = listOf(
        "演职员", "演员表", "主演", "上映时间", "上映日期", "首播", "播出时间",
        "播放地址", "下载地址", "在线播放", "在线观看", "幕后花絮", "精彩看点",
        "更新于", "剧情介绍：", "剧情简介："
    )

    fun extract(html: String): String {
        if (html.isBlank()) return ""
        val meta = metaBlurb(html)
        // 不少站点的 meta description 是「◎译名◎年代」式资料块而非剧情；
        // 这种优先去正文找「剧情简介」段落，找不到才退回 meta。
        var candidate = if (meta.isNotBlank() && !looksLikeParamBlock(meta)) meta
            else markerBlurb(html).ifBlank { meta }
        // 仍是资料块时，试着从块里挖「◎简 介」段；挖不出就宁可空着，
        // 也别把一堆参数当简介展示给用户。
        if (looksLikeParamBlock(candidate)) candidate = markerBlurb(candidate)
        if (looksLikeParamBlock(candidate)) candidate = ""
        return finalize(candidate)
    }

    /**
     * CMS 接口字段（vod_blurb / vod_content）的清洗入口：
     * 字段里常是「◎译名◎年代…◎简介 剧情…」整块，先挖剧情段，
     * 纯参数块返回空，让详情页干脆不显示这一节。
     */
    fun fromApiField(raw: String): String {
        if (raw.isBlank()) return ""
        var text = MediaTitle.clean(raw)
        if (looksLikeParamBlock(text)) text = markerBlurb(raw)
        if (looksLikeParamBlock(text)) return ""
        return finalize(text)
    }

    /** 资料块特征：带 ◎ 分隔，或密集出现「译名/年代/产地」这类字段标签（字间空格不算干扰）。 */
    private fun looksLikeParamBlock(text: String): Boolean {
        if (text.contains('◎')) return true
        val compact = WS.replace(text, "").lowercase()
        return PARAM_LABELS.count { compact.contains(it) } >= 3
    }

    private val PARAM_LABELS = listOf(
        "译名", "年代", "产地", "类别", "语言", "上映", "首播", "集数", "片长",
        "导演", "编剧", "又名", "评分", "imdb", "上映日期"
    )

    /** meta description 优先于 og:description；属性顺序（name/content 先后）都兼容。 */
    private fun metaBlurb(html: String): String {
        var og = ""
        for (match in META_TAG.findAll(html)) {
            val tag = match.value
            val name = NAME_ATTR.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                ?.trim()?.lowercase() ?: continue
            if (name != "description" && name != "og:description") continue
            val content = CONTENT_ATTR.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()
            if (content.isBlank()) continue
            if (name == "description") return content
            if (og.isBlank()) og = content
        }
        return og
    }

    /** 去标签后按「简介」类标记定位正文段落，截到下一个区块标题前。 */
    private fun markerBlurb(html: String): String {
        val text = WS.replace(TAG.replace(html, " "), " ")
        for (pattern in MARKER_PATTERNS) {
            for (match in pattern.findAll(text)) {
                var rest = text.substring(match.range.last + 1).trim()
                    .trimStart(':', '：', ' ', '\n')
                for (stop in STOPS) {
                    val cut = rest.indexOf(stop)
                    if (cut >= 1) rest = rest.substring(0, cut)
                }
                rest = rest.trim()
                // 短于 20 字的多半是导航残片（「简介」链接、按钮文字），换下一处匹配。
                if (rest.length >= 20) return rest
            }
        }
        return ""
    }

    private fun finalize(raw: String): String {
        var text = MediaTitle.clean(raw)
        // meta description 常带站名尾巴：「…剧情简介-站名」「…在线观看」。
        for (stop in STOPS) {
            val cut = text.indexOf(stop)
            if (cut >= 20) text = text.substring(0, cut)
        }
        text = text.trim().trimEnd('-', '|', '·', ' ', '/', '【', '［', '[')
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS).trimEnd() + "…" else text
    }
}
