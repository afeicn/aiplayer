package com.afeicn.aiplayer

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * 把 Media3 的播放错误码翻译成用户看得懂的提示。
 *
 * 以前不管什么错都显示「这首音频暂时无法播放，请检查文件和授权」，会把
 * 「格式不支持」误导成权限问题（例如库里放不了的老 WMA 录音）。
 *
 * 只收 errorCode 而不是整个 [PlaybackException]：纯 JVM 单测里 new 不出
 * PlaybackException（构造时会碰 Android 的 Bundle）。
 */
internal fun playbackErrorMessage(uri: String, errorCode: Int): String {
    val extension = uri
        .substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "文件找不到了，可能被移动或删除"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "没有读取权限，请重新选择文件夹重新授权"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "文件读取越界，文件可能已损坏"
        else -> formatMessage(extension)
    }
}

/** 播放失败的粗粒度分类，用于决定“说哪种人话”。 */
enum class PlaybackErrorKind { NETWORK, HTTP_FORBIDDEN, UNSUPPORTED_FORMAT, OTHER }

/**
 * 把错误码（可选带上 HTTP 状态码）映射成分类。纯函数，供单测。
 *
 * [httpStatus] 从 PlaybackException 的 cause 链里的
 * [HttpDataSource.InvalidResponseCodeException] 取，见 [httpStatusFrom]。
 */
fun classifyPlaybackError(errorCode: Int, httpStatus: Int = 0): PlaybackErrorKind = when {
    httpStatus == 401 || httpStatus == 403 -> PlaybackErrorKind.HTTP_FORBIDDEN
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackErrorKind.NETWORK
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> PlaybackErrorKind.UNSUPPORTED_FORMAT
    else -> PlaybackErrorKind.OTHER
}

/** 网络流播放失败的人话文案，网络 / 403 / 格式不支持分得开。 */
fun networkPlaybackErrorMessage(uri: String, errorCode: Int, httpStatus: Int = 0): String =
    when (classifyPlaybackError(errorCode, httpStatus)) {
        PlaybackErrorKind.NETWORK ->
            "网络连不上，请检查网络后重试"
        PlaybackErrorKind.HTTP_FORBIDDEN ->
            if (httpStatus == 403) "这个地址拒绝了访问（403），可能需要特定的 Referer 或令牌"
            else "这个地址需要登录或授权（$httpStatus），暂时播不了"
        PlaybackErrorKind.UNSUPPORTED_FORMAT ->
            "这个地址的格式暂不支持播放"
        PlaybackErrorKind.OTHER ->
            playbackErrorMessage(uri, errorCode)
    }

/** 从 PlaybackException 的 cause 链里翻出 HTTP 状态码（没有则返回 0）。 */
fun httpStatusFrom(exception: Throwable): Int {
    var cause: Throwable? = exception
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return 0
}

/** 按扩展名给出「格式解码失败」建议，不再往权限问题上引。 */
private fun formatMessage(extension: String): String = when (extension) {
    "" -> "这个文件暂时无法播放，可能是格式不受支持"
    "wma" -> "WMA 格式解码失败，建议转成 MP3 或 FLAC"
    else -> "${extension.uppercase()} 格式解码失败，建议转成 MP3 或 FLAC"
}
