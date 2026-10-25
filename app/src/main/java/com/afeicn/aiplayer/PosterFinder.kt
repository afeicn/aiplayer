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
    /** 站点皮肤、logo、图标、二维码这类装饰图不是海报。只拦真正的皮肤路径与资源文件名，别误杀 /static/upload/ 这类上传目录。 */
    private val SITE_ASSET = Regex(
        "(?i)(/skin/|/static/(?:img|images?|js|css|assets?)/|/images/(logo|icon)|/(?:logo|icon|favicon|qrcode|erweima|weixin|wechat)[._])"
    )
    private val IMAGE_EXT = Regex("(?i)\\.(jpe?g|png|webp|bmp)([?#]|$)")

    fun find(html: String, pageUrl: String): String {
        for (tag in IMG.findAll(html)) {
            // 逐个属性试到可用为止：懒加载属性常是 loading.gif/javascript: 这类占位，
            // 只取「第一个存在的属性」会把真 src 短路掉。
            for (attr in SRC_ATTRS) {
                val raw = attr.find(tag.value)?.groupValues?.get(1)?.trim() ?: continue
                if (raw.isBlank() || raw.startsWith("data:")) continue
                if (SITE_ASSET.containsMatchIn(raw)) continue
                // 原始 HTML 里的 &amp; 不反转义，参数名会变成 amp;h，图 404 还会永久落库。
                val src = raw.replace("&amp;", "&").replace("&#38;", "&")
                val absolute = if (src.startsWith("http")) src else resolve(pageUrl, src)
                if (absolute.isNotBlank() && IMAGE_EXT.containsMatchIn(absolute)) return absolute
            }
        }
        return ""
    }

    private fun resolve(pageUrl: String, src: String): String = runCatching {
        URI(pageUrl).resolve(src).toString()
    }.getOrDefault("")
}
