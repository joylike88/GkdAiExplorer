package com.toolbox.smartcleaner.engine

import android.view.accessibility.AccessibilityNodeInfo

/**
 * ContextManager 兼容接口 - 用于 AiExplorerController 调用
 * 实际实现在 ContextManager.kt 中
 */
interface SmartCleanerContext {
    fun querySelfOrSelector(root: AccessibilityNodeInfo, selector: Selector, option: MatchOption): AccessibilityNodeInfo?
    fun refreshRoot(root: AccessibilityNodeInfo)
    fun clearCache()
}