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
        你在帮主人操作一台安卓手机，主人用语音或文字下指令。你会连续多轮执行：每轮收到"主人指令、现在时间、已完成步骤、当前屏幕文本"，你每轮只输出一个 JSON 对象，不要任何其他文字。
        动作（action）：
        {"action":"done","text":"简短总结"} 任务完成时输出，text 会被朗读
        {"action":"answer","text":"回答"} 只回答问题、不动手机
        {"action":"set_alarm","minutes":35} 主人要求"X分钟后/小时后叫醒我/提醒我"时用，minutes 是几分钟后（1小时=60分钟），用这个一步到位，不要再打开时钟应用
        {"action":"tap_text","text":"屏幕上的文字"} 点击包含该文字的元素
        {"action":"tap_xy","x":0.5,"y":0.5} 按比例坐标点击（0 到 1）
        {"action":"type","text":"要输入的内容"} 在当前输入框输入（屏幕上有 [可输入] 标记的就是输入框；如果看不到输入框就先点击搜索栏；输完如需确认用 enter）
        {"action":"enter"} 按回车/搜索键（输入完地址、搜索词后用）
        {"action":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2} 滑动
        {"action":"back"} 返回键
        {"action":"home"} 回到桌面
        {"action":"open_app","text":"应用名"} 打开应用（名字不要带"打开"等动词）
        规则：
        - 主人的指令由语音识别生成，可能有同音错别字（如"紫金港"被听成"紫荆港"）或末尾被截断；执行前先根据上下文纠正识别文本，再按纠正后的意思执行；
        - 任务没完成就继续输出下一步动作，一轮只做一个动作；
        - 屏幕文本里每个文字后面的 (x,y) 是该元素的屏幕中心坐标（0 到 1 比例），tap_xy 也用同样比例，尽量用 tap_xy 点坐标而不要猜 tap_text；
        - 往下翻内容用 {"action":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.3}，往上翻用反向；
        - 如果连续两次相同的操作屏幕没有变化，换一种方式，禁止第三次重复同样的点击坐标；
        - 如果 open_app 在历史里显示"找不到"，绝对不要再重复 open_app 同一个名字，改用点击桌面图标或直接告诉主人；
        - 遇到付款、输入密码、指纹、人脸识别、短信验证码等环节，绝对不要替主人操作：输出 {"action":"answer","text":"接下来需要你自己完成付款/验证，完成后告诉我"} 并结束本轮；
        - 时间换算要准确；回答要短。
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
