package com.afeicn.aiplayer

import java.net.URL

/**
 * 音频源直链识别与下载类型分流的纯逻辑层。
 *
 * 音频源（[SourceType.AUDIO]）不走 HLS 那套，直接识别 `.mp3/.m4a/.flac/.aac/.wav/.ogg/.opus/.ape/.wma`
 * 直链，或页面里 `<audio src>` 的地址，交给普通文件下载（ProgressiveDownloader）。
 */

object AudioDirectLink {

    /** 支持直链下载的音频扩展名。 */
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "aac", "wav", "ogg", "opus", "ape", "wma")

    private val AUDIO_SRC = Regex(
        """<audio\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val QUOTED_AUDIO = Regex(
        """["']([^"']*\.(?:mp3|m4a|flac|aac|wav|ogg|opus|ape|wma)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE
    )

    /** URL 是否音频直链（按扩展名）。 */
    fun isAudioLink(url: String): Boolean = audioExtension(url) != null

    /** 返回音频扩展名（小写），非音频返回 null。 */
    fun audioExtension(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in AUDIO_EXTENSIONS }
    }

    /** 从页面 HTML 里找音频直链：`<audio src>` 优先，再退回引号里的音频扩展名。 */
    fun findAudioLinks(html: String): List<String> {
        val fromAudio = AUDIO_SRC.findAll(html).map { it.groupValues[1].trim() }.toList()
        val fromQuotes = QUOTED_AUDIO.findAll(html).map { it.groupValues[1].trim() }.toList()
        return (fromAudio + fromQuotes).filter { it.isNotBlank() }.distinct()
    }

    /** 相对地址按页面补全（绝对地址幂等）。 */
    fun resolve(baseUrl: String, candidate: String): String =
        runCatching { URL(URL(baseUrl), candidate.trim()).toString() }.getOrDefault(candidate)

    /** 下载类型分流：音频直链 → 普通文件下载；m3u8 → HLS；其余按扩展名。 */
    fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return ext == "m3u8" || ext == "m3u"
    }
}
