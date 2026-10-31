package com.afeicn.aiplayer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [CloudflareCookieStore] 纯 JVM 单测：域名归一化、TTL 过期、invalidate。 */
class CloudflareCookieStoreTest {

    @Before
    fun setUp() {
        CloudflareCookieStore.clearAll()
        CloudflareCookieStore.resetClock()
    }

    @After
    fun tearDown() {
        CloudflareCookieStore.clearAll()
        CloudflareCookieStore.resetClock()
    }

    @Test
    fun normalizeHostStripsSchemePortPathAndWww() {
        assertEquals("booksru.net", CloudflareCookieStore.normalizeHost("www.booksru.net"))
        assertEquals("booksru.net", CloudflareCookieStore.normalizeHost("WWW.Booksru.Net:443"))
        assertEquals("booksru.net", CloudflareCookieStore.normalizeHost("https://www.booksru.net/some/path?x=1"))
        assertEquals("booksru.net", CloudflareCookieStore.normalizeHost("  booksru.net.  "))
        assertEquals("", CloudflareCookieStore.normalizeHost("   "))
    }

    @Test
    fun wwwAndBareHostShareTheSameCookie() {
        CloudflareCookieStore.put("www.booksru.net", "cf_clearance=abc; __cf_bm=xyz")

        assertEquals("cf_clearance=abc; __cf_bm=xyz", CloudflareCookieStore.cookieFor("booksru.net"))
        assertEquals("cf_clearance=abc; __cf_bm=xyz", CloudflareCookieStore.cookieFor("WWW.BOOKSRU.NET"))
    }

    @Test
    fun cookieExpiresAfterTtl() {
        var now = 1_000_000L
        CloudflareCookieStore.clock = { now }
        CloudflareCookieStore.put("booksru.net", "cf_clearance=abc")

        now += CloudflareCookieStore.TTL_MS - 1
        assertEquals("cf_clearance=abc", CloudflareCookieStore.cookieFor("www.booksru.net"))

        now += 1
        assertNull(CloudflareCookieStore.cookieFor("www.booksru.net"))
        // 过期后仍保留「上次预热时间」，供调试面板显示。
        assertEquals(1_000_000L, CloudflareCookieStore.lastWarmedAt("booksru.net"))
    }

    @Test
    fun invalidateDropsCookieAndTimestamp() {
        CloudflareCookieStore.put("booksru.net", "cf_clearance=abc")
        CloudflareCookieStore.invalidate("www.booksru.net")

        assertNull(CloudflareCookieStore.cookieFor("booksru.net"))
        assertEquals(0L, CloudflareCookieStore.lastWarmedAt("booksru.net"))
    }

    @Test
    fun lastWarmedAtReportsPutTime() {
        var now = 42_000L
        CloudflareCookieStore.clock = { now }
        CloudflareCookieStore.put("booksru.net", "cf_clearance=abc")

        assertEquals(42_000L, CloudflareCookieStore.lastWarmedAt("www.booksru.net"))
        assertEquals(0L, CloudflareCookieStore.lastWarmedAt("other.example.com"))
    }

    @Test
    fun userAgentIsStoredAndExpiresWithCookie() {
        var now = 5_000L
        CloudflareCookieStore.clock = { now }
        CloudflareCookieStore.put("www.booksru.net", "cf_clearance=abc", "  WarmMobileUA  ")

        assertEquals("WarmMobileUA", CloudflareCookieStore.userAgentFor("booksru.net"))
        now += CloudflareCookieStore.TTL_MS
        assertNull(CloudflareCookieStore.userAgentFor("booksru.net"))
        assertNull(CloudflareCookieStore.cookieFor("booksru.net"))
    }

    @Test
    fun userAgentIsNullWhenNotProvided() {
        CloudflareCookieStore.put("booksru.net", "cf_clearance=abc")
        assertNull(CloudflareCookieStore.userAgentFor("booksru.net"))
    }

    @Test
    fun invalidateAlsoDropsUserAgent() {
        CloudflareCookieStore.put("booksru.net", "cf_clearance=abc", "WarmMobileUA")
        CloudflareCookieStore.invalidate("booksru.net")

        assertNull(CloudflareCookieStore.userAgentFor("booksru.net"))
    }

    @Test
    fun blankCookieOrHostIsIgnored() {
        CloudflareCookieStore.put("booksru.net", "   ")
        CloudflareCookieStore.put("   ", "cf_clearance=abc")

        assertNull(CloudflareCookieStore.cookieFor("booksru.net"))
        assertEquals(0L, CloudflareCookieStore.lastWarmedAt("booksru.net"))
    }
}
