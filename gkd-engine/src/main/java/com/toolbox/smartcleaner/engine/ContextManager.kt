/**
 * 上下文缓存管理 — 节点缓存 + Transform 适配器
 * 对应 GKD 的 A11yContext.kt + Transform.kt
 *
 * 管理 AccessibilityNodeInfo 的父子索引缓存，显著减少系统 IPC 调用
 */
package com.toolbox.smartcleaner.engine

import android.util.LruCache
import android.view.accessibility.AccessibilityNodeInfo
import com.toolbox.smartcleaner.engine.selector.Selector
import com.toolbox.smartcleaner.engine.selector.MatchOption
import com.toolbox.smartcleaner.engine.selector.Transform

private const val MAX_CACHE = 2048
private const val MAX_DESCENDANTS = 4096
private const val MAX_CHILD = 128

/**
 * 节点上下文管理器
 */
class ContextManager {

    /** 根节点缓存 */
    @Volatile var rootNode: AccessibilityNodeInfo? = null

    /** child 缓存: (parent, index) -> child */
    private val childCache = LruCache<Pair<AccessibilityNodeInfo, Int>, AccessibilityNodeInfo>(MAX_CACHE)

    /** index 缓存: child -> index */
    private val indexCache = LruCache<AccessibilityNodeInfo, Int>(MAX_CACHE)

    /** parent 缓存: child -> parent */
    private val parentCache = LruCache<AccessibilityNodeInfo, AccessibilityNodeInfo>(MAX_CACHE)

    /** 当前匹配规则（用于中断检查） */
    @Volatile var currentRuleId: String? = null

    /** 中断标志 */
    @Volatile var interruptRequested = false

    // =========== 缓存操作 ===========

    /**
     * 刷新根节点
     */
    fun refreshRoot(root: AccessibilityNodeInfo?) {
        if (root != null && root != rootNode) {
            clearCache()
            rootNode = root
        }
    }

    /**
     * 清空全部缓存
     */
    fun clearCache() {
        childCache.evictAll()
        indexCache.evictAll()
        parentCache.evictAll()
    }

    /**
     * 检查是否中断
     */
    private fun checkInterrupt() {
        if (interruptRequested) throw InterruptedException("Rule matching interrupted")
    }

    // =========== 节点获取（带缓存） ===========

    private fun getCachedChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        checkInterrupt()
        val key = node to index
        childCache[key]?.let { return it }
        return try {
            node.getChild(index)?.also { child ->
                childCache[key] = child
                indexCache[child] = index
                parentCache[child] = node
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getCachedParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        checkInterrupt()
        parentCache[node]?.let { return it }
        return try {
            node.parent?.also { parent -> parentCache[node] = parent }
        } catch (e: Exception) {
            null
        }
    }

    private fun getCachedIndex(node: AccessibilityNodeInfo): Int {
        indexCache[node]?.let { return it }
        return 0
    }

    /**
     * 获取节点深度
     */
    private fun getDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0
        var p: AccessibilityNodeInfo? = node
        while (true) {
            p = getCachedParent(p) ?: break
            depth++
        }
        return depth
    }

    /**
     * 获取属性值（用于选择器匹配）
     */
    fun getNodeAttr(node: AccessibilityNodeInfo, attr: String): Any? = when (attr) {
        "id" -> node.viewIdResourceName
        "vid" -> node.viewIdResourceName?.substringAfter("id/")
        "name" -> node.className
        "text" -> node.text
        "desc" -> node.contentDescription
        "clickable" -> node.isClickable
        "focusable" -> node.isFocusable
        "checkable" -> node.isCheckable
        "checked" -> node.isChecked
        "editable" -> node.isEditable
        "longClickable" -> node.isLongClickable
        "visibleToUser" -> node.isVisibleToUser
        "left" -> node.boundsInScreen.left
        "top" -> node.boundsInScreen.top
        "right" -> node.boundsInScreen.right
        "bottom" -> node.boundsInScreen.bottom
        "width" -> node.boundsInScreen.width()
        "height" -> node.boundsInScreen.height()
        "index" -> getCachedIndex(node)
        "depth" -> getDepth(node)
        "childCount" -> node.childCount
        "parent" -> getCachedParent(node)
        else -> null
    }

    // =========== Transform 实现 ===========

    /**
     * 构造 Transform 实例供选择器引擎使用
     */
    fun createTransform(): Transform<AccessibilityNodeInfo> = Transform(
        getAttr = { target, name ->
            when (target) {
                is AccessibilityNodeInfo -> getNodeAttr(target, name)
                else -> null
            }
        },
        getName = { node -> node.className },
        getChildren = { node ->
            sequence {
                val count = node.childCount.coerceAtMost(MAX_CHILD)
                for (i in 0 until count) {
                    val child = getCachedChild(node, i) ?: break
                    yield(child)
                }
            }
        },
        getParent = { getCachedParent(it) },
        getDescendants = { node ->
            sequence {
                val stack = getChildren(node).toMutableList()
                if (stack.isEmpty()) return@sequence
                stack.reverse()
                val temp = mutableListOf<AccessibilityNodeInfo>()
                var count = 0
                while (stack.isNotEmpty()) {
                    if (count++ > MAX_DESCENDANTS) return@sequence
                    val top = stack.removeAt(stack.lastIndex)
                    yield(top)
                    for (child in getChildren(top)) {
                        temp.add(child)
                    }
                    if (temp.isNotEmpty()) {
                        for (i in temp.size - 1 downTo 0) stack.add(temp[i])
                        temp.clear()
                    }
                }
            }
        },
        traverseChildren = { node, expr ->
            sequence {
                val count = node.childCount.coerceAtMost(MAX_CHILD)
                for (i in 0 until count) {
                    if (expr.maxOffset?.let { i > it } == true) break
                    if (expr.checkOffset(i)) {
                        val child = getCachedChild(node, i) ?: break
                        yield(child)
                    }
                }
            }
        },
        traverseAncestors = { node, expr ->
            sequence {
                var p = getCachedParent(node) ?: return@sequence
                var offset = 0
                while (p != null) {
                    if (expr.checkOffset(offset)) yield(p)
                    offset++
                    if (expr.maxOffset?.let { offset > it } == true) break
                    p = getCachedParent(p)
                }
            }
        },
        traverseDescendants = { node, expr ->
            sequence {
                val stack = getChildren(node).toMutableList()
                if (stack.isEmpty()) return@sequence
                stack.reverse()
                val temp = mutableListOf<AccessibilityNodeInfo>()
                var offset = 0
                while (stack.isNotEmpty()) {
                    val top = stack.removeAt(stack.lastIndex)
                    if (expr.checkOffset(offset)) yield(top)
                    offset++
                    if (offset > MAX_DESCENDANTS || (expr.maxOffset?.let { offset > it } == true)) break
                    for (child in getChildren(top)) temp.add(child)
                    if (temp.isNotEmpty()) {
                        for (i in temp.size - 1 downTo 0) stack.add(temp[i])
                        temp.clear()
                    }
                }
            }
        },
        traverseBeforeBrothers = { node, expr ->
            val parent = getCachedParent(node) ?: return@TraverseBeforeBrothers emptySequence()
            val idx = getCachedIndex(node)
            var i = idx - 1
            var offset = 0
            sequence {
                while (i >= 0) {
                    if (expr.maxOffset?.let { offset > it } == true) break
                    if (expr.checkOffset(offset)) {
                        val child = getCachedChild(parent, i) ?: break
                        yield(child)
                    }
                    i--
                    offset++
                }
            }
        },
        traverseAfterBrothers = { node, expr ->
            val parent = getCachedParent(node) ?: return@TraverseAfterBrothers emptySequence()
            val idx = getCachedIndex(node)
            var i = idx + 1
            var offset = 0
            sequence {
                while (i < parent.childCount) {
                    if (expr.maxOffset?.let { offset > it } == true) break
                    if (expr.checkOffset(offset)) {
                        val child = getCachedChild(parent, i) ?: break
                        yield(child)
                    }
                    i++
                    offset++
                }
            }
        },
        traverseFastQueryDescendants = { _, _ -> emptySequence() }
    )

    // =========== 查询入口 ===========

    /**
     * 查询节点或其子孙中匹配选择器的节点
     */
    fun querySelfOrSelector(
        node: AccessibilityNodeInfo,
        selector: Selector,
        option: MatchOption
    ): AccessibilityNodeInfo? {
        checkInterrupt()
        val transform = createTransform()

        // 尝试当前节点
        selector.match(node, transform, option)?.let { return it }

        // 尝试子孙节点
        return transform.querySelector(node, selector, option)
    }

    /**
     * 查询所有匹配的节点
     */
    fun querySelectorAll(
        node: AccessibilityNodeInfo,
        selector: Selector,
        option: MatchOption
    ): Sequence<AccessibilityNodeInfo> {
        val transform = createTransform()
        return transform.querySelectorAll(node, selector, option)
    }
}