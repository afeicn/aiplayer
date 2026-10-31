package com.afeicn.aiplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小米超级岛 / 焦点通知的纯 JVM 测试：模板选择、权限退化、JSON 结构、sequence、3072 字节上限。 */
class MiuiFocusRulesTest {

    @Test
    fun `protocol 版本选择模板`() {
        assertEquals(MiuiFocusTemplate.NONE, MiuiFocusRules.templateFor(0))
        assertEquals(MiuiFocusTemplate.NONE, MiuiFocusRules.templateFor(1))
        assertEquals(MiuiFocusTemplate.FOCUS_OS2, MiuiFocusRules.templateFor(2))
        assertEquals(MiuiFocusTemplate.ISLAND_OS3, MiuiFocusRules.templateFor(3))
        assertEquals(MiuiFocusTemplate.ISLAND_OS3, MiuiFocusRules.templateFor(4))
    }

    @Test
    fun `非小米或没有焦点权限时退化且不吞通知`() {
        assertEquals(
            MiuiFocusTemplate.NONE,
            MiuiFocusRules.resolve(isXiaomi = false, protocol = 3, islandFeature = true, canShowFocus = true)
        )
        assertEquals(
            MiuiFocusTemplate.NONE,
            MiuiFocusRules.resolve(isXiaomi = true, protocol = 3, islandFeature = true, canShowFocus = false)
        )
        // OS1 没有焦点通知能力。
        assertEquals(
            MiuiFocusTemplate.NONE,
            MiuiFocusRules.resolve(isXiaomi = true, protocol = 1, islandFeature = false, canShowFocus = true)
        )
    }

    @Test
    fun `OS3 没有岛特性时退回 OS2 焦点通知`() {
        assertEquals(
            MiuiFocusTemplate.FOCUS_OS2,
            MiuiFocusRules.resolve(isXiaomi = true, protocol = 3, islandFeature = false, canShowFocus = true)
        )
        assertEquals(
            MiuiFocusTemplate.ISLAND_OS3,
            MiuiFocusRules.resolve(isXiaomi = true, protocol = 3, islandFeature = true, canShowFocus = true)
        )
        assertEquals(
            MiuiFocusTemplate.FOCUS_OS2,
            MiuiFocusRules.resolve(isXiaomi = true, protocol = 2, islandFeature = false, canShowFocus = true)
        )
    }

    @Test
    fun `小米系品牌判定`() {
        assertTrue(MiuiFocusRules.isXiaomiDevice("Xiaomi", "Xiaomi"))
        assertTrue(MiuiFocusRules.isXiaomiDevice("Xiaomi", "Redmi"))
        assertTrue(MiuiFocusRules.isXiaomiDevice("Xiaomi", "POCO"))
        assertTrue(MiuiFocusRules.isXiaomiDevice("xiaomi", "Redmi"))
        assertFalse(MiuiFocusRules.isXiaomiDevice("HUAWEI", "HUAWEI"))
        assertFalse(MiuiFocusRules.isXiaomiDevice("", ""))
    }
}

class MiuiFocusPayloadTest {

    private fun state(
        sequence: Long = 1,
        percent: Int = 42,
        paused: Boolean = false,
        title: String = "测试剧集",
        episode: String = "第 1 集",
        status: String = "下载中"
    ) = MiuiIslandState(
        downloadId = "download-id",
        title = title,
        episode = episode,
        status = status,
        percent = percent,
        speedText = "3.2 MB/s",
        remainingText = "剩 2 分 10 秒",
        downloadedText = "84.0 MB / 200.0 MB",
        activeCount = 2,
        paused = paused,
        notificationId = 0x5A13,
        sequence = sequence
    )

    @Test
    fun `OS3 生成超级岛模板与动作按钮`() {
        val json = MiuiFocusPayload.build(state(sequence = 7), protocol = 3)!!
        val v2 = JSONObject(json).getJSONObject("param_v2")

        assertEquals("download", v2.getString("business"))
        assertEquals(0x5A13.toString(), v2.getString("notifyId"))
        assertEquals(7L, v2.getLong("sequence"))
        assertTrue(v2.getBoolean("updatable"))
        // 权限被关时正常显示普通通知，不能吞掉下载通知。
        assertFalse(v2.getBoolean("filterWhenNoPermission"))
        assertFalse(v2.getBoolean("isShowNotification"))

        val island = v2.getJSONObject("param_island")
        assertEquals(1, island.getInt("islandProperty"))
        val progress = island.getJSONObject("smallIslandArea")
            .getJSONObject("combinePicInfo")
            .getJSONObject("progressInfo")
        assertEquals(42, progress.getInt("progress"))

        val big = island.getJSONObject("bigIslandArea")
        assertEquals("42%", big.getJSONObject("textInfo").getString("title"))
        assertEquals("3.2 MB/s", big.getJSONObject("textInfo").getString("content"))
        assertEquals("测试剧集", big.getJSONObject("imageTextInfoLeft")
            .getJSONObject("textInfo").getString("title"))

        val actions = v2.getJSONArray("actions")
        assertEquals(2, actions.length())
        assertEquals(MiuiFocusPayload.ACTION_PAUSE_KEY, actions.getJSONObject(0).getString("action"))
        assertEquals(MiuiFocusPayload.ACTION_CANCEL_KEY, actions.getJSONObject(1).getString("action"))

        assertEquals("测试剧集 第 1 集", v2.getJSONObject("baseInfo").getString("title"))
        assertEquals(42, v2.getJSONObject("multiProgressInfo").getInt("progress"))
    }

    @Test
    fun `暂停态动作键变成继续`() {
        val v2 = JSONObject(
            MiuiFocusPayload.build(state(paused = true), protocol = 3)!!
        ).getJSONObject("param_v2")
        assertEquals(
            MiuiFocusPayload.ACTION_RESUME_KEY,
            v2.getJSONArray("actions").getJSONObject(0).getString("action")
        )
    }

    @Test
    fun `OS2 焦点通知不下发岛模板`() {
        val v2 = JSONObject(
            MiuiFocusPayload.build(state(), protocol = 2)!!
        ).getJSONObject("param_v2")

        assertFalse(v2.has("param_island"))
        assertTrue(v2.has("progressInfo"))
        assertEquals(42, v2.getJSONObject("progressInfo").getInt("progress"))
        assertTrue(v2.has("baseInfo"))
    }

    @Test
    fun `OS3 无岛特性时按最终模板退回 OS2 结构`() {
        // 协议是 3，但 resolve 因 islandFeature=false 选了 FOCUS_OS2；
        // 这时必须按模板生成，不能仅凭 protocol=3 塞进 param_island。
        val template = MiuiFocusRules.resolve(
            isXiaomi = true, protocol = 3, islandFeature = false, canShowFocus = true
        )
        val v2 = JSONObject(MiuiFocusPayload.build(state(), template)!!).getJSONObject("param_v2")
        assertFalse(v2.has("param_island"))
        assertTrue(v2.has("progressInfo"))
    }

    @Test
    fun `OS1 或更低协议不生成 payload`() {
        assertNull(MiuiFocusPayload.build(state(), protocol = 1))
        assertNull(MiuiFocusPayload.build(state(), protocol = 0))
        assertNull(MiuiFocusPayload.cancel(0x5A13, 1, 1))
    }

    @Test
    fun `结束岛使用 cancel 与递增 sequence`() {
        val v2 = JSONObject(MiuiFocusPayload.cancel(0x5A13, 9, 3)!!)
            .getJSONObject("param_v2")
        assertTrue(v2.getBoolean("cancel"))
        assertFalse(v2.getBoolean("updatable"))
        assertEquals(9L, v2.getLong("sequence"))
        assertEquals(0x5A13.toString(), v2.getString("notifyId"))
    }

    @Test
    fun `超长文案仍不超过 3072 字节`() {
        val long = "很长的剧名".repeat(200)
        val json = MiuiFocusPayload.build(
            state(title = long, episode = long, status = long),
            protocol = 3
        )!!
        assertTrue(
            "payload=${json.toByteArray(Charsets.UTF_8).size}",
            json.toByteArray(Charsets.UTF_8).size <= MiuiFocusRules.MAX_PAYLOAD_BYTES
        )
        // 仍然是合法 JSON，系统能解析。
        JSONObject(json).getJSONObject("param_v2")
    }

    @Test
    fun `标题里的引号与反斜杠被正确转义`() {
        val json = MiuiFocusPayload.build(
            state(title = "他说\"你好\"\\测试", episode = "第 1 集"),
            protocol = 3
        )!!
        val title = JSONObject(json).getJSONObject("param_v2")
            .getJSONObject("baseInfo").getString("title")
        assertTrue(title.contains("\"你好\""))
        assertTrue(title.contains("\\测试"))
    }

    @Test
    fun `空标题有兜底文案`() {
        val v2 = JSONObject(
            MiuiFocusPayload.build(state(title = "", episode = ""), protocol = 3)!!
        ).getJSONObject("param_v2")
        assertEquals("下载任务", v2.getJSONObject("baseInfo").getString("title"))
    }
}

class MiuiFocusTextTest {

    @Test
    fun `剩余时间文案`() {
        assertEquals("", MiuiFocusText.formatRemaining(0))
        assertEquals("剩 45 秒", MiuiFocusText.formatRemaining(45))
        assertEquals("剩 2 分", MiuiFocusText.formatRemaining(120))
        assertEquals("剩 2 分 10 秒", MiuiFocusText.formatRemaining(130))
        assertEquals("剩 1 小时", MiuiFocusText.formatRemaining(3600))
        assertEquals("剩 1 小时 5 分", MiuiFocusText.formatRemaining(3900))
    }
}

class DownloadSpeedTrackerTest {

    @Test
    fun `相邻采样计算平滑速度`() {
        val tracker = DownloadSpeedTracker(smoothing = 0.5f)
        assertNull(tracker.sample("id", 1_000L, 0L))
        assertEquals(2_000L, tracker.sample("id", 3_000L, 1_000L))
        assertEquals(3_000L, tracker.sample("id", 7_000L, 2_000L))
        assertEquals(3_000L, tracker.speedOf("id"))
    }

    @Test
    fun `时间不推进或字节回退时不产生负速度`() {
        val tracker = DownloadSpeedTracker(smoothing = 0.5f)
        tracker.sample("id", 1_000L, 0L)
        tracker.sample("id", 3_000L, 1_000L)
        // 同一时刻重复采样。
        assertEquals(2_000L, tracker.sample("id", 9_000L, 1_000L))
        // 任务重启导致累计字节回退。
        assertEquals(2_000L, tracker.sample("id", 500L, 2_000L))
        assertEquals(2_000L, tracker.speedOf("id"))
    }

    @Test
    fun `retain 清理已结束任务`() {
        val tracker = DownloadSpeedTracker()
        tracker.sample("a", 1_000L, 0L)
        tracker.sample("a", 2_000L, 1_000L)
        tracker.sample("b", 1_000L, 0L)
        tracker.sample("b", 3_000L, 1_000L)
        assertTrue(tracker.speedOf("a") != null)
        assertTrue(tracker.speedOf("b") != null)

        tracker.retain(setOf("a"))
        assertTrue(tracker.speedOf("a") != null)
        assertNull(tracker.speedOf("b"))

        tracker.clear()
        assertNull(tracker.speedOf("a"))
    }
}
