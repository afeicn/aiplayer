package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户粘贴的地址」→「站点根」的纯逻辑单测。
 *
 * 核心命题：不管粘的是首页、列表页、详情页还是播放页，落库和拼候选规则用的都必须是站点根，
 * 否则候选规则会拼成 `…/20830.html/e/search/?keyboard=…` 这种必然 404 的地址。
 */
class SourceAddressTest {

    @Test
    fun playPageAddressCollapsesToSiteRoot() {
        val address = SourceAddressRules.analyze("https://cms.example.com/html/tv/dianshiju/20830.html")

        assertEquals("https://cms.example.com", address.siteBase)
        assertFalse(address.isSiteRoot)
        // 认不出这类自定义路径的类型，但站点根照样收敛对——探测靠真实请求验证，不靠猜类型。
        assertEquals(PageKind.OTHER, address.pageKind)
        assertEquals(listOf("https://cms.example.com"), address.candidateBases())
    }

    @Test
    fun macCmsPlayAndListPagesAreRecognized() {
        assertEquals(PageKind.PLAY, SourceAddressRules.pageKind("https://x.com/vodplay/1234-1-1.html"))
        assertEquals(PageKind.DETAIL, SourceAddressRules.pageKind("https://x.com/voddetail/1234.html"))
        assertEquals(PageKind.LIST, SourceAddressRules.pageKind("https://x.com/vodtype/1.html"))
        assertEquals(PageKind.HOME, SourceAddressRules.pageKind("https://x.com/"))
    }

    @Test
    fun homepageAddressIsItsOwnRoot() {
        val address = SourceAddressRules.analyze("https://cms.example.com/")

        assertEquals("https://cms.example.com", address.siteBase)
        assertTrue(address.isSiteRoot)
        // 站点根本身就是目录，不该多出一个候选 base 让探测白跑一轮。
        assertEquals(listOf("https://cms.example.com"), address.candidateBases())
    }

    @Test
    fun subdirectoryInstallGetsSecondCandidateBase() {
        // 用户粘的是装在子目录的站点首页：只按站点根探会全失败，得给它第二次机会。
        val address = SourceAddressRules.analyze("https://x.com/tv/")

        assertEquals("https://x.com", address.siteBase)
        assertEquals("https://x.com/tv", address.directoryBase)
        assertEquals(listOf("https://x.com", "https://x.com/tv"), address.candidateBases())
    }

    @Test
    fun contentDirectoryIsNotTreatedAsSubdirectorySite() {
        // 粘播放页时所在目录是内容路径，拿它拼搜索入口没意义，不该多跑一轮候选。
        val address = SourceAddressRules.analyze("https://x.com/tv/vodplay/1-1-1.html")

        assertEquals("https://x.com", address.directoryBase)
        assertEquals(listOf("https://x.com"), address.candidateBases())
    }

    @Test
    fun schemeAndPortAndUppercaseHostAreNormalized() {
        assertEquals("https://x.com", SourceAddressRules.siteBase("https://X.COM/path/a.html"))
        assertEquals("http://x.com", SourceAddressRules.siteBase("http://x.com:80/a.html"))
        assertEquals("http://x.com:8080", SourceAddressRules.siteBase("http://x.com:8080/a.html"))
        assertEquals("https://x.com", SourceAddressRules.siteBase("x.com/a.html"))
        assertEquals("https://x.com", SourceAddressRules.siteBase("//x.com/a.html"))
    }

    @Test
    fun probePagesCoverSiteRootAndPastedPage() {
        val address = SourceAddressRules.analyze("https://x.com/vodplay/1-1-1.html")

        // 候选 base 的目录页按 base 索引，生成候选规则时才能查到对应 HTML。
        assertEquals(mapOf("https://x.com" to "https://x.com/"), address.basePagesToFetch())
        assertEquals("https://x.com/vodplay/1-1-1.html", address.pastedPageToFetch())
    }

    @Test
    fun pastedSiteRootIsNotFetchedTwice() {
        val address = SourceAddressRules.analyze("https://x.com/")

        assertEquals(null, address.pastedPageToFetch())
    }

    @Test
    fun sameSiteIgnoresPath() {
        assertTrue(SourceAddressRules.sameSite("https://x.com/a/b.html", "https://x.com/"))
        assertFalse(SourceAddressRules.sameSite("https://x.com/a", "https://y.com/"))
        assertFalse(SourceAddressRules.sameSite("http://x.com", "https://x.com"))
    }
}
