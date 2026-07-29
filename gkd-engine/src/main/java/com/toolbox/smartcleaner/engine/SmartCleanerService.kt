package com.toolbox.smartcleaner.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.GestureDescription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 核心能力层 — 模仿人类操作的手势执行器
 *
 * 关键设计：
 * 1. 点击带随机偏移（不点中心点，模拟手指真实落点）
 * 2. 滑动使用贝塞尔曲线（手指不是直线滑动的）
 * 3. 时长加入随机抖动（每次操作时间不一致更像真人）
 * 4. 误触保护：边界区域不点（纯辅助用）
 */
class SmartCleanerService(
    private val accessibilityService: AccessibilityService
) {

    companion object {
        const val TAG = "SmartCleanerEngine"
        @Volatile var instance: SmartCleanerService? = null

        // === 仿人类随机参数 ===
        // 点击偏移：元素较小尺寸的 15%~35%
        private const val OFFSET_MIN_RATIO = 0.15f
        private const val OFFSET_MAX_RATIO = 0.35f
        // 点击时长随机范围 40ms ~ 120ms
        private const val CLICK_DURATION_MIN = 40L
        private const val CLICK_DURATION_MAX = 120L
        // 滑动时长随机增量 -50ms ~ +100ms
        private const val SWIPE_DURATION_JITTER = 100L
        // 滑动贝塞尔控制点偏移幅度（相对起止距离的 10%~30%）
        private const val BEZIER_OFFSET_MIN = 0.10f
        private const val BEZIER_OFFSET_MAX = 0.30f
        // 滑动折线段数（模拟手指采样点，6~12 段）
        private const val SEGMENTS_MIN = 6
        private const val SEGMENTS_MAX = 12
    }

    @Volatile var contextManager = ContextManager()
    private val pendingGestures = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val rng = java.util.Random()
    private var gestureIdCounter = 0

    init {
        instance = this
        Log.i(TAG, "SmartCleanerService initialized")
    }

    // ========== 公共 API ==========

    fun getFreshRoot(): AccessibilityNodeInfo? {
        return try {
            accessibilityService.rootInActiveWindow?.apply { refresh() }
        } catch (e: Exception) {
            Log.w(TAG, "getFreshRoot failed", e)
            null
        }
    }

    /**
     * 点击 — 自动在元素区域内随机偏移
     * @param bounds 目标元素在屏幕上的边界（可选），null 时偏移幅度减小
     */
    suspend fun click(x: Int, y: Int, bounds: Rect? = null): Boolean {
        val (fx, fy) = jitterClick(x, y, bounds)
        val duration = CLICK_DURATION_MIN + rng.nextLong() % (CLICK_DURATION_MAX - CLICK_DURATION_MIN)

        return performGesture { builder ->
            val path = Path().apply { moveTo(fx.toFloat(), fy.toFloat()) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
    }

    /**
     * 贝塞尔曲线滑动 — 模仿手指弧线轨迹
     * @param duration 基准时长（毫秒），实际会加上随机抖动
     */
    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 300
    ): Boolean {
        val actualDuration = (duration + (rng.nextLong() % (2 * SWIPE_DURATION_JITTER) - SWIPE_DURATION_JITTER))
            .coerceAtLeast(80L)

        return performGesture { builder ->
            val path = buildBezierSwipePath(startX, startY, endX, endY)
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, actualDuration))
        }
    }

    /**
     * 长按 — 在元素区域内随机偏移
     */
    suspend fun longPress(x: Int, y: Int, bounds: Rect? = null, baseDuration: Long = 1000): Boolean {
        val (fx, fy) = jitterClick(x, y, bounds)
        val duration = baseDuration + rng.nextLong() % 200  // 加随机尾巴

        return performGesture { builder ->
            val path = Path().apply { moveTo(fx.toFloat(), fy.toFloat()) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
    }

    fun inputText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return accessibilityService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            || getFreshRoot()?.let { root ->
                findEditableNode(root)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            } ?: false
    }

    fun globalAction(action: Int): Boolean = accessibilityService.performGlobalAction(action)

    // ========== 仿人类手势算法 ==========

    /**
     * 点击随机偏移 —— 在元素边界矩形内随机选点，偏向中心但不等于中心
     *
     * 算法：
     * 1. 计算元素宽高
     * 2. 随机偏移比例 [OFFSET_MIN_RATIO, OFFSET_MAX_RATIO]
     * 3. 以中心为基准，沿随机方向偏移
     */
    private fun jitterClick(cx: Int, cy: Int, bounds: Rect?): Pair<Int, Int> {
        if (bounds == null) {
            // 没有 bounds 时小幅度随机偏移 (3~12px)
            val offset = 3 + rng.nextInt(10)
            val angle = rng.nextDouble() * 2 * PI
            return (cx + (offset * cos(angle)).toInt()) to (cy + (offset * sin(angle)).toInt())
        }

        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return cx to cy

        // 以较小边为基准确定最大偏移半径
        val minDim = min(w, h)
        val radius = (minDim * (OFFSET_MIN_RATIO + rng.nextFloat() * (OFFSET_MAX_RATIO - OFFSET_MIN_RATIO))).toInt()
            .coerceIn(2, minDim / 2 - 1)

        val angle = rng.nextDouble() * 2 * PI
        val dx = (radius * cos(angle)).toInt()
        val dy = (radius * sin(angle)).toInt()

        // 确保不超出元素边界
        val fx = (cx + dx).coerceIn(bounds.left + 1, bounds.right - 1)
        val fy = (cy + dy).coerceIn(bounds.top + 1, bounds.bottom - 1)

        return fx to fy
    }

    /**
     * 贝塞尔曲线滑动路径 —— 模拟人类手指的自然弧线轨迹
     *
     * 算法：
     * 1. 在起点和终点之间随机生成 1~2 个控制点
     * 2. 控制点垂直偏离直线方向（模拟手指弧线）
     * 3. 路径用多段贝塞尔曲线拼接，每段约 30~50px
     * 4. 每个分段端点加入微小随机抖动（模拟手指微小震颤）
     */
    private fun buildBezierSwipePath(
        startX: Int, startY: Int,
        endX: Int, endY: Int
    ): Path {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())

        val dx = (endX - startX).toFloat()
        val dy = (endY - startY).toFloat()
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < 20f) {
            // 距离太短直接直线
            path.lineTo(endX.toFloat(), endY.toFloat())
            return path
        }

        // 计算垂直方向单位向量（用于控制点偏移）
        val len = max(distance, 1f)
        val nx = -dy / len  // 垂直方向
        val ny = -dx / len

        // 分段：每段 30~50px
        val segmentLen = 30 + rng.nextInt(21)
        val segments = (distance / segmentLen).toInt().coerceIn(SEGMENTS_MIN, SEGMENTS_MAX)

        for (i in 0 until segments) {
            val t = (i + 1).toFloat() / segments
            // 贝塞尔控制点 - 在垂直方向随机偏移
            val bezierOffset = (BEZIER_OFFSET_MIN + rng.nextFloat() * (BEZIER_OFFSET_MAX - BEZIER_OFFSET_MIN)) * distance
            val cpx = startX + dx * (t - 0.3f / segments) + nx * bezierOffset * (rng.nextFloat() - 0.5f) * 2f
            val cpy = startY + dy * (t - 0.3f / segments) + ny * bezierOffset * (rng.nextFloat() - 0.5f) * 2f

            // 终点（带随机微小抖动模拟手指震颤）
            val jitter = (distance * 0.01f).coerceIn(1f, 8f)
            val ex = endX + (rng.nextFloat() - 0.5f) * jitter
            val ey = endY + (rng.nextFloat() - 0.5f) * jitter

            // 二次贝塞尔：当前起点 → 控制点 → 段终点
            path.quadTo(
                cpx, cpy,
                ex, ey
            )
        }

        return path
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findEditableNode(child)
                if (found != null) return found
            }
        }
        return null
    }

    // ========== 通用手势执行器 ==========

    private suspend fun performGesture(build: (GestureDescription.Builder) -> Unit): Boolean {
        val id = gestureIdCounter++
        val deferred = CompletableDeferred<Boolean>()
        pendingGestures[id] = deferred

        val builder = GestureDescription.Builder()
        build(builder)
        val gesture = builder.build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                pendingGestures.remove(id)?.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                pendingGestures.remove(id)?.complete(false)
            }
        }

        accessibilityService.dispatchGesture(gesture, callback, null)
        return withTimeoutOrNull(3000) { deferred.await() } ?: false
    }
}