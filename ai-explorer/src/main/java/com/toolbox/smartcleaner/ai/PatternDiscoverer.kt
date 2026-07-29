package com.toolbox.smartcleaner.ai

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.engine.CompiledRule
import com.toolbox.smartcleaner.engine.ActionType
import com.toolbox.smartcleaner.engine.selector.Selector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 模式发现器 - AI 探索核心模块
 * 职责：
 * 1. 采集 UI 树快照
 * 2. 调用 LLM 分析识别广告/弹窗/权限等模式
 * 3. 生成 GKD 兼容的选择器字符串
 * 4. 编译为 CompiledRule 并注入 RuleEngine
 */
class PatternDiscoverer(
    private val context: Context,
    private val ruleEngine: RuleEngine,
    private val llmClient: LlmClient
) {

    companion object {
        const val TAG = "PatternDiscoverer"
        const val MAX_TREE_DEPTH = 15
        const val MAX_NODES_PER_TREE = 300
        const val MIN_CONFIDENCE = 0.75f
    }

    // 系统 Prompt - 指导 LLM 生成 GKD 选择器
    private const val SYSTEM_PROMPT = """
        你是 Android UI 自动化专家，专门识别广告、弹窗、权限请求、更新提示等可自动处理的 UI 模式。
        
        任务：分析提供的 UI 树结构，输出 JSON 格式的模式识别结果。
        
        输出格式（严格 JSON）：
        {
          "patterns": [
            {
              "type": "AD_CLOSE_BUTTON|POPUP_DIALOG|SPLASH_AD|BANNER_AD|REWARD_SKIP|PERMISSION_DIALOG|UPDATE_DIALOG|CUSTOM",
              "description": "简短描述，如'右上角关闭按钮'、'底部弹窗确认键'",
              "confidence": 0.95,
              "selector": "@text='跳过' || @text='关闭' || @desc='close'",
              "action": "CLICK",
              "actionArgs": {},
              "sampleNode": {
                "className": "android.widget.Button",
                "text": "跳过广告",
                "contentDescription": "",
                "viewId": "com.example:id/skip_btn",
                "clickable": true
              }
            }
          ]
        }
        
        选择器语法（GKD 兼容）：
        - @text='xxx'  文本匹配
        - @id='xxx'    资源ID匹配
        - @desc='xxx'  contentDescription 匹配
        - @class='xxx' 类名匹配
        - [attr=val]   属性匹配，支持 = != > >= < <= ^= $= *= ~= !~=
        - >  子节点
        - << 后代节点
        - <  父节点
        - +  前一个兄弟
        - -  后一个兄弟
        - -> 前一个匹配节点
        - && 逻辑与
        - || 逻辑或
        - !  逻辑非
        - (1) 索引
        - (2n+1) 多项式索引
        
        规则：
        1. 选择器必须能唯一或高概率定位目标节点
        2. 优先使用 @text/@id/@desc 等快速查询属性
        3. 避免使用过深的层级遍历（<< 尽量少用）
        4. 只输出置信度 >= 0.7 的模式
        5. action 只能是：CLICK, BACK, HOME, TEXT_INPUT
    """.trimIndent()

    private val discoveredCache = ConcurrentHashMap<String, DiscoveredPattern>()

    /**
     * 启动对当前前台应用的探索
     */
    suspend fun exploreCurrentApp(appId: String, appName: String): ExplorationResult {
        return withContext(Dispatchers.IO) {
            // 1. 获取 UI 树
            val snapshots = captureUITrees(appId)
            if (snapshots.isEmpty()) {
                return@withContext ExplorationResult(appId, appName, emptyList(), emptyList(), "未获取到 UI 树")
            }

            // 2. LLM 分析
            val patterns = analyzeWithLLM(appId, appName, snapshots)
            if (patterns.isEmpty()) {
                return@withContext ExplorationResult(appId, appName, emptyList(), emptyList(), "未识别出有效模式")
            }

            // 3. 生成规则
            val rules = generateRules(appId, patterns)
            
            // 4. 注入引擎
            rules.forEach { ruleEngine.injectRule(it) }

            ExplorationResult(appId, appName, patterns, rules, "成功")
        }
    }

    /**
     * 采集多次 UI 树快照（处理动态变化）
     */
    private fun captureUITrees(appId: String): List<List<NodeSnapshot>> {
        val service = com.toolbox.smartcleaner.service.ObservationService.instance
            ?: return emptyList()
        
        val trees = mutableListOf<List<NodeSnapshot>>()
        
        repeat(3) { attempt ->
            val root = service.getFreshRoot() ?: return@repeat
            val nodes = extractKeyNodes(root, appId)
            if (nodes.isNotEmpty()) {
                trees.add(nodes)
            }
            Thread.sleep(400) // 等待 UI 稳定
        }
        
        return trees
    }

    /**
     * 提取关键节点（剪枝，减少 token 消耗）
     */
    private fun extractKeyNodes(root: AccessibilityNodeInfo, appId: String): List<NodeSnapshot> {
        val nodes = mutableListOf<NodeSnapshot>()
        val queue = mutableListOf(root)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TREE_DEPTH && nodes.size < MAX_NODES_PER_TREE) {
            val current = queue.removeAt(0)
            val snap = toSnapshot(current, depth)
            
            // 过滤：只保留有文本、ID、描述或可点击的节点
            if (isInterestingNode(snap)) {
                nodes.add(snap)
            }

            for (i in 0 until current.childCount.coerceAtMost(12)) {
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

        // 必须有识别特征
        return snap.text?.isNotBlank() == true
            || snap.contentDescription?.isNotBlank() == true
            || snap.viewId?.isNotBlank() == true
            || snap.clickable
    }

    /**
     * 调用 LLM 分析
     */
    private fun analyzeWithLLM(appId: String, appName: String, trees: List<List<NodeSnapshot>>): List<DiscoveredPattern> {
        // 使用第一棵树（最新的）
        val nodes = trees.firstOrNull() ?: return emptyList()
        
        // 压缩为文本表示
        val treeText = nodes.joinToString("\n") { snap ->
            "  ${"  ".repeat(snap.depth)}[${snap.className.substringAfterLast(".")}] " +
                    "id=${snap.viewId ?: "-"} " +
                    "text=${snap.text?.take(40) ?: "-"} " +
                    "desc=${snap.contentDescription?.take(40) ?: "-"} " +
                    "click=${snap.clickable} " +
                    "bounds=${snap.bounds}"
        }

        val prompt = """
            应用: $appName ($appId)
            UI 树结构:
            $treeText
            
            请识别其中的广告关闭按钮、弹窗确认键、权限允许按钮、更新跳过按钮等可自动处理的元素。
            严格按指定 JSON 格式输出。
        """.trimIndent()

        return try {
            val response = llmClient.chatCompletion(SYSTEM_PROMPT, prompt, 0.2)
            parseLLMResponse(response, appId)
        } catch (e: Exception) {
            Log.e(TAG, "LLM analysis failed", e)
            emptyList()
        }
    }

    /**
     * 解析 LLM 响应
     */
    private fun parseLLMResponse(jsonText: String, appId: String): List<DiscoveredPattern> {
        val json = JSONObject(jsonText)
        val patternsArray = json.optJSONArray("patterns") ?: return emptyList()
        val results = mutableListOf<DiscoveredPattern>()

        for (i in 0 until patternsArray.length()) {
            val p = patternsArray.getJSONObject(i)
            val confidence = p.getDouble("confidence").toFloat()
            
            if (confidence < MIN_CONFIDENCE) continue

            val selectorStr = p.getString("selector")
            val selector = Selector.parseOrNull(selectorStr) ?: continue

            val pattern = DiscoveredPattern(
                patternId = UUID.randomUUID().toString(),
                patternType = PatternType.valueOf(p.getString("type")),
                description = p.getString("description"),
                confidence = confidence,
                sampleNodes = emptyList(), // 可选：存储样本节点
                suggestedSelector = selectorStr,
                suggestedAction = ActionType.valueOf(p.getString("action")),
                actionArgs = p.optJSONObject("actionArgs")?.toString() ?: "{}"
            )
            
            results.add(pattern)
        }

        return results
    }

    /**
     * 生成 CompiledRule
     */
    private fun generateRules(appId: String, patterns: List<DiscoveredPattern>): List<CompiledRule> {
        return patterns.mapIndexed { index, pattern ->
            val ruleId = "${appId}_ai_${pattern.patternType.name}_$index"
            CompiledRule(
                id = ruleId,
                appId = appId,
                name = "${pattern.patternType.name}: ${pattern.description}",
                matches = listOf(Selector.parse(pattern.suggestedSelector)),
                excludeMatches = emptyList(),
                actionType = pattern.suggestedAction,
                actionArgs = parseActionArgs(pattern.actionArgs),
                actionCd = 1000L,
                maxTriggerCount = 0,
                fastQuery = true
            )
        }
    }

    private fun parseActionArgs(jsonStr: String): Map<String, String> {
        return try {
            val obj = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/**
 * 探索结果
 */
data class ExplorationResult(
    val appId: String,
    val appName: String,
    val discoveredPatterns: List<DiscoveredPattern>,
    val generatedRules: List<CompiledRule>,
    val message: String
)

/**
 * 发现的模式
 */
data class DiscoveredPattern(
    val patternId: String,
    val patternType: PatternType,
    val description: String,
    val confidence: Float,
    val sampleNodes: List<NodeSnapshot>,
    val suggestedSelector: String,
    val suggestedAction: ActionType,
    val actionArgs: String
)

enum class PatternType {
    AD_CLOSE_BUTTON,
    POPUP_DIALOG,
    SPLASH_AD,
    BANNER_AD,
    REWARD_SKIP,
    PERMISSION_DIALOG,
    UPDATE_DIALOG,
    CUSTOM
}

/**
 * 节点快照
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