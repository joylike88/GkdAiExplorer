package com.toolbox.smartcleaner.ai

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.SmartCleanerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 后台处理服务 — 伪装为"智能优化后台"
 * 在独立线程处理 LLM 调用等耗时任务，不阻塞无障碍服务
 *
 * 向服务发送 Intent：
 *   ACTION_EXPLORE  → 启动 AI 探索目标应用
 *   ACTION_STOP     → 取消当前探索
 */
class AiProcessingService : Service() {

    companion object {
        const val TAG = "AiProcessingService"
        const val ACTION_EXPLORE = "com.toolbox.smartcleaner.action.EXPLORE"
        const val ACTION_STOP = "com.toolbox.smartcleaner.action.STOP_EXPLORE"
        const val EXTRA_TARGET_PKG = "target_pkg"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var controller: AiExplorerController? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AiProcessingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXPLORE -> {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                if (targetPkg != null && !isRunning.getAndSet(true)) {
                    startExploration(targetPkg)
                }
            }
            ACTION_STOP -> {
                controller?.cancel()
                isRunning.set(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startExploration(targetPkg: String) {
        val smartCleaner = SmartCleanerService.instance
        if (smartCleaner == null) {
            Log.e(TAG, "SmartCleanerService not initialized yet")
            isRunning.set(false)
            return
        }

        val ruleEngine = RuleEngine.instance
        if (ruleEngine == null) {
            Log.e(TAG, "RuleEngine not initialized yet")
            isRunning.set(false)
            return
        }

        val llmClient = LlmClientHolder.getClient()
        controller = AiExplorerController(
            ruleEngine = ruleEngine,
            serviceProvider = { SmartCleanerService.instance },
            llmClient = llmClient
        )

        controller?.startExploration(targetPkg)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.cancel()
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "AiProcessingService destroyed")
    }
}

/**
 * 保持 LlmClient 生命周期（Client 可以在设置页面修改 API Key 后更新）
 */
object LlmClientHolder {
    @Volatile private var client: LlmClient = LlmClient()

    fun getClient(): LlmClient = client

    fun updateClient(newClient: LlmClient) {
        client = newClient
    }
}