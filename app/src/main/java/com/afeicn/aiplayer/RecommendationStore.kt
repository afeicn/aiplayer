package com.afeicn.aiplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 发现页推荐流的本地缓存。
 *
 * 推荐流是「用热搜关键词真跑跨源搜索」得到的，每次冷启动都重跑一遍等于持续压订阅源，
 * 固定节奏的三连搜也很容易被站点风控识别。这里把聚合结果落盘，[RecommendationRules.CACHE_TTL_MS]
 * 内直接复用、不碰网络；上次预取一条都没召回时也记一笔，冷却期内不再重试。
 *
 * 缓存里的 hit 只记源 id，读回时按当前源列表重建（源的请求头/规则以注册表为准），
 * 源被删掉后对应的 hit 自动丢弃。
 */
class RecommendationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("discover_recommendations", Context.MODE_PRIVATE)

    /** TTL 内的缓存；没有、过期或一条都还原不出来时返回 null。 */
    fun cached(now: Long, resolve: (String) -> OnlineVideoSource?): List<AggregatedVod>? {
        val raw = prefs.getString(KEY_CARDS, null) ?: return null
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (!RecommendationRules.cacheFresh(savedAt, now)) return null
        return RecommendationCodec.decode(raw, resolve).takeIf { it.isNotEmpty() }
    }

    /** 一次成功的预取：落卡片与时间戳。 */
    fun save(cards: List<AggregatedVod>, now: Long) {
        prefs.edit()
            .putString(KEY_CARDS, RecommendationCodec.encode(cards))
            .putLong(KEY_SAVED_AT, now)
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putInt(KEY_LAST_ATTEMPT_COUNT, cards.size)
            .apply()
    }

    /** 一次拉空的预取：只记时间，冷却期内不再每次进发现页都重试。 */
    fun markEmptyAttempt(now: Long) {
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putInt(KEY_LAST_ATTEMPT_COUNT, 0)
            .apply()
    }

    fun inEmptyCooldown(now: Long): Boolean = RecommendationRules.inEmptyCooldown(
        lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
        lastCount = prefs.getInt(KEY_LAST_ATTEMPT_COUNT, -1),
        now = now
    )

    private companion object {
        const val KEY_CARDS = "cards"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        const val KEY_LAST_ATTEMPT_COUNT = "last_attempt_count"
    }
}

/**
 * 推荐卡片的 JSON 编解码（纯函数，不碰 SharedPreferences，方便纯 JVM 单测）。
 *
 * 卡片要能点进详情页、选集、下载，所以 hit 里的线路与详情页地址都要保留；
 * 源本体不落盘，只记 id，读回时交给调用方按当前源列表还原（[resolve] 返回 null 的 hit 丢弃）。
 */
object RecommendationCodec {

    fun encode(cards: List<AggregatedVod>): String {
        val array = JSONArray()
        cards.forEach { card ->
            val hits = JSONArray()
            card.hits.forEach { hit ->
                hits.put(JSONObject().apply {
                    put("sourceId", hit.source.id)
                    put("detailUrl", hit.detailUrl)
                    put("item", encodeItem(hit.item))
                })
            }
            array.put(JSONObject().apply {
                put("name", card.name)
                put("pic", card.pic)
                put("year", card.year)
                put("area", card.area)
                put("remarks", card.remarks)
                put("blurb", card.blurb)
                put("hits", hits)
            })
        }
        return array.toString()
    }

    /** 解码：还原不出任何 hit 的卡片、缺名字的卡片直接丢弃；整段坏 JSON 返回空列表。 */
    fun decode(raw: String, resolve: (String) -> OnlineVideoSource?): List<AggregatedVod> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                val hitsArr = obj.optJSONArray("hits") ?: JSONArray()
                val hits = buildList {
                    for (h in 0 until hitsArr.length()) {
                        val hitObj = hitsArr.optJSONObject(h) ?: continue
                        val source = resolve(hitObj.optString("sourceId")) ?: continue
                        val itemObj = hitObj.optJSONObject("item") ?: continue
                        add(SourceHit(source, decodeItem(itemObj), hitObj.optString("detailUrl")))
                    }
                }
                if (hits.isEmpty()) continue
                add(
                    AggregatedVod(
                        name = name,
                        pic = obj.optString("pic"),
                        year = obj.optString("year"),
                        area = obj.optString("area"),
                        remarks = obj.optString("remarks"),
                        hits = hits,
                        blurb = obj.optString("blurb")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeItem(item: VodItem): JSONObject = JSONObject().apply {
        put("vodName", item.vodName)
        put("vodPic", item.vodPic)
        put("vodYear", item.vodYear)
        put("vodArea", item.vodArea)
        put("vodRemarks", item.vodRemarks)
        put("blurb", item.blurb)
        val groups = JSONArray()
        item.playGroups.forEach { group ->
            val lines = JSONArray()
            group.lines.forEach { line ->
                lines.put(JSONObject().put("name", line.name).put("url", line.url))
            }
            groups.put(JSONObject().put("label", group.label).put("lines", lines))
        }
        put("playGroups", groups)
    }

    private fun decodeItem(obj: JSONObject): VodItem {
        val groupsArr = obj.optJSONArray("playGroups") ?: JSONArray()
        val groups = buildList {
            for (g in 0 until groupsArr.length()) {
                val groupObj = groupsArr.optJSONObject(g) ?: continue
                val linesArr = groupObj.optJSONArray("lines") ?: JSONArray()
                val lines = buildList {
                    for (l in 0 until linesArr.length()) {
                        val lineObj = linesArr.optJSONObject(l) ?: continue
                        add(VodPlayLine(lineObj.optString("name"), lineObj.optString("url")))
                    }
                }
                add(VodPlayGroup(groupObj.optString("label"), lines))
            }
        }
        return VodItem(
            vodName = obj.optString("vodName"),
            vodPic = obj.optString("vodPic"),
            vodYear = obj.optString("vodYear"),
            vodArea = obj.optString("vodArea"),
            vodRemarks = obj.optString("vodRemarks"),
            blurb = obj.optString("blurb"),
            playGroups = groups
        )
    }
}
