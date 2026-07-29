/**
 * 规则引擎 — 核心匹配执行模块
 * 对应 GKD 的 A11yRuleEngine.kt，处理规则加载/匹配/动作执行闭环
 */
package com.toolbox.smartcleaner.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import com.toolbox.smartcleaner.engine.selector.Selector
import com.toolbox.smartcleaner.engine.selector.MatchOption
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则状态
 */
enum class RuleStatus {
    /** 新建/待验证 */
    PENDING,
    /** 活跃可用 */
    ACTIVE,
    /** 已过期 */
    EXPIRED
}

/**
 * 动作类型
 */
enum class ActionType(val code: Int) {
    CLICK(0),
    BACK(1),
    HOME(2),
    TEXT_INPUT(3),
    SWIPE(4),
    LONG_PRESS(5),
    NONE(-1)
}

/**
 * 一条经过解析的可执行规则
 */
data class CompiledRule(
    val id: String,
    val appId: String,
    val name: String,
    /** 匹配选择器列表（AND 关系） */
    val matches: List<Selector>,
    /** 排除选择器（OR 关系，任一匹配则跳过） */
    val excludeMatches: List<Selector> = emptyList(),
    /** 动作类型 */
    val actionType: ActionType = ActionType.CLICK,
    /** 动作参数（文本/坐标等） */
    val actionArgs: Map<String, String> = emptyMap(),
    /** 冷却时间（毫秒） */
    val actionCd: Long = 500L,
    /** 最大触发次数（0 表示不限制） */
    val maxTriggerCount: Int = 1,
    /** 是否使用快速查询 */
    val fastQuery: Boolean = true,
    /** 状态 */
    var status: RuleStatus = RuleStatus.PENDING,
    /** 最后触发时间 */
    @Volatile var lastTriggerTime: Long = 0L,
    /** 累计触发次数 */
    @Volatile var triggerCount: Int = 0
)

/**
 * 规则匹配结果
 */
data class MatchResult(
    val matched: Boolean,
    val matchedNode: AccessibilityNodeInfo? = null,
    val rule: CompiledRule? = null,
    val targetBounds: android.graphics.Rect? = null
)

/**
 * 规则引擎 — 线程安全，内存常驻
 * 负责管理规则生命周期、接收节点进行匹配、执行动作
 */
class RuleEngine(private val serviceProvider: () -> SmartCleanerService?) {

    companion object {
        const val TAG = "RuleEngine"
        @Volatile var instance: RuleEngine? = null
    }

    /** 规则存储（appId -> List<CompiledRule>） */
    private val rules = ConcurrentHashMap<String, MutableList<CompiledRule>>()

    /** 调度线程 */
    private val matchDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val actionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 上下文缓存管理 */
    @Volatile var contextManager: ContextManager? = null

    /** 匹配锁 */
    private val matching = AtomicBoolean(false)

    // =========== 对外 API ===========

    /**
     * 注入单条规则（由 AI 模块动态调用）
     */
    fun injectRule(rule: CompiledRule) {
        val list = rules.getOrPut(rule.appId) { mutableListOf() }
        synchronized(list) {
            // 去重：同名规则更新
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx >= 0) {
                list[idx] = rule
            } else {
                list.add(rule)
            }
        }
        Log.d(TAG, "Rule injected: ${rule.appId}/${rule.name} ${rule.matches}")
    }

    /**
     * 批量注入规则（启动时或从本地加载）
     */
    fun injectRules(ruleList: List<CompiledRule>) {
        ruleList.forEach { injectRule(it) }
    }

    /**
     * 移除规则
     */
    fun removeRule(appId: String, ruleId: String) {
        rules[appId]?.removeAll { it.id == ruleId }
    }

    /**
     * 获取指定应用的所有规则
     */
    fun getRulesByApp(appId: String?): List<CompiledRule> {
        if (appId == null) return rules.values.flatten()
        return rules[appId]?.toList() ?: emptyList()
    }

    /**
     * 清除所有规则
     */
    fun clearAllRules() {
        rules.clear()
    }

    /**
     * 核心匹配入口 — 对指定节点尝试所有规则
     * @param node 当前窗口根节点或事件源节点
     * @param appId 当前前台应用包名
     */
    fun matchAndExecute(node: AccessibilityNodeInfo, appId: String) {
        if (matching.get()) return
        matching.set(true)

        try {
            val appRules = rules[appId] ?: return
            if (appRules.isEmpty()) return

            for (rule in appRules) {
                if (rule.status != RuleStatus.ACTIVE && rule.status != RuleStatus.PENDING) continue

                // 检查冷却
                val now = System.currentTimeMillis()
                if (now - rule.lastTriggerTime < rule.actionCd) continue
                if (rule.maxTriggerCount > 0 && rule.triggerCount >= rule.maxTriggerCount) {
                    rule.status = RuleStatus.EXPIRED
                    continue
                }

                // 匹配
                val result = tryMatchRule(rule, node, appId)
                if (result.matched && result.matchedNode != null) {
                    rule.lastTriggerTime = now
                    rule.triggerCount++
                    rule.status = RuleStatus.ACTIVE
                    Log.d(TAG, "Rule matched: ${rule.appId}/${rule.name}")

                    // 执行动作
                    executeAction(rule, result.matchedNode!!)
                    return // 一次事件只触发一个规则
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Match error", e)
        } finally {
            matching.set(false)
        }
    }

    /**
     * 尝试匹配单条规则
     */
    private fun tryMatchRule(rule: CompiledRule, node: AccessibilityNodeInfo, appId: String): MatchResult {
        val ctx = contextManager ?: return MatchResult(false)
        val option = MatchOption(fastQuery = rule.fastQuery)

        // 对每个 match 选择器执行查询
        for (selector in rule.matches) {
            try {
                val matchedNode = ctx.querySelfOrSelector(node, selector, option)
                if (matchedNode != null) {
                    val bounds = android.graphics.Rect()
                    matchedNode.getBoundsInScreen(bounds)
                    return MatchResult(true, matchedNode, rule, bounds)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Selector match error: ${selector}", e)
            }
        }

        return MatchResult(false)
    }

    /**
     * 执行规则动作
     */
    private fun executeAction(rule: CompiledRule, targetNode: AccessibilityNodeInfo) {
        scope.launch(actionDispatcher) {
            try {
                val service = serviceProvider() ?: return@launch
                val rect = android.graphics.Rect()
                targetNode.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()

                when (rule.actionType) {
                    ActionType.CLICK -> {
                        // 仿人类点击 — 带 bounds 自动随机偏移
                        service.click(cx, cy, rect)
                    }
                    ActionType.BACK -> {
                        service.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    ActionType.HOME -> {
                        service.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    ActionType.TEXT_INPUT -> {
                        val text = rule.actionArgs["text"] ?: ""
                        service.inputText(text)
                    }
                    ActionType.SWIPE -> {
                        var sx = rule.actionArgs["startX"]?.toIntOrNull() ?: cx
                        var sy = rule.actionArgs["startY"]?.toIntOrNull() ?: rect.bottom
                        var ex = rule.actionArgs["endX"]?.toIntOrNull() ?: cx
                        var ey = rule.actionArgs["endY"]?.toIntOrNull() ?: rect.top
                        service.swipe(sx, sy, ex, ey)
                    }
                    ActionType.LONG_PRESS -> {
                        service.longPress(cx, cy, rect)
                    }
                    else -> {
                        // 默认尝试点击，传 bounds 做随机偏移
                        service.click(cx, cy, rect)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Action execution error", e)
            }
        }
    }

    /**
     * 设置 ContextManager 实例
     */
    fun attachContextManager(manager: ContextManager) {
        this.contextManager = manager
    }

    /**
     * 释放资源
     */
    fun release() {
        matchDispatcher.close()
        actionDispatcher.close()
    }
}