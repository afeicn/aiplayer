package com.afeicn.aiplayer

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMessagesTest {

    @Test
    fun wmaDecodeFailureTellsUserToConvert() {
        val message = playbackErrorMessage(
            "content://com.android.providers.media.documents/document/京剧录音.WMA",
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertEquals("WMA 格式解码失败，建议转成 MP3 或 FLAC", message)
    }

    @Test
    fun otherFormatsUseTheirOwnExtension() {
        val message = playbackErrorMessage(
            "content://media/external/audio/media/12.mp4",
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )
        assertTrue(message, message.contains("格式解码失败"))
    }

    @Test
    fun fileNotFoundIsNotBlamedOnFormat() {
        val message = playbackErrorMessage(
            "content://media/external/audio/media/12.wma",
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        assertEquals("文件找不到了，可能被移动或删除", message)
    }

    @Test
    fun permissionDeniedIsStatedAsPermission() {
        val message = playbackErrorMessage(
            "content://media/external/audio/media/12.flac",
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        )
        assertTrue(message, message.contains("重新授权"))
    }

    @Test
    fun noMessageMisleadsAboutPermissions() {
        val codes = intArrayOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        for (code in codes) {
            val message = playbackErrorMessage("content://x/foo.wma", code)
            assertTrue(message, !message.contains("请检查文件和授权"))
        }
    }
}
