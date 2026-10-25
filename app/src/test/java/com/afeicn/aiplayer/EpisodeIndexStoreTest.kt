package com.afeicn.aiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版最小偏好存储：与 SharedPreferences 的整数读写语义一致，供纯 JVM 单测注入。 */
private class FakeIntPreferences : IntPreferences {
    val values = mutableMapOf<String, Int>()
    override fun getInt(key: String, defaultValue: Int): Int = values[key] ?: defaultValue
    override fun putInt(key: String, value: Int) {
        values[key] = value
    }
}

class EpisodeIndexStoreTest {

    @Test
    fun 保存后能读回() {
        val store = EpisodeIndexStore(FakeIntPreferences())
        store.save("庆余年 第二季", 15)
        assertEquals(15, store.lastOf("庆余年 第二季"))
    }

    @Test
    fun 同剧不同集会覆盖() {
        val store = EpisodeIndexStore(FakeIntPreferences())
        store.save("庆余年 第二季", 12)
        store.save("庆余年 第二季", 15)
        assertEquals(15, store.lastOf("庆余年 第二季"))
    }

    @Test
    fun clean前后名称读写一致() {
        val prefs = FakeIntPreferences()
        val store = EpisodeIndexStore(prefs)
        store.save("  <font>庆余年</font>  第二季  ", 15)
        // 读取侧带 HTML 残渣或多余空白，清洗后仍命中同一个 key。
        assertEquals(15, store.lastOf("庆余年 第二季"))
        assertEquals(15, store.lastOf("<b>庆余年</b> 第二季"))
        assertTrue(prefs.values.containsKey("ep:庆余年 第二季"))
    }

    @Test
    fun 空剧名不写入() {
        val prefs = FakeIntPreferences()
        val store = EpisodeIndexStore(prefs)
        store.save("   ", 15)
        assertTrue(prefs.values.isEmpty())
        assertEquals(0, store.lastOf(""))
    }

    @Test
    fun 超过总集数的脏值按零处理并回写() {
        val prefs = FakeIntPreferences()
        val store = EpisodeIndexStore(prefs)
        prefs.putInt("ep:庆余年", 99)
        assertEquals(0, store.sanitized("庆余年", 15))
        assertEquals(0, store.lastOf("庆余年"))
        assertEquals(0, prefs.getInt("ep:庆余年", -1))
        assertEquals(15, prefs.getInt("eptotal:庆余年", 0))
    }

    @Test
    fun 总集数未知时不误判上界() {
        val store = EpisodeIndexStore(FakeIntPreferences())
        store.save("庆余年", 15)
        assertEquals(15, store.lastOf("庆余年"))
        assertEquals(15, store.sanitized("庆余年", 0))
    }

    @Test
    fun 非法下界和非正集号视为无记录() {
        val prefs = FakeIntPreferences()
        val store = EpisodeIndexStore(prefs)
        store.save("庆余年", -3)
        assertEquals(0, store.lastOf("庆余年"))
        assertEquals(0, store.sanitized("庆余年", 15))
        assertEquals(0, prefs.getInt("ep:庆余年", -1))
    }
}
