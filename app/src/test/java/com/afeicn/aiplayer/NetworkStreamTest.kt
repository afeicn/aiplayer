package com.afeicn.aiplayer

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStreamTest {

    // ---- 请求头拼装 ----

    @Test
    fun requestHeadersAssembleRefererUserAgentAndExtra() {
        val headers = buildRequestHeaders(
            referer = "https://example.com/",
            userAgent = "观己 Player",
            extra = mapOf("X-Custom" to "1")
        )

        assertEquals("https://example.com/", headers["Referer"])
        assertEquals("观己 Player", headers["User-Agent"])
        assertEquals("1", headers["X-Custom"])
    }

    @Test
    fun blankHeadersAreDropped() {
        val headers = buildRequestHeaders(
            referer = "   ",
            userAgent = "",
            extra = mapOf("X-Custom" to "  ", "Keep" to "yes")
        )

        assertFalse(headers.containsKey("Referer"))
        assertFalse(headers.containsKey("User-Agent"))
        assertFalse(headers.containsKey("X-Custom"))
        assertEquals("yes", headers["Keep"])
    }

    @Test
    fun extraDoesNotOverrideExplicitUserAgent() {
        val headers = buildRequestHeaders(
            userAgent = "explicit",
            extra = mapOf("User-Agent" to "from-extra")
        )

        // 显式字段先写入，extra 后写入且不覆盖已存在的键（buildMap put 后写覆盖）——这里要求 Referer/UA 显式优先。
        assertEquals("explicit", headers["User-Agent"])
    }

    // ---- 错误分类映射 ----

    @Test
    fun networkErrorsAreClassifiedAsNetwork() {
        assertEquals(
            PlaybackErrorKind.NETWORK,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals(
            PlaybackErrorKind.NETWORK,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
    }

    @Test
    fun http403IsClassifiedAsForbidden() {
        assertEquals(
            PlaybackErrorKind.HTTP_FORBIDDEN,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 403)
        )
        assertEquals(
            PlaybackErrorKind.HTTP_FORBIDDEN,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, httpStatus = 403)
        )
    }

    @Test
    fun unsupportedFormatsAreClassifiedAsUnsupported() {
        assertEquals(
            PlaybackErrorKind.UNSUPPORTED_FORMAT,
            classifyPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        )
        assertEquals(
            PlaybackErrorKind.UNSUPPORTED_FORMAT,
            classifyPlaybackError(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        )
        assertEquals(
            PlaybackErrorKind.UNSUPPORTED_FORMAT,
            classifyPlaybackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        )
    }

    @Test
    fun unknownErrorIsOther() {
        assertEquals(
            PlaybackErrorKind.OTHER,
            classifyPlaybackError(PlaybackException.ERROR_CODE_UNSPECIFIED)
        )
    }

    @Test
    fun networkMessageSpeaksPlainLanguage() {
        val message = networkPlaybackErrorMessage(
            "https://x/live.m3u8",
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertEquals("网络连不上，请检查网络后重试", message)
    }

    @Test
    fun forbiddenMessageMentions403() {
        val message = networkPlaybackErrorMessage(
            "https://x/live.m3u8",
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatus = 403
        )
        assertTrue(message, message.contains("403"))
    }

    @Test
    fun networkStreamDerivesExtensionAndFlags() {
        val stream = NetworkStream(url = "https://x/live.m3u8", title = "测试台", isLive = true)

        assertEquals("m3u8", stream.extension)
        assertTrue(stream.isHls)
        assertFalse(stream.isMp4)
        assertEquals("测试台", stream.displayTitle)
    }
}
