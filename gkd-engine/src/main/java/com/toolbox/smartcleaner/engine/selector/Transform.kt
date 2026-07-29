/**
 * Transform — 选择器引擎的节点遍历抽象层
 * 适配 GKD 的 Transform.kt，为 Selector 提供统一遍历接口
 */
package com.toolbox.smartcleaner.engine.selector

import kotlinx.coroutines.tasks.await

/**
 * 节点遍历转换器
 * 统一定义：父/子/兄弟/祖先/后代/索引遍历
 */
data class Transform<T>(
    /** 获取属性 */
    val getAttr: (T, String) -> Any?,
    /** 获取类名/名称 */
    val getName: (T) -> String,
    /** 获取子节点序列 */
    val getChildren: (T) -> Sequence<T>,
    /** 获取父节点 */
    val getParent: (T) -> T?,
    /** 获取所有后代（深度优先） */
    val getDescendants: (T) -> Sequence<T>,
    /** 遍历子节点（带索引过滤） */
    val traverseChildren: (T, IndexExpression) -> Sequence<T>,
    /** 遍历祖先（带索引过滤） */
    val traverseAncestors: (T, IndexExpression) -> Sequence<T>,
    /** 遍历后代（带索引过滤） */
    val traverseDescendants: (T, IndexExpression) -> Sequence<T>,
    /** 遍历前面的兄弟 */
    val traverseBeforeBrothers: (T, IndexExpression) -> Sequence<T>,
    /** 遍历后面的兄弟 */
    val traverseAfterBrothers: (T, IndexExpression) -> Sequence<T>,
    /** 快速查询后代（系统 API） */
    val traverseFastQueryDescendants: (T, FastQuery) -> Sequence<T>
) {
    /**
     * 单节点匹配查询
     */
    fun <T> querySelector(node: T, selector: Selector, option: MatchOption): T? {
        return selector.expression.match(node, this, option)
    }

    /**
     * 多节点匹配查询
     */
    fun <T> querySelectorAll(node: T, selector: Selector, option: MatchOption): Sequence<T> {
        return selector.expression.matchAll(node, this, option)
    }
}

/**
 * 查询上下文 — 用于属性单元求值
 */
class QueryContext<T>(val node: T)

/**
 * 索引表达式（用于位置过滤）
 */
sealed interface IndexExpression {
    fun checkOffset(offset: Int): Boolean
    val minOffset: Int = 0
    val maxOffset: Int? = null
}

object IndexExpression {
    data class Single(val index: Int) : IndexExpression {
        override fun checkOffset(offset: Int) = offset == index
        override val minOffset = index
        override val maxOffset = index
    }
    data class List(val indices: List<Int>) : IndexExpression {
        override fun checkOffset(offset: Int) = offset in indices
        override val minOffset = indices.minOrNull() ?: 0
        override val maxOffset = indices.maxOrNull()
    }
    data class Polynomial(val a: Int, val b: Int) : IndexExpression {
        override fun checkOffset(offset: Int): Boolean {
            if (a == 0) return offset == b
            return offset >= b && (offset - b) % a == 0
        }
        override val minOffset = if (a > 0) b else 0
        override val maxOffset = null
    }
}