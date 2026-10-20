package io.github.jakeranch.voiceagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 大脑：输入"主人指令 + 当前屏幕文本树"，输出一个 JSON 动作。
 */
object Brain {

    private const val URL = "https://api.deepseek.com/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val SYSTEM_PROMPT = """
        你在帮主人操作一台安卓手机，主人用语音下指令。你只输出一个 JSON 对象，不要任何其他文字。
        可选动作（action 字段）：
        {"action":"answer","text":"回复内容"} 只回答、不动手机（text 要口语化、简短，会被朗读）
        {"action":"tap_text","text":"屏幕上的文字"} 点击包含该文字的元素
        {"action":"tap_xy","x":0.5,"y":0.5} 按比例坐标点击（0 到 1）
        {"action":"type","text":"要输入的内容"} 在当前输入框输入
        {"action":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2} 滑动
        {"action":"back"} 返回键
        {"action":"home"} 回到桌面
        {"action":"open_app","text":"应用名称"} 打开一个应用
        {"action":"done","text":"完成总结"} 任务完成，text 会被朗读
        规则：能点文字就不要用坐标；输入前先确保有输入框；一步步来，每步只做一个动作；回答要短。
    """.trimIndent()

    /** 返回 DeepSeek 的 JSON 内容；失败返回 null。 */
    fun ask(apiKey: String, userPrompt: String): String? {
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("max_tokens", 800)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.e("Brain", "HTTP ${resp.code}")
                    return null
                }
                val text = resp.body?.string() ?: return null
                JSONObject(text).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").optString("content")
            }
        } catch (e: Exception) {
            android.util.Log.e("Brain", "call failed", e)
            null
        }
    }
}
