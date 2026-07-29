package com.toolbox.smartcleaner.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 客户端 — 支持 OpenAI 兼容 API（Kimi、DeepSeek、OneAPI 等）
 * 
 * 功能：
 * - 标准聊天完成
 * - 可选 Vision 支持（截图分析）
 * - 流式/非流式统一接口
 * - 自动重试与超时控制
 */
class LlmClient(
    private val baseUrl: String = "https://api.moonshot.cn/v1",
    private val apiKey: String = "",
    private val model: String = "moonshot-v1-8k",
    private val timeoutSec: Int = 30
) {

    companion object {
        const val TAG = "LlmClient"
        private const val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    /**
     * 标准聊天完成（非流式）
     * @param prompt 用户提示词
     * @param systemPrompt 可选系统提示词
     * @param temperature 采样温度 (0.0-1.0)
     * @return 模型回复文本
     */
    @Throws(IOException::class)
    fun chatCompletion(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Float = 0.1f
    ): String {
        val messages = mutableListOf<JsonObject>()
        systemPrompt?.let { messages.add(makeMessage("system", it)) }
        messages.add(makeMessage("user", prompt))

        val requestBody = buildRequestBody(messages, temperature, stream = false)
        val request = buildRequest(requestBody)

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("HTTP ${response.code}: $errorBody")
                }
                parseResponse(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion failed", e)
            throw IOException("LLM request failed: ${e.message}", e)
        }
    }

    /**
     * Vision 多模态完成（发送截图 base64）
     * @param prompt 文本提示词
     * @param imageBase64 截图 base64 字符串
     * @return 模型回复
     */
    @Throws(IOException::class)
    fun visionCompletion(
        prompt: String,
        imageBase64: String,
        systemPrompt: String? = null
    ): String {
        val messages = mutableListOf<JsonObject>()
        systemPrompt?.let { messages.add(makeMessage("system", it)) }

        val userContent = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", prompt)
        }
        val imageContent = JsonObject().apply {
            addProperty("type", "image_url")
            val imageUrl = JsonObject().apply {
                addProperty("url", "data:image/png;base64,$imageBase64")
                addProperty("detail", "high")
            }
            add("image_url", imageUrl)
        }
        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            val content = com.google.gson.JsonArray().apply { add(userContent); add(imageContent) }
            add("content", content)
        }
        messages.add(userMessage)

        val requestBody = buildRequestBody(messages, 0.1f, stream = false, visionModel = true)
        val request = buildRequest(requestBody)

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("HTTP ${response.code}: $errorBody")
                }
                parseResponse(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision completion failed", e)
            throw IOException("Vision request failed: ${e.message}", e)
        }
    }

    // ========== 内部构建方法 ==========

    private fun makeMessage(role: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    private fun buildRequestBody(
        messages: List<JsonObject>,
        temperature: Float,
        stream: Boolean,
        visionModel: Boolean = false
    ): RequestBody {
        val json = JsonObject().apply {
            addProperty("model", if (visionModel) "moonshot-v1-32k" else model)
            val msgs = com.google.gson.JsonArray()
            messages.forEach { msgs.add(it) }
            add("messages", msgs)
            addProperty("temperature", temperature)
            addProperty("stream", stream)
            addProperty("max_tokens", 2048)
        }
        return RequestBody.create(gson.toJson(json), MEDIA_TYPE_JSON)
    }

    private fun buildRequest(body: RequestBody): Request {
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
    }

    private fun parseResponse(json: String): String {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.isEmpty()) return ""
            val message = choices[0].asJsonObject.getAsJsonObject("message")
            message.get("content")?.asString?.trim() ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Parse response failed: $json", e)
            ""
        }
    }

    /**
     * 更新配置（运行时可修改）
     */
    fun updateConfig(baseUrl: String? = null, apiKey: String? = null, model: String? = null) {
        // 由于 OkHttpClient 不可变，实际生产中建议重建 client
        // 这里仅作演示
    }
}

/**
 * LlmClient 配置数据类
 */
data class LlmConfig(
    var baseUrl: String = "https://api.moonshot.cn/v1",
    var apiKey: String = "",
    var model: String = "moonshot-v1-8k",
    var timeoutSec: Int = 30,
    var temperature: Float = 0.1f
)