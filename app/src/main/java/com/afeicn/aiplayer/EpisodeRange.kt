package com.afeicn.aiplayer

/**
 * 「确认范围」里把用户口语/文字选集要求解析成集数列表的纯逻辑层。
 *
 * 支持：
 *  - 全下 / 全部 / 所有 / 都下 / 全集；
 *  - 第 3、5 集 / 3,5 / 3 5（列表）；
 *  - 前 10 集 / 开头 10 集；
 *  - 第 2 集到第 8 集 / 2-8 / 2 到 8（区间）；
 *  - 第 3 集 / 3（单个）。
 * 中文数字（三 / 十 / 第十二）复用 [EpisodeNumber.parse]。结果越界时夹到 [1, total]，去重保序。
 */
object EpisodeRangeParser {

    private val CN = "[0-9零〇一二两三四五六七八九十百千]+"

    private val ALL = Regex("^(?:全下|全部下|全要|所有|全集|都下|都要|全选|下全部|全部|下所有|全下下来)$")

    private val LEAD = Regex("^(?:前|开头|头|最开始|先下前|先下)\\s*($CN)\\s*(?:集|话|期)?$")
    private val LEAD_LOOSE = Regex("^(?:前|开头)\\s*($CN)")

    private val RANGE = Regex(
        "(?:第)?\\s*($CN)\\s*(?:集|话|期)?\\s*(?:到|至|~|—|－|-)\\s*(?:第)?\\s*($CN)"
    )

    fun parse(raw: String, totalEpisodes: Int): List<Int> {
        val total = totalEpisodes.coerceAtLeast(0)
        if (total <= 0) return emptyList()
        val text = raw.trim().replace(Regex("""[，。！？、；;:,]"""), " ")
        if (text.isBlank()) return emptyList()

        // 全下 / 全部 / 所有
        if (ALL.matches(text)) return (1..total).toList()

        // 前 N 集
        LEAD.find(text)?.let { m -> return leadRange(m.groupValues[1], total) }
        LEAD_LOOSE.find(text)?.let { m ->
            if (text.trimStart().startsWith("前") || text.trimStart().startsWith("开头")) {
                return leadRange(m.groupValues[1], total)
            }
        }

        // X 到 Y / X-Y / X 至 Y
        RANGE.find(text)?.let { m ->
            val a = EpisodeNumber.parse(m.groupValues[1])
            val b = EpisodeNumber.parse(m.groupValues[2])
            if (a != null && b != null) return range(a, b, total)
        }

        // 列表 / 单个：第3、5集 / 3,5 / 第3集
        val nums = findAllNumbers(text)
        if (nums.isNotEmpty()) return nums.map { it.coerceIn(1, total) }.distinct()

        return emptyList()
    }

    /** 是否一个合法的范围表达（用于「共 N 集，要哪几集」判定要不要提示重说）。 */
    fun isRangeRequest(raw: String): Boolean {
        val text = raw.trim()
        if (text.isBlank()) return false
        return ALL.matches(text) || LEAD.containsMatchIn(text) || RANGE.containsMatchIn(text) ||
            findAllNumbers(text).isNotEmpty()
    }

    private fun leadRange(nText: String, total: Int): List<Int> {
        val n = EpisodeNumber.parse(nText) ?: return emptyList()
        return (1..n.coerceIn(1, total)).toList()
    }

    private fun range(a: Int, b: Int, total: Int): List<Int> {
        val from = a.coerceIn(1, total)
        val to = b.coerceIn(1, total)
        if (from > to) return (to..from).reversed().toList()
        return (from..to).toList()
    }

    private fun findAllNumbers(text: String): List<Int> {
        val result = mutableListOf<Int>()
        Regex("[0-9]+").findAll(text).forEach { it.value.toIntOrNull()?.let(result::add) }
        Regex("$CN").findAll(text).forEach { EpisodeNumber.parse(it.value)?.let(result::add) }
        return result
    }
}
