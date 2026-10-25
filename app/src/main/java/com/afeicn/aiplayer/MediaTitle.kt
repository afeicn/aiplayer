package com.afeicn.aiplayer

/**
 * 片名/集名的清洗与归一化（纯函数，不碰 Android 框架，方便纯 JVM 单测）。
 *
 * 背景：从站点搜索页抓标题时，标题常常夹带 HTML 残渣——高亮用的 `<font>`、被截断的属性、
 * 甚至整段重复。真实事故（帝国CMS 站点整页标题重复）：
 *
 *   `title="<font color='red'>我不是大师</font>[全集]"` 里的 `>` 让 `<a …>` 的属性扫描提前结束，
 *   于是标题被解析成 `我不是大师[全集]">我不是大师[全集]` 并落库。
 *
 * 这里统一做三件事：
 *  1. **去标签 + 解实体**：`<font>` / `&quot;` / `&#34;` / `&gt;` 等一律还原成纯文本；
 *  2. **去属性残渣**：`title="…">`、`" />`、`">` 这类「属性尾巴」不当正文；
 *  3. **去重复**：`X">X` / `XX` / `X X` 只保留一份（同一段标题被拼了两遍的情况）。
 *
 * 清洗是幂等的：对已经干净的中文标题调用不会改变结果（含引号的标题也保留原样）。
 */
object MediaTitle {

    private val TAG = Regex("""<[^>]*>""")
    /** 属性残渣：`">`、`'>`、`\" >`、`" />`（含转义斜杠）。 */
    private val ATTR_TAIL = Regex("""\\*["']\s*/?>""")
    /** 标题字段被当成正文带进来时的前缀：`title="…`、`alt='…`。 */
    private val ATTR_PREFIX = Regex("""^\s*(?:title|alt|name|data-title)\s*=\s*["']?""", RegexOption.IGNORE_CASE)
    private val WS = Regex("""\s+""")
    /** 匹配 `[全集]` / `（第一季）` / `【高清】` 这类附加标记（仅用于「同一部剧」比对，不改展示）。 */
    private val BRACKET = Regex("""[\[\(（【][^\]\)）】]{1,16}[\]\)）】]""")

    private val NAMED_ENTITIES = listOf(
        "&quot;" to "\"", "&apos;" to "'", "&gt;" to ">", "&lt;" to "<",
        "&nbsp;" to " ", "&ensp;" to " ", "&emsp;" to " ", "&middot;" to "·",
        "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        "&ldquo;" to "“", "&rdquo;" to "”", "&lsquo;" to "‘", "&rsquo;" to "’",
        "&amp;" to "&"
    )
    private val NUMERIC_ENTITY = Regex("""&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));""")

    /** 标题清洗：去标签 / 解实体 / 去属性残渣 / 去重复 / 归一空白。 */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var text = unescape(raw)
        // 标签可能藏着实体（`<font>&quot;`），剥完标签再解一次。
        text = TAG.replace(text, "")
        text = unescape(text)
        text = text.replace('\u00A0', ' ').replace('\u3000', ' ')
        text = ATTR_PREFIX.replace(text, "")
        text = ATTR_TAIL.replace(text, "")
        text = WS.replace(text, " ").trim()
        text = collapseDuplicates(text)
        text = text.trim().trimStart('>', '/', ' ')
        return WS.replace(text, " ").trim()
    }

    /** 比对用的强归一化：清洗 + 小写 + 去掉所有空白（全角空格也算空白）。 */
    fun normalize(raw: String): String = WS.replace(clean(raw), "").lowercase()

    /** 两个标题是否「同一部剧」：清洗后忽略大小写与空白比较。 */
    fun same(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        return left.isNotEmpty() && left == right
    }

    /** 去掉 `[全集]` / `（第一季）` / `【高清】` 这类型附加标记，得到剧名主干（仅用于匹配）。 */
    fun core(raw: String): String {
        var text = clean(raw)
        repeat(2) { text = BRACKET.replace(text, " ") }
        return WS.replace(text, " ").trim()
    }

    /** 标题尾部标记：`全` / `全集` / `更新至N` / `高清` / `HD` 这类（仅用于生成搜索关键词候选）。 */
    private val TAIL_TAG = Regex("""\s*(全|全集|完整版|更新中|更新至\d+|高清|蓝光|HD|BD|TC|TS|\d{3,4}[Pp])$""")

    /**
     * 按剧名搜索的关键词候选：整标题在站上搜不到时，逐级换更短的关键词再试。
     * 去括号标记、去尾部画质/更新标记、取第一段三种收缩两两组合，长的在前。
     */
    fun searchKeys(raw: String): List<String> {
        val base = clean(raw)
        if (base.isEmpty()) return emptyList()
        val trunk = core(base)
        return listOf(
            base,
            trunk,
            TAIL_TAG.replace(base, "").trim(),
            TAIL_TAG.replace(trunk, "").trim(),
            base.substringBefore(' ').trim(),
            trunk.substringBefore(' ').trim(),
            // 空格后段：「斗罗大陆剧场版 剑道尘心」里「剑道尘心」常才是源上的主标题。
            base.substringAfterLast(' ').trim(),
            trunk.substringAfterLast(' ').trim()
        ).filter { it.isNotBlank() }.distinct()
    }

    /** `X">X` / `XX` / `X X` → `X`（后面还能跟站点后缀，如 `X">X - 站名`）；不是重复就原样返回。 */
    private fun collapseDuplicates(text: String): String {
        var value = text.trim()
        // 前缀重复：开头一段被拼了两遍（重复段之后可能跟空白或站名分隔符）。
        val maxHalf = value.length / 2
        for (half in maxHalf downTo 2) {
            val head = value.substring(0, half)
            if (head.isBlank() || !value.startsWith(head, half)) continue
            val rest = value.substring(half * 2)
            val boundary = rest.isEmpty() || rest.first().isWhitespace() || rest.first() in SEPARATORS
            if (boundary) {
                value = (head + rest).trim()
                break
            }
        }
        // `X X` 两段重复。
        val parts = value.split(' ')
        if (parts.size == 2 && parts[0].isNotEmpty() && parts[0] == parts[1]) value = parts[0]
        return value
    }

    private val SEPARATORS = charArrayOf(' ', '-', '|', '_', '·', '/', '：', ':', '【', '[')

    private fun unescape(raw: String): String {
        var text = raw
        NAMED_ENTITIES.forEach { (entity, value) -> text = text.replace(entity, value, ignoreCase = true) }
        text = NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1].ifEmpty { match.groupValues[2] }
                .toIntOrNull(if (match.groupValues[1].isNotEmpty()) 16 else 10)
            code?.takeIf { it in 0x20..0x10FFFF }?.let { String(Character.toChars(it)) }.orEmpty()
        }
        return text
    }
}
