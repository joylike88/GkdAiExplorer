package com.toolbox.smartcleaner.ai

import com.toolbox.smartcleaner.engine.CompiledRule
import com.toolbox.smartcleaner.engine.ActionType
import com.toolbox.smartcleaner.engine.selector.Selector
import com.toolbox.smartcleaner.ai.PatternType

/**
 * 规则翻译器 - 将 AI 发现的 UI 模式转化为 GKD 可执行规则
 *
 * 职责链：
 *   AiExplorerController 采集 UI 树 → PatternDiscoverer 调用 LLM 识别模式
 *   → RuleTranslator 将模式描述转换为 Selector 对象 → RuleEngine 注入执行
 */
object RuleTranslator {

    /**
     * 根据 AI 发现的模式生成 CompiledRule
     *
     * @param appId 目标应用包名
     * @param pattern 发现的 UI 模式
     * @param index 同应用内规则序号（用于区分同名规则）
     */
    fun translate(appId: String, pattern: DiscoveredPattern, index: Int): CompiledRule {
        val selector = Selector.parseOrNull(pattern.suggestedSelector)
            ?: fallbackSelector(pattern)

        val (actionType, args) = when (pattern.suggestedAction) {
            ActionType.SWIPE -> {
                // 滑动需要起止坐标，从样本节点边界推算
                val node = pattern.sampleNodes.firstOrNull()
                val (sx, sy, ex, ey) = if (node != null) {
                    val b = node.bounds
                    b.centerX() to b.bottom to b.centerX() to b.top
                } else 540 to 1800 to 540 to 400 // 兜底：全屏上滑
                mapOf(
                    "startX" to sx.toString(),
                    "startY" to sy.toString(),
                    "endX" to ex.toString(),
                    "endY" to ey.toString(),
                    "duration" to "300"
                ) to ActionType.SWIPE
            }
            else -> emptyMap() to pattern.suggestedAction
        }

        return CompiledRule(
            id = "${appId}_ai_${pattern.patternType}_$index",
            appId = appId,
            name = "AI: ${pattern.description}",
            matches = listOf(selector),
            excludeMatches = emptyList(),
            actionType = actionType,
            actionArgs = args,
            actionCd = computeActionCd(pattern.patternType),
            maxTriggerCount = if (pattern.patternType == PatternType.SPLASH_AD) 1 else 0,
            fastQuery = true,
            status = com.toolbox.smartcleaner.engine.RuleStatus.PENDING
        )
    }

    /**
     * 当 LLM 返回的选择器无法解析时，用 AI 提取的节点特征构建后备选择器
     */
    private fun fallbackSelector(pattern: DiscoveredPattern): Selector {
        // 使用样本节点中最有区分度的特征
        val node = pattern.sampleNodes.firstOrNull() ?: return Selector.parse("@text='跳过'")
        
        val selector = buildString {
            append("@text='${escapeSelector(node.text ?: "")}' || ")
            append("@desc='${escapeSelector(node.contentDescription ?: "")}' || ")
            if (node.viewId != null) append("@id='${node.viewId}' || ")
            append("!()") // 兜底
        }
        
        return Selector.parse(selector.removeSuffix(" || !()"))
    }

    /**
     * 将 PatternType 映射到规则动作
     */
    private fun mapAction(actionType: ActionType): ActionType = actionType

    /**
     * 根据模式类型计算合理的冷却时间
     */
    private fun computeActionCd(patternType: PatternType): Long {
        return when (patternType) {
            PatternType.SPLASH_AD -> 5000L       // 开屏广告可能持续几秒
            PatternType.POPUP_DIALOG -> 1000L     // 弹窗可能频繁出现
            PatternType.BANNER_AD -> 3000L        // 横幅可能反复加载
            PatternType.REWARD_VIDEO_SKIP -> 30000L // 激励视频一般较长
            PatternType.PERMISSION_DIALOG -> 2000L // 权限请求弹窗
            PatternType.UPDATE_DIALOG -> 5000L    // 更新提示不会频繁弹出
            else -> 1000L
        }
    }

    /**
     * 转义选择器字符串中的特殊字符
     */
    private fun escapeSelector(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * 批量翻译多个模式
     */
    fun translateAll(appId: String, patterns: List<DiscoveredPattern>): List<CompiledRule> {
        return patterns.mapIndexed { index, pattern ->
            translate(appId, pattern, index)
        }
    }
}