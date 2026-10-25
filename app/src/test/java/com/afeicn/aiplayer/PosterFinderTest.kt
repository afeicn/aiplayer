package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterFinderTest {

    @Test
    fun skipsSiteLogoAndPicksFirstPoster() {
        val html = """
            <img src="/skin/66scc/images/logo.png" alt="站点">
            <img src="https://tu.66tutup.com:667/2026/3591.jpg" alt="">
            <img src="https://tu.66tutup.com:667/other.jpg" alt="">
        """.trimIndent()
        assertEquals("https://tu.66tutup.com:667/2026/3591.jpg",
            PosterFinder.find(html, "https://www.xb6v.com/dianshiju/guoju/29683.html"))
    }

    @Test
    fun prefersLazyLoadAttributeOverPlaceholderSrc() {
        val html = """<img src="/img/placeholder.gif" data-original="https://cdn.example.org/p/cover.webp">"""
        assertEquals("https://cdn.example.org/p/cover.webp",
            PosterFinder.find(html, "https://site.example/detail/1"))
    }

    @Test
    fun resolvesRelativePosterAgainstPageUrl() {
        val html = """<img src="/upload/2023/cover.jpg">"""
        assertEquals("https://site.example/upload/2023/cover.jpg",
            PosterFinder.find(html, "https://site.example/detail/1"))
    }

    @Test
    fun skipsIconsAndDataUris() {
        val html = """
            <img src="data:image/gif;base64,R0lGOD">
            <img src="https://cdn.example.org/icon.png">
            <img src="https://cdn.example.org/qrcode.jpg">
        """.trimIndent()
        assertEquals("", PosterFinder.find(html, "https://site.example/detail/1"))
    }

    @Test
    fun returnsEmptyWhenNoImages() {
        assertEquals("", PosterFinder.find("<div>没有图</div>", "https://site.example/detail/1"))
    }

    @Test
    fun fallsBackToSrcWhenLazyAttributeIsPlaceholder() {
        // 懒加载属性是 loading.gif 这类占位时，要回退到真 src，不能被第一个属性短路。
        val html = """<img data-original="/img/loading.gif" src="https://cdn.example.org/p/cover.jpg">"""
        assertEquals("https://cdn.example.org/p/cover.jpg",
            PosterFinder.find(html, "https://site.example/detail/1"))
    }

    @Test
    fun unescapesHtmlAmpersandInSrc() {
        // 原始 HTML 里的 &amp; 不反转义，参数名会变成 amp;h，图 404 还会永久落库。
        val html = """<img src="https://cdn.example.org/p/cover.jpg?w=300&amp;h=450">"""
        assertEquals("https://cdn.example.org/p/cover.jpg?w=300&h=450",
            PosterFinder.find(html, "https://site.example/detail/1"))
    }

    @Test
    fun allowsStaticUploadDirectory() {
        // /static/upload/ 是上传目录不是皮肤资源，收窄后的 SITE_ASSET 不该误杀。
        val html = """<img src="/static/upload/2023/cover.jpg">"""
        assertEquals("https://site.example/static/upload/2023/cover.jpg",
            PosterFinder.find(html, "https://site.example/detail/1"))
    }
}
