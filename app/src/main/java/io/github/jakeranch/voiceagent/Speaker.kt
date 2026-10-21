package io.github.jakeranch.voiceagent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 系统 TTS 朗读（中文）。 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val tts: TextToSpeech = TextToSpeech(appContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINESE
            ready = true
            LogBus.log("朗读引擎就绪")
            // 预热：空播一段，消除首次合成的明显延迟
            runCatching { tts.speak("", TextToSpeech.QUEUE_FLUSH, null, "voice_agent_warm") }
        } else {
            LogBus.log("朗读引擎初始化失败（状态码 $status），回复可能没声音")
            ChatBus.agent("未检测到系统中文语音引擎，回复可能没有声音")
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        // 扬声器开关禁用时不念出声音
        val enabled = appContext.getSharedPreferences(MainActivity.PREFS, 0)
            .getBoolean("speaker_enabled", true)
        if (!enabled) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_agent_tts")
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
