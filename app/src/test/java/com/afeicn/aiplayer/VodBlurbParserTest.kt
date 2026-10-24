package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页简介提取单测：meta description / og:description / 「简介」标记三种来源，
 * 以及站名尾巴截断、超长截断、无简介返回空。
 */
class VodBlurbParserTest {

    @Test
    fun metaDescription优先且解实体() {
        val html = """
            <html><head>
            <meta name="description" content="庆余年第二季&mdash;&mdash;范闲重返京都，揭开身世谜团。在线观看-6v电影">
            </head><body>剧情简介：这段不该被用到</body></html>
        """.trimIndent()
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.startsWith("庆余年第二季"))
        assertTrue(blurb.contains("范闲"))
        // 站名尾巴「在线观看」「-6v电影」被截掉。
        assertTrue(!blurb.contains("6v电影"))
        assertTrue(!blurb.contains("在线观看"))
    }

    @Test
    fun 属性顺序颠倒也能取到() {
        val html = """<meta content="少年叶平安为复仇入局，以催眠之术撬动旧案。" name="description">"""
        assertEquals("少年叶平安为复仇入局，以催眠之术撬动旧案。", VodBlurbParser.extract(html))
    }

    @Test
    fun 没有description时用ogDescription() {
        val html = """<meta property="og:description" content="一支小队深入荒漠寻找失踪的科考队，却发现被掩埋的古城。">"""
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.contains("古城"))
    }

    @Test
    fun 正文简介标记定位并截到下一区块() {
        val html = """
            <div class="plot"><h2>剧情简介</h2>
            <p>大学毕业生张小斐回到家乡，接手父亲的旧书店，在旧书里发现一封五十年前的信。</p></div>
            <div class="cast"><h2>演职员</h2><p>张小斐 饰 店主</p></div>
        """.trimIndent()
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.startsWith("大学毕业生"))
        assertTrue(blurb.contains("五十年前的信"))
        assertTrue(!blurb.contains("演职员"))
        assertTrue(!blurb.contains("饰 店主"))
    }

    @Test
    fun 标记后是导航残片则不采用() {
        val html = """<a href="/about">简介</a><a href="/cast">演员表</a>"""
        assertEquals("", VodBlurbParser.extract(html))
    }

    @Test
    fun meta是资料块时优先正文剧情段落() {
        val html = """
            <head><meta name="description" content="◎译 名 庆余年2◎年 代 2024◎产 地 中国大陆-6v电影"></head>
            <body><div class="plot">剧情简介：范闲死而复生，一路追查春祭背后的阴谋，最终揭开身世真相。</div>
            <div>演员表：张若昀</div></body>
        """.trimIndent()
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.startsWith("范闲"))
        assertTrue(blurb.contains("身世真相"))
        assertTrue(!blurb.contains("演员表"))
        assertTrue(!blurb.contains("◎"))
    }

    @Test
    fun 标记字间夹空格也能定位() {
        // 6v 系站点正文是「◎简 介」这种拉开字距的排版，简介后紧跟【下载地址】。
        val html = """
            <head><meta name="description" content="◎译 名 庆余年2◎年 代 2024◎简 介 该剧改编自猫腻同名畅销小说，范闲率使团回归。-6v电影"></head>
            <body><p>◎简　　介　该剧改编自猫腻同名畅销小说，承接上季，范闲相约城中众商贾，以售卖&ldquo;库债&rdquo;筹集银两。</p>
            <h3>【下载地址】</h3><ul><li>磁力：全集打包.mkv</li></ul></body>
        """.trimIndent()
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.startsWith("该剧改编自"))
        assertTrue(blurb.contains("“库债”"))
        assertTrue(!blurb.contains("◎"))
        assertTrue(!blurb.contains("磁力"))
        assertTrue(!blurb.contains("下载地址"))
    }

    @Test
    fun 正文无标记时从资料块里挖简介段() {
        val html = """
            <head><meta name="description" content="◎译 名 庆余年2◎年 代 2024◎集 数 36◎简 介 范闲与林婉儿如愿大婚，接手内库却发现负债累累。-6v电影"></head>
            <body><p>只有播放列表没有简介段落</p></body>
        """.trimIndent()
        val blurb = VodBlurbParser.extract(html)
        assertTrue(blurb.startsWith("范闲与林婉儿"))
        assertTrue(!blurb.contains("◎"))
    }

    @Test
    fun 无圆点符号的参数块也识别并拒绝() {
        val html = """
            <head><meta name="description" content="译名 某片 年代 2024 产地 中国大陆 类别 剧情 语言 汉语普通话 集数 36"></head>
            <body><p>正文里没有任何简介段落</p></body>
        """.trimIndent()
        assertEquals("", VodBlurbParser.extract(html))
    }

    @Test
    fun API字段参数块里挖出剧情段() {
        val field = "◎译 名 某剧◎年 代 2024◎集 数 12◎简 介 少女叶平安为复仇入局，以催眠之术撬动十年旧案。"
        val blurb = VodBlurbParser.fromApiField(field)
        assertTrue(blurb.startsWith("少女叶平安"))
        assertTrue(!blurb.contains("◎"))
    }

    @Test
    fun API字段是正常剧情时原样保留() {
        assertEquals(
            "检察官韩明遭遇一连串棘手案件，法与情的抉择接踵而至。",
            VodBlurbParser.fromApiField("检察官韩明遭遇一连串棘手案件，法与情的抉择接踵而至。")
        )
    }

    @Test
    fun 纯参数块没有简介时宁可返回空() {
        val html = """
            <head><meta name="description" content="◎译 名 某片◎年 代 2024◎产 地 中国大陆-6v电影"></head>
            <body><p>什么简介都没有</p></body>
        """.trimIndent()
        assertEquals("", VodBlurbParser.extract(html))
    }

    @Test
    fun 超长简介截断加省略号() {
        // 前后半不同的样本：避免被 MediaTitle.clean 的「重复折叠」先缩短。
        val long = "雨".repeat(350) + "雪".repeat(350)
        val html = """<meta name="description" content="$long">"""
        val blurb = VodBlurbParser.extract(html)
        assertEquals(VodBlurbParser.MAX_CHARS + 1, blurb.length)
        assertTrue(blurb.endsWith("…"))
    }

    @Test
    fun 空页面与无简介页面返回空() {
        assertEquals("", VodBlurbParser.extract(""))
        assertEquals("", VodBlurbParser.extract("<html><body><p>只有播放列表</p></body></html>"))
    }
}
