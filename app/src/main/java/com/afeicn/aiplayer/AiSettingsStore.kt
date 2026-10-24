package com.afeicn.aiplayer

import android.content.Context

/**
 * DeepSeek 兜底解析的设置：开关 + API Key + 模型名。存本地 SharedPreferences。
 *
 * v1 明文存储（设置页已提示）；Key 只在兜底触发时发往 api.deepseek.com。
 * 默认关闭——不配置、不开启就完全不调用模型，主路解析行为不变。
 */
class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()
    fun setApiKey(value: String) = prefs.edit().putString(KEY_API, value.trim()).apply()

    fun model(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    fun setModel(value: String) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 是否具备调用条件：已开启且 Key 非空。 */
    fun usable(): Boolean = enabled() && apiKey().isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
    }
}
