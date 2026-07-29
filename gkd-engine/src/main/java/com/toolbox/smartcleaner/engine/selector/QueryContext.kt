/**
 * 属性匹配求值引擎
 * 完成 SelectorParser 中的 PropertyUnit.match() 等方法
 */
package com.toolbox.smartcleaner.engine.selector

/**
 * 比较值包装
 */
sealed interface CompareValue {
    val stringValue: String
    fun compareTo(other: CompareValue): Int
    fun equals(other: CompareValue): Boolean
}

object CompareValue {
    data class StringValue(val value: String) : CompareValue {
        override val stringValue = value
        override fun compareTo(other: CompareValue) = when(other) {
            is StringValue -> value.compareTo(other.value)
            is IntValue -> value.compareTo(other.value.toString())
            else -> 0
        }
        override fun equals(other: CompareValue) = other is StringValue && value == other.value
    }
    data class IntValue(val value: Int) : CompareValue {
        override val stringValue = value.toString()
        override fun compareTo(other: CompareValue) = when(other) {
            is IntValue -> value.compareTo(other.value)
            is StringValue -> value.toString().compareTo(other.value)
            else -> 0
        }
        override fun equals(other: CompareValue) = other is IntValue && value == other.value
    }
    data class BooleanValue(val value: Boolean) : CompareValue {
        override val stringValue = value.toString()
        override fun compareTo(other: CompareValue) = 0
        override fun equals(other: CompareValue) = other is BooleanValue && value == other.value
    }
    object NullValue : CompareValue {
        override val stringValue = "null"
        override fun compareTo(other: CompareValue) = 0
        override fun equals(other: CompareValue) = other is NullValue
    }
}

/**
 * 属性求值器
 */
class PropertyEvaluator<T>(
    private val getAttr: (T, String) -> Any?,
    private val getName: (T) -> String
) {
    /**
     * 求值表达式
     */
    fun eval(node: T, expr: Expression): CompareValue {
        return when (expr) {
            is BinaryExpression -> evalBinary(node, expr)
            is LogicalExpression -> evalLogical(node, expr)
            is NotExpression -> evalNot(node, expr)
            else -> CompareValue.NullValue
        }
    }

    private fun evalBinary(node: T, expr: BinaryExpression): CompareValue {
        val leftVal = evalValue(node, expr.left)
        val rightVal = evalValue(node, expr.right)
        return when (expr.operator) {
            CompareOperator.Equal -> CompareValue.BooleanValue(leftVal.equals(rightVal))
            CompareOperator.NotEqual -> CompareValue.BooleanValue(!leftVal.equals(rightVal))
            CompareOperator.Greater -> CompareValue.BooleanValue(leftVal.compareTo(rightVal) > 0)
            CompareOperator.GreaterEqual -> CompareValue.BooleanValue(leftVal.compareTo(rightVal) >= 0)
            CompareOperator.Less -> CompareValue.BooleanValue(leftVal.compareTo(rightVal) < 0)
            CompareOperator.LessEqual -> CompareValue.BooleanValue(leftVal.compareTo(rightVal) <= 0)
            CompareOperator.StartsWith -> CompareValue.BooleanValue(leftVal.stringValue.startsWith(rightVal.stringValue))
            CompareOperator.EndsWith -> CompareValue.BooleanValue(leftVal.stringValue.endsWith(rightVal.stringValue))
            CompareOperator.Contains -> CompareValue.BooleanValue(leftVal.stringValue.contains(rightVal.stringValue))
            CompareOperator.Matches -> CompareValue.BooleanValue(leftVal.stringValue.matches(rightVal.stringValue.toRegex()))
            CompareOperator.NotMatches -> CompareValue.BooleanValue(!leftVal.stringValue.matches(rightVal.stringValue.toRegex()))
        }
    }

    private fun evalLogical(node: T, expr: LogicalExpression): CompareValue {
        val left = eval(node, expr.left)
        val right = eval(node, expr.right)
        return CompareValue.BooleanValue(when (expr.operator) {
            LogicalOperator.And -> (left as CompareValue.BooleanValue).value && (right as CompareValue.BooleanValue).value
            LogicalOperator.Or -> (left as CompareValue.BooleanValue).value || (right as CompareValue.BooleanValue).value
        })
    }

    private fun evalNot(node: T, expr: NotExpression): CompareValue {
        val result = eval(node, expr.expression)
        return CompareValue.BooleanValue(!(result as CompareValue.BooleanValue).value)
    }

    private fun evalValue(node: T, valueExpr: ValueExpression): CompareValue {
        return when (valueExpr) {
            is ValueExpression.StringLiteral -> CompareValue.StringValue(valueExpr.value)
            is ValueExpression.IntLiteral -> CompareValue.IntValue(valueExpr.value)
            is ValueExpression.BooleanLiteral -> CompareValue.BooleanValue(valueExpr.value)
            ValueExpression.NullLiteral -> CompareValue.NullValue
            is ValueExpression.Identifier -> getAttrValue(node, valueExpr.value)
            is ValueExpression.MemberExpression -> {
                val obj = evalValue(node, valueExpr.object)
                // 简化：不支持嵌套 member
                CompareValue.StringValue(obj.stringValue)
            }
            is ValueExpression.CallExpression -> {
                // 简化：不支持函数调用
                CompareValue.StringValue(valueExpr.callee.stringValue)
            }
        }
    }

    private fun getAttrValue(node: T, attrName: String): CompareValue {
        val raw = getAttr(node, attrName)
        return when (raw) {
            null -> CompareValue.NullValue
            is String -> CompareValue.StringValue(raw)
            is Int -> CompareValue.IntValue(raw)
            is Long -> CompareValue.IntValue(raw.toInt())
            is Boolean -> CompareValue.BooleanValue(raw)
            is CharSequence -> CompareValue.StringValue(raw.toString())
            else -> CompareValue.StringValue(raw.toString())
        }
    }
}

/**
 * 扩展 PropertyUnit.match()
 */
internal fun <T> PropertyUnit.match(context: QueryContext<T>, transform: Transform<T>): Boolean {
    val evaluator = PropertyEvaluator(transform.getAttr, transform.getName)
    val result = evaluator.eval(context.node, expression)
    return result is CompareValue.BooleanValue && result.value
}