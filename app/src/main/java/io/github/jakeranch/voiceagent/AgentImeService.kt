package io.github.jakeranch.voiceagent

import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * 隐藏输入法：用来给高德这类"自绘搜索框"注入文字。
 * 无障碍读不到它的输入框节点，但输入法连接是存在的——切换到这个输入法后
 * commitText 就能把字打进去（和正常键盘同一条通道）。
 * 需要用户在系统里启用一次"语音管家键盘"。
 */
class AgentImeService : InputMethodService() {

    companion object {
        @Volatile
        var instance: AgentImeService? = null

        @Volatile
        var pendingText: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogBus.log("输入法通道已启动")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View =
        TextView(this).apply { text = "语音管家正在输入…" }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val text = pendingText
        if (text != null) {
            pendingText = null
            val ic = currentInputConnection
            if (ic != null) {
                ic.commitText(text, 1)
                LogBus.log("输入法通道提交成功：$text")
            } else {
                LogBus.log("输入法通道未获得连接")
            }
        }
    }

    /** 本输入法的绑定 token（供反射 setInputMethod 使用）。 */
    val token: IBinder?
        get() = try {
            val f = InputMethodService::class.java.getDeclaredField("mToken")
            f.isAccessible = true
            f.get(this) as? IBinder
        } catch (e: Exception) {
            null
        }
}
