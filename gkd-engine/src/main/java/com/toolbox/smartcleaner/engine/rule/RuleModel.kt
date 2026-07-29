package com.toolbox.smartcleaner.engine.rule

/**
 * GKD 规则数据模型 - 对应 GKD 的 JSON 规则结构
 * 这是核心数据模型，用于在引擎和 AI 模块间传递规则
 */
data class GkdRule(
    val id: String,                    // 目标应用包名，如 "com.tencent.wework"
    val name: String,                  // 应用显示名称
    val groups: List<RuleGroup> = emptyList()  // 规则分组
)

data class RuleGroup(
    val key: Int,                      // 分组唯一标识，用于更新时匹配
    val name: String,                  // 分组名称，如 "开屏广告"
    val rules: List<Rule> = emptyList() // 该分组下的规则列表
) {
    data class Rule(
        val matches: List<String>,     // 选择器列表（AND 关系）
        val action: String,            // 动作类型：click, back, textInput, swipe 等
        val name: String = "",         // 规则名称/描述
        val excludeMatches: List<String> = emptyList(), // 排除选择器
        val snapshotUrls: List<String> = emptyList(),   // 快照 URL
        val quickFind: Boolean = false, // 是否使用快速查找
        val actionCd: Int = 0,         // 动作冷却时间(ms)
        val actionMaximum: Int = 1,    // 最大触发次数
        val resetMatch: String = "",   // 重置匹配选择器
        val actionDelay: Int = 0,      // 动作延迟
        val actionRetryCount: Int = 0, // 重试次数
        val actionRetryDelay: Int = 0  // 重试延迟
    )
}

/**
 * 内存中的编译后规则 - RuleEngine 使用的高性能表示
 */
data class CompiledRule(
    val id: String,                    // 规则唯一 ID
    val appId: String,                 // 目标应用包名
    val name: String,                  // 规则名称
    val matches: List<Selector>,       // 解析后的选择器列表
    val excludeMatches: List<Selector> = emptyList(),
    val actionType: ActionType = ActionType.CLICK,
    val actionArgs: Map<String, String> = emptyMap(),
    val actionCd: Long = 0,
    val maxTriggerCount: Int = 0,
    val fastQuery: Boolean = true,
    var status: RuleStatus = RuleStatus.PENDING,
    var lastTriggerTime: Long = 0,
    var triggerCount: Int = 0
)

enum class RuleStatus { PENDING, ACTIVE, EXPIRED }

enum class ActionType(val code: Int) {
    CLICK(0), BACK(1), HOME(2), TEXT_INPUT(3), SWIPE(4), LONG_PRESS(5), NONE(-1)
}