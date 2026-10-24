package com.afeicn.aiplayer

/**
 * 站点名称的确定性兜底：AI 认不出站名时，从页面 `<title>` 里取品牌段。
 * 只切常见分隔符取第一段，取不到或长度不合理返回 null（再退回域名）。
 */
object SiteNameRules {

    private val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
    private val separators = listOf(" - ", " _ ", " | ", "——", "·", " » ", " >> ")

    fun titleFromHtml(html: String): String? {
        val raw = titleRegex.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val first = separators.fold(raw) { acc, sep -> acc.take(if (acc.contains(sep)) acc.indexOf(sep) else acc.length) }
            .trim()
        return first.takeIf { it.length in 2..30 }
    }
}
