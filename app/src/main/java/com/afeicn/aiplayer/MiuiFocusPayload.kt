package com.afeicn.aiplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 小米焦点通知 / 超级岛的纯数据层：协议判定 + `miui.focus.param` JSON 模板。
 *
 * 小米官方《开发指南》要点（2026-10）：
 * - OS2 只有「焦点通知」，OS3 才有「超级岛」，两者共用 `miui.focus.param`；
 * - OS3 的岛模板不能下发到 OS2，因此这里按 `notification_focus_protocol` 选两套模板：
 *   protocol>=3 走岛（param_island），protocol==2 走 OS2 焦点通知（无 param_island）；
 * - 整段 JSON 不超过 3072 字节；
 * - `filterWhenNoPermission=false` 表示焦点权限被关时退化成普通通知，而不是把通知吞掉。
 *
 * 本文件不引用任何 Android 类，JSON 生成、模板选择、权限缺失退化都能在 JVM 单测里覆盖。
 */

/** 当前设备适用的模板。 */
enum class MiuiFocusTemplate {
    /** 非小米 / OS1 / 无焦点权限：不额外发岛通知，保留原有普通下载通知。 */
    NONE,
    /** OS2 焦点通知（状态栏、通知中心、锁屏、息屏）。 */
    FOCUS_OS2,
    /** OS3 小米超级岛（摘要态 + 展开态）。 */
    ISLAND_OS3
}

object MiuiFocusRules {

    const val PROTOCOL_OS1 = 1
    const val PROTOCOL_OS2 = 2
    const val PROTOCOL_OS3 = 3

    /** 官方限制：`miui.focus.param` 整体不超过 3072 字节。 */
    const val MAX_PAYLOAD_BYTES = 3072

    /** 只按协议版本选模板；权限和岛特性由 [resolve] 再兜一层。 */
    fun templateFor(protocol: Int): MiuiFocusTemplate = when {
        protocol >= PROTOCOL_OS3 -> MiuiFocusTemplate.ISLAND_OS3
        protocol == PROTOCOL_OS2 -> MiuiFocusTemplate.FOCUS_OS2
        else -> MiuiFocusTemplate.NONE
    }

    /**
     * 结合四项运行时能力给出最终模板。
     *
     * 退化顺序：
     * 1. 非小米 / 无焦点通知权限 → [MiuiFocusTemplate.NONE]（普通下载通知照常显示，不吞通知）；
     * 2. OS3 但 `persist.sys.feature.island` 为 false → 退回 OS2 焦点通知模板（OS3 仍兼容焦点通知）；
     * 3. OS2 → OS2 焦点通知模板；OS1 及以下 → 不发。
     */
    fun resolve(
        isXiaomi: Boolean,
        protocol: Int,
        islandFeature: Boolean,
        canShowFocus: Boolean
    ): MiuiFocusTemplate {
        if (!isXiaomi || !canShowFocus) return MiuiFocusTemplate.NONE
        return when {
            protocol >= PROTOCOL_OS3 && islandFeature -> MiuiFocusTemplate.ISLAND_OS3
            protocol >= PROTOCOL_OS2 -> MiuiFocusTemplate.FOCUS_OS2
            else -> MiuiFocusTemplate.NONE
        }
    }

    /** 小米系判定：MIUI/HyperOS 上 MANUFACTURER 为 Xiaomi，部分机型 BRAND 是 Redmi/POCO。 */
    fun isXiaomiDevice(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        return m == "xiaomi" || m.contains("xiaomi") ||
            b == "xiaomi" || b == "redmi" || b == "poco" || b.contains("xiaomi")
    }
}

/** 超级岛 / 焦点通知要展示的一条下载任务快照。 */
data class MiuiIslandState(
    val downloadId: String,
    val title: String,
    val episode: String = "",
    /** 下载中 / 已暂停 / 排队 / 等待网络。 */
    val status: String,
    val percent: Int,
    val speedText: String = "",
    val remainingText: String = "",
    val downloadedText: String = "",
    val activeCount: Int = 1,
    /** 暂停态按钮要变成「继续」。 */
    val paused: Boolean = false,
    val notificationId: Int = 0,
    val sequence: Long = 0L
) {
    val taskName: String
        get() = listOf(MediaTitle.clean(title), episode.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "下载任务" }
            .take(40)

    /** 焦点通知 / 岛的正文：状态 + 多任务数 + 百分比 + 速度。 */
    val statusLine: String
        get() = buildList {
            add(status)
            if (activeCount > 1) add("${activeCount}个任务")
            add("$percent%")
            if (speedText.isNotBlank()) add(speedText)
        }.joinToString(" · ").take(48)

    val pauseActionKey: String
        get() = if (paused) MiuiFocusPayload.ACTION_RESUME_KEY else MiuiFocusPayload.ACTION_PAUSE_KEY
}

object MiuiFocusText {

    /** 剩余时间文案，控制在小岛一行能放下的长度。 */
    fun formatRemaining(seconds: Long): String {
        if (seconds <= 0L) return ""
        return when {
            seconds < 60L -> "剩 ${seconds} 秒"
            seconds < 3600L -> {
                val m = seconds / 60
                val s = seconds % 60
                if (s == 0L) "剩 $m 分" else "剩 $m 分 $s 秒"
            }
            else -> {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                if (m == 0L) "剩 $h 小时" else "剩 $h 小时 $m 分"
            }
        }
    }
}

object MiuiFocusPayload {

    const val BUSINESS = "download"
    const val PICTURE_APP = "miui.focus.pic_app"
    const val ACTION_PAUSE_KEY = "miui.focus.action_pause"
    const val ACTION_RESUME_KEY = "miui.focus.action_resume"
    const val ACTION_CANCEL_KEY = "miui.focus.action_cancel"

    /** 官方示例里 param_v2 内的协议字段固定为 1；OS 版本通过模板结构区分。 */
    private const val PAYLOAD_PROTOCOL = 1
    private const val PROGRESS_COLOR = "#3482FF"
    private const val PROGRESS_UNREACH = "#33FFFFFF"

    /**
     * 按系统协议版本生成 `miui.focus.param`。
     *
     * @return null 表示当前协议不支持焦点通知/岛，调用方应只保留普通通知。
     */
    fun build(state: MiuiIslandState, protocol: Int): String? =
        build(state, MiuiFocusRules.templateFor(protocol))

    /**
     * 按最终模板生成 `miui.focus.param`。
     *
     * 注意：OS3 设备但 `persist.sys.feature.island=false` 时，[MiuiFocusRules.resolve] 会退到
     * [MiuiFocusTemplate.FOCUS_OS2]；此时不能再按 protocol=3 生成岛模板，必须用本重载。
     */
    fun build(state: MiuiIslandState, template: MiuiFocusTemplate): String? {
        if (template == MiuiFocusTemplate.NONE) return null

        val root = JSONObject()
        val v2 = JSONObject()
        v2.put("protocol", PAYLOAD_PROTOCOL)
        v2.put("business", BUSINESS)
        v2.put("notifyId", state.notificationId.toString())
        v2.put("updatable", true)
        v2.put("sequence", state.sequence)
        // 权限被关时退化成普通通知，不吞掉下载通知。
        v2.put("filterWhenNoPermission", false)
        // 普通下载通知由 OfflineDownloadService 的前台通知承担，这里只上岛/焦点，避免通知栏重复。
        v2.put("isShowNotification", false)
        v2.put("enableFloat", false)
        v2.put("islandFirstFloat", false)
        v2.put("reopen", "reopen")
        v2.put("timeout", 720)
        v2.put("ticker", "${state.taskName} ${state.percent}%".take(40))
        v2.put("tickerPic", PICTURE_APP)
        v2.put("aodTitle", aodTitle(state))
        v2.put("aodPic", PICTURE_APP)

        // OS2 焦点通知正文：官方示例使用 baseInfo（title/content/colorTitle/type）。
        v2.put("baseInfo", JSONObject().apply {
            put("title", state.taskName)
            put("content", state.statusLine)
            put("colorTitle", PROGRESS_COLOR)
            put("type", 2)
        })
        v2.put("picInfo", JSONObject().apply {
            put("type", 1)
            put("pic", PICTURE_APP)
        })

        if (template == MiuiFocusTemplate.ISLAND_OS3) {
            v2.put("multiProgressInfo", JSONObject().apply {
                put("title", state.remainingText.ifBlank { state.downloadedText }.take(24))
                put("progress", state.percent)
                put("color", PROGRESS_COLOR)
            })
            v2.put("param_island", island(state))
        } else {
            // OS2 焦点通知模板：IM 图文 + 进度组件2。
            v2.put("progressInfo", JSONObject().apply {
                put("progress", state.percent)
                put("colorProgress", PROGRESS_COLOR)
                put("colorProgressEnd", PROGRESS_COLOR)
                put("colorProgressBg", PROGRESS_UNREACH)
            })
        }

        v2.put("actions", actionsJson(state))
        root.put("param_v2", v2)

        val json = root.toString()
        // 所有文案在拼装时已截断；这里兜底防止极端字符把 3072 字节吃满。
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MiuiFocusRules.MAX_PAYLOAD_BYTES }
    }

    /** 结束岛：同 id 下发 cancel=true，让系统移除摘要态和展开态。 */
    fun cancel(notificationId: Int, sequence: Long, protocol: Int): String? =
        cancel(notificationId, sequence, MiuiFocusRules.templateFor(protocol))

    fun cancel(notificationId: Int, sequence: Long, template: MiuiFocusTemplate): String? {
        if (template == MiuiFocusTemplate.NONE) return null
        val root = JSONObject()
        root.put("param_v2", JSONObject().apply {
            put("protocol", PAYLOAD_PROTOCOL)
            put("business", BUSINESS)
            put("notifyId", notificationId.toString())
            put("updatable", false)
            put("sequence", sequence)
            put("cancel", true)
            put("isShowNotification", false)
            put("filterWhenNoPermission", false)
        })
        return root.toString()
    }

    private fun island(state: MiuiIslandState): JSONObject = JSONObject().apply {
        put("islandProperty", 1)
        put("islandPriority", 2)
        // 岛自动消失时间（秒）：真实结束由 cancel=true 控制，这里给足 12 小时。
        put("islandTimeout", 43_200)
        put("dismissIsland", false)
        put("bigIslandArea", JSONObject().apply {
            put("imageTextInfoLeft", JSONObject().apply {
                put("type", 1)
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", PICTURE_APP)
                })
                put("textInfo", JSONObject().apply {
                    put("title", MediaTitle.clean(state.title).take(16).ifBlank { "下载" })
                    if (state.episode.isNotBlank()) put("content", state.episode.take(8))
                    put("showHighlightColor", true)
                })
            })
            put("textInfo", JSONObject().apply {
                put("title", "${state.percent}%")
                put("content", islandRightText(state).take(16))
                put("showHighlightColor", true)
                put("narrowFont", true)
            })
        })
        put("smallIslandArea", JSONObject().apply {
            put("combinePicInfo", JSONObject().apply {
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", PICTURE_APP)
                })
                put("progressInfo", JSONObject().apply {
                    put("progress", state.percent)
                    put("colorReach", PROGRESS_COLOR)
                    put("colorUnReach", PROGRESS_UNREACH)
                    put("isCCW", true)
                })
            })
        })
    }

    private fun actionsJson(state: MiuiIslandState): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            // type=0：普通图标按钮；action 指向 miui.focus.actions Bundle 里的 Notification.Action。
            put("type", 0)
            put("action", state.pauseActionKey)
            put("actionIntentType", 2)
        })
        put(JSONObject().apply {
            put("type", 0)
            put("action", ACTION_CANCEL_KEY)
            put("actionIntentType", 2)
        })
    }

    private fun islandRightText(state: MiuiIslandState): String =
        state.speedText.ifBlank { state.remainingText }.ifBlank { state.status }

    private fun aodTitle(state: MiuiIslandState): String =
        if (state.remainingText.isNotBlank()) "${state.percent}% · ${state.remainingText}"
        else "${state.percent}%"
}
