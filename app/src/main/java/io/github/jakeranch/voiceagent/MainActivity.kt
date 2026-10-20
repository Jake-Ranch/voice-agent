package io.github.jakeranch.voiceagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 极简主界面：DeepSeek key 输入 + 开启/停止 + 实时状态。
 * 无动画、无图标，只有一个面板。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "DeepSeek API Key（只存本机）"
            textSize = 15f
            setTextColor(Color.BLACK)
        })
        keyInput = EditText(this).apply {
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deepseek_key", ""))
            setTextColor(Color.BLACK)
        }
        root.addView(keyInput)
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setLineSpacing(6f, 1f)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "保存 key 并开启语音管家"
            setOnClickListener { saveAndStart() }
        })
        root.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                AgentService.stop(this@MainActivity)
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "开启无障碍操作权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refreshLoop()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun refreshLoop() {
        handler.postDelayed({ if (!isFinishing) { refresh(); refreshLoop() } }, 1_000)
    }

    private fun saveAndStart() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            status.text = "请先粘贴 DeepSeek API Key"
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("deepseek_key", key).apply()
        AgentService.start(this)
        refresh()
    }

    private fun refresh() {
        val svc = if (AgentService.isRunning) "运行中" else "未运行"
        val asr = LocalAsr.state + (LocalAsr.lastError?.let { "（$it）" } ?: "")
        status.text = "语音服务：$svc\n本地识别：$asr\n\n" +
            "──── 事件日志 ────\n" + LogBus.snapshot().ifBlank { "（暂无）" }
    }

    companion object {
        const val PREFS = "voice_agent"
    }
}
