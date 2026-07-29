package com.toolbox.smartcleaner.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.toolbox.smartcleaner.ai.AiExplorerController
import com.toolbox.smartcleaner.ai.LlmClient
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.SmartCleanerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * AI 后台处理服务 — 处理耗时的 AI 探索任务
 * 
 * 避免阻塞主线程，与无障碍服务解耦
 * 通过 startService + Intent 触发，或由 MainActivity bindService 调用
 */
class AiProcessingService : Service() {

    companion object {
        const val TAG = "AiProcessingService"
        const val ACTION_START_EXPLORATION = "com.toolbox.smartcleaner.ACTION_START_EXPLORATION"
        const val ACTION_CANCEL = "com.toolbox.smartcleaner.ACTION_CANCEL"
        const val EXTRA_TARGET_PKG = "target_package"
    }

    private val smartCleaner = SmartCleanerService(this)
    private val ruleEngine = RuleEngine { smartCleaner }
    private val llmClient = LlmClient(
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "", // 从 SharedPreferences 读取
        model = "moonshot-v1-8k"
    )
    private val aiController = AiExplorerController(ruleEngine, { smartCleaner }, llmClient)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AiProcessingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_EXPLORATION -> {
                    val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                    scope.launch { aiController.startExploration(targetPkg) }
                }
                ACTION_CANCEL -> {
                    aiController.cancel()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return LocalBinder()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "AiProcessingService destroyed")
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): AiProcessingService = this@AiProcessingService
    }

    /** 供 Activity bind 后直接调用 */
    fun startExploration(targetPkg: String?) = scope.launch { aiController.startExploration(targetPkg) }
    fun cancelExploration() = aiController.cancel()
    fun getController(): AiExplorerController = aiController
}