package io.github.jakeranch.voiceagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 视觉大脑：截图 + 任务上下文 → 一个 JSON 动作。
 * 默认走 DeepSeek 官方多模态模型 deepseek-v4-flash-vision-exp（同一把 key），
 * 也可在设置里换成别的 OpenAI 兼容视觉服务。
 */
object Vision {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val SYSTEM_PROMPT = """
        你在帮主人操作一台安卓手机。你每轮收到：主人指令、现在时间、已完成步骤、当前屏幕截图。
        你只输出一个 JSON 对象（动作），不要任何其他文字。
        动作（action）：
        {"action":"done","text":"简短总结"} 任务完成，text 会被朗读
        {"action":"answer","text":"回答"} 只回答问题、不动手机
        {"action":"set_alarm","minutes":35} 主人要求"X分钟后/小时后叫醒我/提醒我"时用
        {"action":"set_alarm_at","hour":7,"minute":30} 具体时刻提醒时用
        {"action":"add_calendar","title":"事项","at":"2026-10-27 10:00"} 写日历日程
        {"action":"call_contact","text":"联系人名或号码"} 打电话
        {"action":"remember","text":"内容"} 记住偏好/事实
        {"action":"clear_memory"} 清空记忆
        {"action":"tap_xy","x":0.5,"y":0.5} 按比例坐标点击（0-1，坐标从截图实际位置估算）
        {"action":"tap_text","text":"屏幕上的文字"} 点击包含该文字的元素
        {"action":"type","text":"要输入的内容"} 输入文字（先点击输入框再 type，输完如需确认用 enter）
        {"action":"enter"} 回车/搜索键
        {"action":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2} 滑动
        {"action":"back"} 返回键
        {"action":"home"} 回到桌面
        {"action":"open_app","text":"应用名"} 打开应用
        规则：
        - 一轮只做一个动作，任务没完成就继续输出下一步；
        - 坐标必须按截图里的真实位置估算（0 到 1 比例），文字类目标优先 tap_xy；
        - 主人的指令是语音识别生成的，可能有同音错别字，执行前根据上下文纠正；
        - 遇到付款、密码、指纹、验证码，输出 answer 告诉主人自己完成，绝不代替操作。
    """.trimIndent()

    /**
     * [userPrompt] 文本 + [imageB64] JPEG base64 → JSON 动作字符串；失败返回 null。
     */
    fun ask(
        apiKey: String,
        endpoint: String,
        model: String,
        userPrompt: String,
        imageB64: String,
    ): String? {
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", userPrompt))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$imageB64"),
                ),
            )
        }
        val body = JSONObject().apply {
            put("model", model)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", content))
                },
            )
            put("max_tokens", 800)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.log("视觉模型 HTTP ${resp.code}")
                    return null
                }
                val text = resp.body?.string() ?: return null
                JSONObject(text).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").optString("content")
            }
        } catch (e: Exception) {
            LogBus.log("视觉模型调用失败：${e.message}")
            null
        }
    }
}
