package io.github.jakeranch.voiceagent

/**
 * 聊天气泡流：用户消息（右侧绿泡）与管家消息（左侧灰白泡）。
 * 调试信息只进 LogBus，不在这里。
 */
object ChatBus {

    sealed class Msg {
        data class User(val text: String) : Msg()
        data class Agent(val text: String) : Msg()
    }

    private val msgs = ArrayDeque<Msg>()

    @Synchronized
    fun user(text: String) = add(Msg.User(text))

    @Synchronized
    fun agent(text: String) = add(Msg.Agent(text))

    @Synchronized
    private fun add(m: Msg) {
        msgs.addLast(m)
        while (msgs.size > 100) msgs.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Msg> = msgs.toList()
}
