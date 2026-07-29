/**
 * AI Explorer — AI 驱动的规则发现模块
 * 核心流程：UI 树采集 -> LLM 模式分析 -> 生成 GKD 选择器 -> 注入 RuleEngine
 */
package com.toolbox.smartcleaner.ai

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.CompiledRule
import com.toolbox.smartcleaner.engine.ActionType
import com.toolbox.smartcleaner.engine.selector.Selector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 探索状态
 */
enum class ExplorationState {
    IDLE,
    SCANNING,
    ANALYZING,
    GENERATING_RULES,
    COMPLETED,
    ERROR
}

/**
 * 探索结果
 */
data class ExplorationResult(
    val appId: String,
    val appName: String,
    val discoveredPatterns: List<DiscoveredPattern>,
    val generatedRules: List<CompiledRule>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 发现的 UI 模式
 */
data class DiscoveredPattern(
    val patternId: String,
    val patternType: PatternType,
    val description: String,
    val confidence: Float,
    val sampleNodes: List<NodeSnapshot>,
    val suggestedSelector: String,
    val suggestedAction: ActionType
)

enum class PatternType {
    AD_CLOSE_BUTTON,
    POPUP_DIALOG,
    SPLASH_AD,
    BANNER_AD,
    REWARD_VIDEO_SKIP,
    PERMISSION_DIALOG,
    UPDATE_DIALOG,
    CUSTOM
}

/**
 * 节点快照（用于发送给 LLM）
 */
data class NodeSnapshot(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: android.graphics.Rect,
    val clickable: Boolean,
    val depth: Int,
    val childCount: Int
)

/**
 * AI 探索控制器
 */
class AiExplorerController(
    private val ruleEngine: RuleEngine,
    private val serviceProvider: () -> com.toolbox.smartcleaner.engine.SmartCleanerService?,
    private val llmClient: LlmClient
) {

    companion object {
        const val TAG = "AiExplorer"
        const val MAX_TREE_DEPTH = 15
        const val MAX_NODES_PER_TREE = 200
    }

    private val _state = MutableStateFlow(ExplorationState.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<ExplorationState> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<ExplorationResult?>(null)
    val lastResult: kotlinx.coroutines.flow.StateFlow<ExplorationResult?> = _lastResult.asStateFlow()

    private val explorationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val knownAppSignatures = ConcurrentHashMap<String, String>() // appId -> tree signature

    /**
     * 启动对外部调用：开始探索当前应用
     */
    fun startExploration(targetAppId: String? = null) {
        explorationScope.launch {
            _state.value = ExplorationState.SCANNING
            try {
                val appId = targetAppId ?: getCurrentForegroundApp() ?: return@launch
                val appName = getAppLabel(appId) ?: appId

                // 1. 采集 UI 树
                val treeSnapshots = captureUiTrees(appId)
                if (treeSnapshots.isEmpty()) {
                    _state.value = ExplorationState.ERROR
                    return@launch
                }

                // 2. 去重：如果树结构签名没变，跳过
                val signature = computeTreeSignature(treeSnapshots)
                if (knownAppSignatures[appId] == signature) {
                    Log.d(TAG, "UI tree unchanged for $appId, skipping")
                    _state.value = ExplorationState.COMPLETED
                    return@launch
                }
                knownAppSignatures[appId] = signature

                _state.value = ExplorationState.ANALYZING

                // 3. LLM 分析模式
                val patterns = analyzePatternsWithLlm(appId, appName, treeSnapshots)
                if (patterns.isEmpty()) {
                    Log.d(TAG, "No patterns discovered for $appId")
                    _state.value = ExplorationState.COMPLETED
                    return@launch
                }

                _state.value = ExplorationState.GENERATING_RULES

                // 4. 生成 GKD 规则
                val rules = generateRulesFromPatterns(appId, patterns)

                // 5. 注入规则引擎
                rules.forEach { ruleEngine.injectRule(it) }

                val result = ExplorationResult(appId, appName, patterns, rules)
                _lastResult.value = result
                _state.value = ExplorationState.COMPLETED

                Log.i(TAG, "Exploration completed for $appId: ${patterns.size} patterns, ${rules.size} rules")

            } catch (e: Exception) {
                Log.e(TAG, "Exploration failed", e)
                _state.value = ExplorationState.ERROR
            }
        }
    }

    /**
     * 取消当前探索
     */
    fun cancel() {
        explorationScope.coroutineContext.cancelChildren()
        _state.value = ExplorationState.IDLE
    }

    /**
     * 采集当前窗口的 UI 树（多次采样，处理动态变化）
     */
    private fun captureUiTrees(appId: String): List<List<NodeSnapshot>> {
        val service = serviceProvider() ?: return emptyList()
        val results = mutableListOf<List<NodeSnapshot>>()

        repeat(3) { attempt ->
            val root = service.getFreshRoot() ?: return@repeat
            val nodes = extractNodes(root, appId)
            if (nodes.isNotEmpty()) {
                results.add(nodes)
            }
            Thread.sleep(500) // 等待 UI 稳定
        }
        return results
    }

    /**
     * 从根节点提取关键节点（剪枝，避免发送过大树给 LLM）
     */
    private fun extractNodes(root: AccessibilityNodeInfo, appId: String): List<NodeSnapshot> {
        val nodes = mutableListOf<NodeSnapshot>()
        val queue = mutableListOf(root)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TREE_DEPTH && nodes.size < MAX_NODES_PER_TREE) {
            val current = queue.removeAt(0)
            val snap = toSnapshot(current, depth)
            if (isInterestingNode(snap)) {
                nodes.add(snap)
            }
            for (i in 0 until current.childCount.coerceAtMost(10)) {
                current.getChild(i)?.let { queue.add(it) }
            }
            depth++
        }
        root.recycle()
        return nodes
    }

    private fun toSnapshot(node: AccessibilityNodeInfo, depth: Int): NodeSnapshot {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return NodeSnapshot(
            className = node.className.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            bounds = bounds,
            clickable = node.isClickable,
            depth = depth,
            childCount = node.childCount
        )
    }

    /**
     * 判断节点是否值得分析（过滤容器、布局等无用节点）
     */
    private fun isInterestingNode(snap: NodeSnapshot): Boolean {
        // 过滤纯容器类
        val containerClasses = setOf(
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "android.widget.GridLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "android.view.ViewGroup",
            "android.widget.ScrollView",
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.ListView",
            "androidx.viewpager.widget.ViewPager",
            "androidx.viewpager2.widget.ViewPager2"
        )
        if (containerClasses.contains(snap.className)) return false

        // 必须有文本、描述、ID 或可点击其中之一
        return snap.text?.isNotBlank() == true ||
                snap.contentDescription?.isNotBlank() == true ||
                snap.viewId?.isNotBlank() == true ||
                snap.clickable
    }

    /**
     * 计算树签名（用于去重）
     */
    private fun computeTreeSignature(trees: List<List<NodeSnapshot>>): String {
        return trees.flatten()
            .joinToString("|") { "${it.className}:${it.text?.take(20) ?? \"\"}:${it.viewId?.take(30) ?? \"\"}" }
            .hashCode()
            .toString()
    }

    /**
     * 调用 LLM 分析 UI 模式
     */
    private fun analyzePatternsWithLlm(
        appId: String,
        appName: String,
        trees: List<List<NodeSnapshot>>
    ): List<DiscoveredPattern> {
        // 构建 prompt：将节点树压缩为结构化文本
        val treeText = trees.firstOrNull()?.joinToString("\n") { snap ->
            "  ${"  ".repeat(snap.depth)}[${snap.className.substringAfterLast(".")}] " +
                    "id=${snap.viewId ?: "-"} " +
                    "text=${snap.text?.take(30) ?: "-"} " +
                    "desc=${snap.contentDescription?.take(30) ?: "-"} " +
                    "click=${snap.clickable} " +
                    "bounds=${snap.bounds}"
        } ?: ""

        val prompt = buildAnalysisPrompt(appId, appName, treeText)

        return try {
            val response = llmClient.chatCompletion(prompt)
            parseLlmResponse(response, appId)
        } catch (e: Exception) {
            Log.e(TAG, "LLM analysis failed", e)
            emptyList()
        }
    }

    private fun buildAnalysisPrompt(appId: String, appName: String, treeText: String): String {
        return """
            你是一个 Android UI 自动化专家，专门识别广告、弹窗、权限请求等可自动处理的 UI 模式。
            
            目标应用: $appName ($appId)
            
            当前 UI 树关键节点（已过滤容器类）:
            $treeText
            
            任务：识别出所有可自动点击/关闭的模式，返回 JSON 数组，每个元素包含：
            {
              "patternType": "AD_CLOSE_BUTTON|POPUP_DIALOG|SPLASH_AD|BANNER_AD|REWARD_VIDEO_SKIP|PERMISSION_DIALOG|UPDATE_DIALOG|CUSTOM",
              "description": "人类可读的描述",
              "confidence": 0.0-1.0,
              "suggestedSelector": "GKD 选择器语法，如 @text='跳过' > [id='close']",
              "suggestedAction": "CLICK|BACK|TEXT_INPUT|NONE",
              "sampleNodeHints": ["className", "text", "id", "desc"]  // 用于定位的关键特征
            }
            
            选择器语法参考：
            - @text='xxx' 文本匹配
            - @id='pkg:id/xxx' 资源ID匹配  
            - @desc='xxx' 内容描述匹配
            - @class='xxx' 类名匹配
            - [attr=val] 属性匹配，支持 = != > >= < <= ^= $= *= ~= !~=
            - > 子节点, < 父节点, << 后代, + 前兄弟, - 后兄弟
            - && 与, || 或, ! 非, () 分组
            
            只返回 JSON 数组，不要其他说明。
            """.trimIndent()
    }

    private fun parseLlmResponse(response: String, appId: String): List<DiscoveredPattern> {
        // 简化解析：提取 JSON 数组
        val jsonStart = response.indexOf('[')
        val jsonEnd = response.lastIndexOf(']')
        if (jsonStart < 0 || jsonEnd < 0) return emptyList()

        val json = response.substring(jsonStart, jsonEnd + 1)
        // 这里用简单的字符串解析，实际建议用 Gson/Moshi
        return try {
            // 简化实现：手动解析关键字段
            parsePatternsManually(json, appId)
        } catch (e: Exception) {
            Log.w(TAG, "Parse LLM response failed", e)
            emptyList()
        }
    }

    private fun parsePatternsManually(json: String, appId: String): List<DiscoveredPattern> {
        // 最小可行解析器
        val patterns = mutableListOf<DiscoveredPattern>()
        val objects = json.split("},{").map { it.trim().trimStart('[').trimEnd(']') }
        
        for (obj in objects) {
            val patternType = extractJsonValue(obj, "patternType") ?: "CUSTOM"
            val description = extractJsonValue(obj, "description") ?: ""
            val confidence = extractJsonValue(obj, "confidence")?.toFloat() ?: 0.5f
            val selector = extractJsonValue(obj, "suggestedSelector") ?: ""
            val actionStr = extractJsonValue(obj, "suggestedAction") ?: "CLICK"
            val hints = extractJsonArray(obj, "sampleNodeHints")

            val actionType = ActionType.valueOf(actionStr)
            val pType = PatternType.valueOf(patternType)

            patterns.add(DiscoveredPattern(
                patternId = "${appId}_${pType}_${System.currentTimeMillis()}",
                patternType = pType,
                description = description,
                confidence = confidence,
                sampleNodes = emptyList(), // 暂不填充
                suggestedSelector = selector,
                suggestedAction = actionType
            ))
        }
        return patterns
    }

    private fun extractJsonValue(obj: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(obj)?.groupValues?.get(1)
    }

    private fun extractJsonArray(obj: String, key: String): List<String> {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(obj) ?: return emptyList()
        return match.groupValues[1].split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
    }

    /**
     * 将发现的模式转换为 GKD 规则
     */
    private fun generateRulesFromPatterns(appId: String, patterns: List<DiscoveredPattern>): List<CompiledRule> {
        return RuleTranslator.translateAll(appId, patterns)
    }

    // =========== 公共辅助方法 ===========

    fun getCurrentForegroundApp(): String? {
        val service = serviceProvider() ?: return null
        val root = service.getFreshRoot() ?: return null
        val pkg = root.packageName?.toString()
        root.recycle()
        return pkg
    }

    private fun getAppLabel(packageName: String): String? {
        try {
            val service = serviceProvider() ?: return packageName
            val pm = service.packageManager
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            return packageName
        }
    }
}