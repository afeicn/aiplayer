package com.afeicn.aiplayer

/**
 * 下载条目的命名规则（纯函数，不碰 Android 框架，方便纯 JVM 单测）。
 *
 * 统一格式（[displayName]）：
 *  - 没有季：`{剧名}-{集号}` → `我不是大师-1`
 *  - 有季：`{剧名}S{季}-{集号}` → `我不是大师S1-1`
 *  - 目录模式（`inDirectory = true`，见 [fileName]）：只出 `{集号}` 或 `S{季}-{集号}`
 *    （调用方已把文件放进以剧名命名的目录，文件名里再带剧名就重复了）
 *
 * 三条配套规则：
 *  1. **集号整数不补零**：`第01集` → 1（列表按数值排序才不会错位）；
 *  2. **季从标题里识别并剥掉**：`第1季` / `第一季` / `Season 1` / `S01` / `（S1）`，
 *     剧名只留主干，季只出现在 `S{季}` 里，不重复出现；
 *  3. **剧名沿用 [MediaTitle] 清洗**，再去掉尾部状态标注（`[全集]` / `[完结]` / `[更新至24集]`），
 *     保持名字干净（脏标题见 [MediaTitle] 的真机事故说明）。
 *
 * 集号取数顺序：显式传入的 [displayName] 的 `episodeNumber` > `episodeName` 里的数字 > 没有集号。
 * 完全没有集号时退回原集名（绝不自造集号）。
 */
object EpisodeNaming {

    private val WS = Regex("""\s+""")

    /** 「第二季」「第2季」「第02季」（含全角数字与中文数字）。 */
    private val SEASON_CN = Regex("""第\s*([0-9０-９]{1,3}|[〇零一二两三四五六七八九十百]{1,6})\s*季""")
    /** 「Season 2」「season 02」。 */
    private val SEASON_EN = Regex("""season\s*0*([0-9]{1,3})""", RegexOption.IGNORE_CASE)
    /** 「S2」「S02」「（S1）」「[S01]」；前后不能紧贴字母数字（避免误吃 `H264`、`Masters1` 这类名字）。 */
    private val SEASON_S = Regex("""(?<![A-Za-z0-9])S\s*0*([0-9]{1,3})(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)

    /** 尾部「状态标注」词（只在剧名末尾、且作为独立标记时剥掉，不动作品名本身）。 */
    private const val STATUS_WORDS =
        "全集|完结|已完结|完整版|连载中|更新中|大结局|抢先版|更新至[0-9]{1,4}集|全[0-9]{1,4}集"
    /** `[全集]` / `（完结）` / `【更新至24集】` 这类尾部括号标记。 */
    private val STATUS_BRACKET = Regex("""[\[\(（【〔]\s*(?:$STATUS_WORDS)\s*[\]\)）】〕]\s*$""")
    /** `我不是大师 全集` 这种裸状态词（要求前面有分隔符，避免吃掉「全集中」这类正文）。 */
    private val STATUS_TAIL = Regex("""[\s\-–—_·:：|/、,，](?:$STATUS_WORDS)$""")

    /** 剥标记后可能留下的空括号。 */
    private val EMPTY_BRACKET = Regex("""[\[\(（【〔]\s*[\]\)）】〕]""")
    /** 首尾残留的分隔符 / 标点。 */
    private val EDGE_JUNK = Regex("""^[\s\-–—_·:：|/、,，.。+]+|[\s\-–—_·:：|/、,，.。+]+$""")
    /** 全角数字 → 半角（季号解析用）。 */
    private val FULLWIDTH_DIGITS = '０'..'９'

    /** 整段就是集名（`第08集` / `EP08`）时它不算剧名，别拼出 `第08集-8`。 */
    private val BARE_EPISODE = Regex("""^(?:第\s*[0-9〇零一二两三四五六七八九十百千]+\s*[集期话回]|[Ee][Pp]?\s*0*[0-9]+)$""")

    /** 标题拆分结果：[number] 为季号（null 表示标题里没有季信息），[name] 为剥掉季与状态标注后的剧名。 */
    data class Seasoned(val number: Int?, val name: String)

    /**
     * 从标题里识别季并剥掉，同时清掉尾部状态标注。
     *
     * `我不是大师 第二季[全集]` → (2, `我不是大师`)；`狂飙` → (null, `狂飙`)。
     */
    fun splitSeason(rawTitle: String): Seasoned {
        val cleaned = MediaTitle.clean(rawTitle)
        if (cleaned.isEmpty()) return Seasoned(null, "")
        val match = SEASON_CN.find(cleaned) ?: SEASON_EN.find(cleaned) ?: SEASON_S.find(cleaned)
        if (match == null) return Seasoned(null, tidy(cleaned))
        val number = EpisodeNumber.parse(halfWidth(match.groupValues[1]))
        val stripped = cleaned.removeRange(match.range)
        return Seasoned(number, tidy(stripped))
    }

    /** 剧名主干：清洗 + 剥季 + 去尾部状态标注（不参与集号拼接）。 */
    fun baseName(rawTitle: String): String = splitSeason(rawTitle).name

    /** 集号解析：`第01集`→1、`EP03`→3、`第十一集`→11、`粤语07`→7、`音频4`→4。 */
    fun episodeNumber(name: String): Int? {
        val text = name.trim()
        if (text.isEmpty()) return null
        return EpisodeListParser.indexFromName(text) ?: EpisodeNumber.parse(text)
    }

    /**
     * 集号标签（不含剧名）：有季 → `S{季}-{集号}`；无季 → `{集号}`。
     * 没有集号时退回清洗后的原集名；集名也空 → null。
     */
    fun episodeLabel(season: Int?, number: Int?, episodeName: String): String? = when {
        number != null && season != null -> "S$season-$number"
        number != null -> number.toString()
        else -> MediaTitle.clean(episodeName).takeIf { it.isNotEmpty() }
    }

    /**
     * 下载条目的显示名。
     *
     * @param title 剧名（可带季信息与 `[全集]` 这类状态标注，内部会剥掉）
     * @param episodeName 集名（只在没有集号时兜底展示 / 从中解析集号）
     * @param episodeNumber 已解析好的集号（优先使用）
     * @param inDirectory 目录模式：文件已经在以剧名命名的目录里，只输出集号部分
     * @param season 已落库的季号（存量迁移把季从标题剥出去后，标题里已经没有「第二季」，
     *   靠这个参数补回 `S{季}`；null 表示从标题识别）
     */
    fun displayName(
        title: String,
        episodeName: String,
        episodeNumber: Int?,
        inDirectory: Boolean = false,
        season: Int? = null
    ): String {
        val seasoned = splitSeason(title)
        val cleanedTitle = MediaTitle.clean(title)
        val name = MediaTitle.clean(episodeName)
        // 剧名整段是集名（老数据把集名当剧名落库）时不算剧名，退回集名/第 N 集。
        val base = seasoned.name.takeIf { it.isNotEmpty() && !BARE_EPISODE.matches(it) }.orEmpty()
        val number = episodeNumber ?: episodeNumber(episodeName)
        val effectiveSeason = season?.takeIf { it > 0 } ?: seasoned.number
        val label = episodeLabel(effectiveSeason, number, name)
        return when {
            inDirectory -> label ?: base.ifEmpty { "未命名" }
            // 有季时集号标签自己带 S 前缀（`剧名S1-3`），无季才是 `剧名-3`。
            base.isNotEmpty() && label != null -> if (effectiveSeason != null) "$base$label" else "$base-$label"
            base.isNotEmpty() -> base
            else -> when {
                name.isNotEmpty() -> name
                cleanedTitle.isNotEmpty() -> cleanedTitle
                number != null -> "第 $number 集"
                else -> "未命名"
            }
        }
    }

    /** 目录模式下的文件名（只有集号部分）：`S1-3` / `12` / 无集号时退回别名。 */
    fun fileName(title: String, episodeName: String, episodeNumber: Int?, season: Int? = null): String =
        displayName(title, episodeName, episodeNumber, inDirectory = true, season = season)

    /**
     * 下载条目的排序规则：先剧名（归一化后忽略大小写与空白），再**集号数值**，最后集名。
     * 用数值比较才能让 `-1, -2, -10` 有序（按字符串排会得到 `-1, -10, -2`）。
     */
    val EPISODE_ORDER: Comparator<DownloadedEpisode> = compareBy<DownloadedEpisode> {
        MediaTitle.normalize(it.title)
    }.thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeName.lowercase() }

    /** 同一部剧内部的集序（[EPISODE_ORDER] 的剧名部分之外那两层）。 */
    val WITHIN_SERIES: Comparator<DownloadedEpisode> = compareBy<DownloadedEpisode> {
        it.episodeNumber ?: Int.MAX_VALUE
    }.thenBy { it.episodeName.lowercase() }

    /** 清洗链的收尾：剥状态标注 → 去空括号 → 归空白 → 去首尾残渣，直到稳定。 */
    private fun tidy(raw: String): String {
        var text = WS.replace(raw, " ").trim()
        repeat(6) {
            val next = EMPTY_BRACKET.replace(
                STATUS_TAIL.replace(STATUS_BRACKET.replace(text, ""), ""),
                " "
            ).let { WS.replace(it, " ").trim() }.let { EDGE_JUNK.replace(it, "") }
            if (next == text) return next
            text = next
        }
        return text
    }

    private fun halfWidth(text: String): String =
        text.map { if (it in FULLWIDTH_DIGITS) '0' + (it - '０') else it }.joinToString("")
}
