package io.github.jakeranch.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面：
 * 顶栏：状态圆点 + 扬声器开关 / 日志 / 设置（齿轮）；
 * 中部：微信风聊天气泡（我方绿泡在右，管家灰白泡在左）；
 * 底部：语音/键盘输入切换 + 文本框。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dot: View
    private lateinit var stateText: TextView
    private lateinit var speakerBtn: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: TextView
    private lateinit var keyInput: EditText
    private lateinit var inputToggle: TextView
    private lateinit var textInput: EditText
    private lateinit var inputRow: LinearLayout
    private var keyboardMode = false
    private var lastChatCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
        }

        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(237, 237, 237))
        }

        // ── 顶栏 ──
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.WHITE)
        }
        dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt())
        }
        top.addView(dot)
        stateText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(pad / 2, 0, 0, 0)
        }
        top.addView(stateText)
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        top.addView(spacer)
        speakerBtn = navIcon("🔊")
        top.addView(speakerBtn)
        top.addView(navIcon("📋") { logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        top.addView(navIcon("⚙️") { settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        root.addView(top)

        // ── 流式识别实时文本（正在听：×××）──
        liveText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad / 2, pad, 0)
            visibility = View.GONE
        }
        root.addView(liveText)

        // ── 设置面板（点齿轮显示）──
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        settingsPanel.addView(TextView(this).apply {
            text = "DeepSeek API Key（只存本机）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        keyInput = EditText(this).apply {
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deepseek_key", ""))
            setTextColor(Color.BLACK)
        }
        settingsPanel.addView(keyInput)
        settingsPanel.addView(Button(this).apply {
            text = "保存 key 并开启语音管家"
            setOnClickListener { saveAndStart() }
        })
        settingsPanel.addView(Button(this).apply {
            text = "停止语音管家"
            setOnClickListener {
                AgentService.stop(this@MainActivity)
                refresh()
            }
        })
        settingsPanel.addView(Button(this).apply {
            text = "开启无障碍操作权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        root.addView(settingsPanel)

        // ── 日志面板（点日志图标显示；固定约 15 行高，可上下拖动）──
        logScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt(),
            )
            setBackgroundColor(Color.rgb(250, 250, 240))
        }
        logPanel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        logScroll.addView(logPanel)
        root.addView(logScroll)

        // ── 聊天区（气泡）──
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val chatScroll = ScrollView(this).apply {
            addView(chatContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(chatScroll)

        // ── 底部输入行 ──
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.WHITE)
        }
        inputToggle = TextView(this).apply {
            text = "🎤"
            textSize = 24f
            setPadding(pad, 0, pad, 0)
            setOnClickListener { toggleInputMode() }
        }
        inputRow.addView(inputToggle)
        textInput = EditText(this).apply {
            hint = "输入指令，回车发送"
            setTextColor(Color.BLACK)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendText()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(textInput)
        val sendBtn = Button(this).apply {
            text = "发送"
            setOnClickListener { sendText() }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)
        toggleInputMode()

        setContentView(root)
        refreshLoop()
    }

    private fun navIcon(emoji: String, onClick: (() -> Unit)? = null): TextView {
        val pad = (10 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = emoji
            textSize = 22f
            setPadding(pad, pad / 2, pad, pad / 2)
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun toggleInputMode() {
        keyboardMode = !keyboardMode
        inputToggle.text = if (keyboardMode) "⌨️" else "🎤"
        textInput.visibility = if (keyboardMode) View.VISIBLE else View.GONE
    }

    private fun sendText() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return
        textInput.setText("")
        AgentService.submitText(text)
        if (!AgentService.isRunning) {
            AgentService.start(this)
        }
        refresh()
    }

    private fun refreshLoop() {
        handler.postDelayed({ if (!isFinishing) { refresh(); refreshLoop() } }, 800)
    }

    private fun saveAndStart() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            LogBus.log("请先粘贴 DeepSeek API Key")
            refresh()
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("deepseek_key", key).apply()
        AgentService.start(this)
        refresh()
    }

    private fun refresh() {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(StateBus.color)
        }
        dot.background = gd
        stateText.text = " " + StateBus.label

        // 流式识别实时文本
        val partial = StateBus.partialText
        if (partial.isBlank()) {
            liveText.visibility = View.GONE
        } else {
            liveText.visibility = View.VISIBLE
            liveText.text = "正在听：$partial"
        }

        val speakerOn = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("speaker_enabled", true)
        speakerBtn.text = if (speakerOn) "🔊" else "🔇"
        speakerBtn.setOnClickListener {
            val on = !getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("speaker_enabled", true)
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("speaker_enabled", on).apply()
            refresh()
        }

        if (logScroll.visibility == View.VISIBLE) {
            logPanel.text = LogBus.snapshot().ifBlank { "（暂无调试日志）" }
        }

        // 气泡重建：仅当消息数变化
        val msgs = ChatBus.snapshot()
        if (msgs.size != lastChatCount) {
            lastChatCount = msgs.size
            chatContainer.removeAllViews()
            val density = resources.displayMetrics.density
            for (m in msgs) {
                val isUser = m is ChatBus.Msg.User
                val text = when (m) {
                    is ChatBus.Msg.User -> m.text
                    is ChatBus.Msg.Agent -> m.text
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (isUser) Gravity.END else Gravity.START
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }
                val bubble = TextView(this).apply {
                    this.text = text
                    textSize = 16f
                    setTextColor(if (isUser) Color.WHITE else Color.BLACK)
                    setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                    maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * density
                        setColor(if (isUser) Color.rgb(7, 193, 96) else Color.WHITE)
                    }
                }
                row.addView(bubble)
                chatContainer.addView(row)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val PREFS = "voice_agent"
        private const val REQ_PERMS = 42
    }
}
