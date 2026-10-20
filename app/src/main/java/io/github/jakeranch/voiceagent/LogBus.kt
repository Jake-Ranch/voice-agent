package io.github.jakeranch.voiceagent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 屏上事件日志：把每一步（听到、识别、大脑回复、执行）都记下来，
 * 显示在主界面，方便远程排查。
 */
object LogBus {

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 30) lines.removeFirst()
        android.util.Log.i("VoiceAgent", msg)
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")
}
