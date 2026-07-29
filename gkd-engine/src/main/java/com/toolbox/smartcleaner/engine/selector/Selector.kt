/**
 * GKD 选择器引擎 — 核心数据模型与匹配入口
 * 移植自 gkd-kit/gkd :selector KMP 模块，适配 Android-only 环境
 */
package com.toolbox.smartcleaner.engine.selector

/**
 * 选择器：整个规则匹配的入口
 * 由一个或多个 SelectorExpression 经过 && / || 组合而成
 */
data class Selector(
    val expression: SelectorExpression
) {
    companion object {
        /**
         * 解析选择器字符串
         * 语法示例: @text='跳过' > [id='close'] << [desc='关闭']
         */
        fun parse(source: String): Selector {
            return SelectorParser(source).parse()
        }

        fun parseOrNull(source: String): Selector? {
            return try {
                parse(source)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 对指定节点执行匹配，返回命中的目标节点
     */
    fun <T> match(
        node: T,
        transform: Transform<T>,
        option: MatchOption = MatchOption.default
    ): T? {
        return expression.match(node, transform, option)
    }

    /**
     * 查找所有匹配节点
     */
    fun <T> matchAll(
        node: T,
        transform: Transform<T>,
        option: MatchOption = MatchOption.default
    ): Sequence<T> {
        return expression.matchAll(node, transform, option)
    }

    /**
     * 获取快速查询列表（用于 fastQuery 优化）
     */
    val fastQueryList: List<FastQuery> get() = expression.fastQueryList

    /**
     * 判断是否为根节点匹配模式（parent == null）
     */
    val isMatchRoot: Boolean get() = expression.isMatchRoot

    /**
     * 判断选择器是否为"慢查询"
     */
    fun isSlow(option: MatchOption): Boolean = expression.isSlow(option)
}

/**
 * 匹配选项
 */
data class MatchOption(
    val fastQuery: Boolean = true
) {
    companion object {
        val default = MatchOption(fastQuery = true)
        val noFast = MatchOption(fastQuery = false)
    }
}

/**
 * 快速查询类型 - 用于系统 API findAccessibilityNodeInfosByViewId/ByText 加速
 */
data class FastQuery(val type: FastQueryType, val value: String)

enum class FastQueryType { Id, Text, Vid }

/**
 * 解析错误
 */
class SelectorParseException(message: String, val position: Int) : Exception(message)