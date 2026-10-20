package io.github.jakeranch.voiceagent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 系统 TTS 朗读（中文）。 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINESE
            ready = true
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_agent_tts")
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
