package com.toolbox.smartcleaner.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.toolbox.smartcleaner.engine.ContextManager
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.SmartCleanerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 对外暴露的无障碍服务 — 伪装为"智能清理"服务
 */
class ObservationService : AccessibilityService() {

    companion object {
        const val TAG = "ObservationService"
        @Volatile var instance: ObservationService? = null
    }

    private val smartCleaner = SmartCleanerService(this)
    private val ruleEngine = RuleEngine { smartCleaner }.also { RuleEngine.instance = it }
    private val scope = CoroutineScope(Dispatchers.Main + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ruleEngine.attachContextManager(smartCleaner.contextManager)
        Log.i(TAG, "ObservationService created")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        smartCleaner.contextManager.refreshRoot(root)
        val pkg = root.packageName?.toString() ?: return
        ruleEngine.matchAndExecute(root, pkg)
    }

    override fun onInterrupt() {
        smartCleaner.contextManager.clearCache()
    }

    fun getFreshRoot(): AccessibilityNodeInfo? = smartCleaner.getFreshRoot()
    fun getContextManager(): ContextManager = smartCleaner.contextManager
    fun getRuleEngine(): RuleEngine = ruleEngine

    /** 仿人类点击 — 自动在元素范围内随机偏移 */
    fun performClick(x: Int, y: Int, bounds: Rect? = null) {
        scope.launch { smartCleaner.click(x, y, bounds) }
    }

    /** 贝塞尔曲线滑动 */
    fun performSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 300
    ) {
        scope.launch { smartCleaner.swipe(startX, startY, endX, endY, duration) }
    }

    fun performLongPress(x: Int, y: Int, bounds: Rect? = null, duration: Long = 1000) {
        scope.launch { smartCleaner.longPress(x, y, bounds, duration) }
    }

    fun performInputText(text: String): Boolean = smartCleaner.inputText(text)
    fun performGlobalAction(action: Int): Boolean = smartCleaner.globalAction(action)
}