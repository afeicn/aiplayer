package com.afeicn.aiplayer

import java.net.URI

/**
 * 从详情页 HTML 提取主海报：取第一个像海报的 `<img>`（绝对地址、图片扩展名、
 * 非站点皮肤/标志/二维码类资源），相对地址按页面 URL 补全。找不到返回空串。
 * 下载记录与「最近浏览」的海报回填都凭它。
 */
object PosterFinder {

    private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    /** 懒加载图 src 常是占位小图，真实地址在 data-original/data-src，按优先级取。 */
    private val SRC_ATTRS = listOf("data-original", "data-src", "src")
        .map { Regex("$it\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE) }
    /** 站点皮肤、logo、图标、二维码这类装饰图不是海报。 */
    private val SITE_ASSET = Regex(
        "(?i)(/skin/|/static/|/images/(logo|icon)|logo|icon|favicon|qrcode|erweima|weixin|wechat)"
    )
    private val IMAGE_EXT = Regex("(?i)\\.(jpe?g|png|webp|bmp)([?#]|$)")

    fun find(html: String, pageUrl: String): String {
        for (tag in IMG.findAll(html)) {
            val src = SRC_ATTRS.firstNotNullOfOrNull { it.find(tag.value)?.groupValues?.get(1)?.trim() }
                ?: continue
            if (src.isBlank() || src.startsWith("data:")) continue
            if (SITE_ASSET.containsMatchIn(src)) continue
            val absolute = if (src.startsWith("http")) src else resolve(pageUrl, src)
            if (absolute.isNotBlank() && IMAGE_EXT.containsMatchIn(absolute)) return absolute
        }
        return ""
    }

    private fun resolve(pageUrl: String, src: String): String = runCatching {
        URI(pageUrl).resolve(src).toString()
    }.getOrDefault("")
}
