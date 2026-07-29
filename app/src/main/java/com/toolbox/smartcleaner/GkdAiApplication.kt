package com.toolbox.smartcleaner

import android.app.Application
import android.util.Log
import com.toolbox.smartcleaner.ai.LlmClientHolder
import com.toolbox.smartcleaner.ai.LlmConfig
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.SmartCleanerService

/**
 * 应用入口 — 初始化引擎和 AI 模块的单例
 * 伪装为"智能工具箱"
 */
class GkdAiApplication : Application() {

    companion object {
        const val TAG = "GkdAiApp"
        @Volatile lateinit var instance: GkdAiApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 LLM 配置（从 SharedPreferences 读取）
        initLlmConfig()

        Log.i(TAG, "GkdAiApplication initialized")
    }

    private fun initLlmConfig() {
        val prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val config = LlmConfig(
            baseUrl = prefs.getString("api_endpoint", "https://api.moonshot.cn/v1") ?: "https://api.moonshot.cn/v1",
            apiKey = prefs.getString("api_key", "") ?: "",
            model = prefs.getString("model", "moonshot-v1-8k") ?: "moonshot-v1-8k",
            timeoutSec = 30,
            temperature = 0.1f
        )
        LlmClientHolder.updateConfig(config)
        Log.d(TAG, "LLM config loaded: ${config.baseUrl} / ${config.model}")
    }
}