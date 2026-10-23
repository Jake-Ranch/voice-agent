package io.github.jakeranch.voiceagent

import android.graphics.Color

/**
 * 语音管家的四态状态机，主界面圆点按它变色：
 * 红=故障，黄=待机等待唤醒，绿=已唤醒正在倾听，蓝=正在调用大模型并执行。
 */
object StateBus {

    enum class State { FAULT, IDLE, LISTENING, WORKING }

    @Volatile
    var current: State = State.IDLE
        private set

    /** 流式识别的实时文本（边说边显示）。 */
    @Volatile
    var partialText: String = ""

    fun set(s: State) {
        current = s
    }

    val label: String
        get() = when (current) {
            State.FAULT -> "故障"
            State.IDLE -> "待机，等待唤醒"
            State.LISTENING -> "已唤醒，正在倾听"
            State.WORKING -> "正在调用大模型并执行"
        }

    val color: Int
        get() = when (current) {
            State.FAULT -> Color.RED
            State.IDLE -> Color.rgb(200, 160, 0) // 黄
            State.LISTENING -> Color.rgb(0, 160, 0) // 绿
            State.WORKING -> Color.rgb(30, 110, 220) // 蓝
        }
}
