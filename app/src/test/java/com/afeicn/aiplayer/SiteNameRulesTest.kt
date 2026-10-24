package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteNameRulesTest {

    @Test
    fun takesFirstSegmentOfTitle() {
        assertEquals("6v电影", SiteNameRules.titleFromHtml("<title>6v电影 - 最新电影在线观看</title>"))
        assertEquals("片吧", SiteNameRules.titleFromHtml("<TITLE>片吧 | 高清影视</TITLE>"))
        assertEquals("神马影院", SiteNameRules.titleFromHtml("<title>神马影院——免费观影</title>"))
    }

    @Test
    fun plainTitleWithoutSeparatorKept() {
        assertEquals("酷客影院", SiteNameRules.titleFromHtml("<html><head><title>酷客影院</title></head></html>"))
    }

    @Test
    fun rejectsTooShortOrTooLongOrMissing() {
        assertNull(SiteNameRules.titleFromHtml("<title>v</title>"))
        assertNull(SiteNameRules.titleFromHtml("<title>${"很".repeat(31)}</title>"))
        assertNull(SiteNameRules.titleFromHtml("<html><body>没有标题</body></html>"))
    }
}
