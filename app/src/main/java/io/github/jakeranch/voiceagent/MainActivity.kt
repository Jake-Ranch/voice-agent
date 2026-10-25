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
    private lateinit var abortBtn: TextView
    private lateinit var speakerBtn: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: TextView
    private lateinit var scrim: View
    private lateinit var silLabel: TextView
    private lateinit var keyInput: EditText
    private lateinit var visionKeyInput: EditText
    private lateinit var visionModelInput: EditText
    private lateinit var visionEndpointInput: EditText
    private lateinit var brainModelInput: EditText
    private lateinit var brainEndpointInput: EditText
    private lateinit var wakeInput: EditText
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_CALENDAR)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.READ_CALENDAR)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.READ_CONTACTS)
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
        }

        val pad = (14 * resources.displayMetrics.density).toInt()
        // 根容器：FrameLayout，支持左侧滑出抽屉
        val root = android.widget.FrameLayout(this)
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(237, 237, 237))
        }
        root.addView(
            main,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

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
        abortBtn = TextView(this).apply {
            text = "✋终止"
            textSize = 15f
            setTextColor(Color.RED)
            setPadding(pad / 2, 0, 0, 0)
            visibility = View.GONE
            setOnClickListener {
                AgentService.abort()
                LogBus.log("已请求终止")
                refresh()
            }
        }
        top.addView(abortBtn)
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        top.addView(spacer)
        speakerBtn = navIcon("🔊")
        top.addView(speakerBtn)
        top.addView(navIcon("📋") { logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        top.addView(navIcon("⚙️") { openSettings() })
        main.addView(top)

        // ── 流式识别实时文本（正在听：×××）──
        liveText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad / 2, pad, 0)
            visibility = View.GONE
        }
        main.addView(liveText)

        // ── 设置抽屉（左侧滑出，内容可滚动；点外面空白收起）──
        val panelWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                panelWidth,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START,
            )
            translationX = -panelWidth.toFloat()
            visibility = View.GONE
        }
        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        settingsPanel.addView(
            ScrollView(this).apply {
                addView(settingsContent)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            },
        )
        settingsContent.addView(TextView(this).apply {
            text = "DeepSeek API Key（只存本机）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        keyInput = EditText(this).apply {
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deepseek_key", ""))
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(keyInput)
        settingsContent.addView(Button(this).apply {
            text = "保存并开启语音管家"
            setOnClickListener { saveAndStart() }
        })
        settingsContent.addView(Button(this).apply {
            text = "停止语音管家"
            setOnClickListener {
                AgentService.stop(this@MainActivity)
                refresh()
            }
        })
        settingsContent.addView(Button(this).apply {
            text = "开启无障碍操作权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        settingsContent.addView(Button(this).apply {
            text = "启用打字通道（一次性，解决高德输入）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        // ── 视觉模型（看图决策）──
        settingsContent.addView(TextView(this).apply {
            text = "视觉模型 API Key（留空=用大脑的 key）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        visionKeyInput = EditText(this).apply {
            hint = "留空默认用大脑 key"
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("vision_key", ""))
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(visionKeyInput)
        settingsContent.addView(TextView(this).apply {
            text = "视觉模型名（默认 deepseek-v4-flash-vision-exp）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        visionModelInput = EditText(this).apply {
            setText(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("vision_model", "deepseek-v4-flash-vision-exp"),
            )
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(visionModelInput)
        settingsContent.addView(TextView(this).apply {
            text = "视觉接口地址（OpenAI 兼容）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        visionEndpointInput = EditText(this).apply {
            setText(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("vision_endpoint", "https://api.deepseek.com/chat/completions"),
            )
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(visionEndpointInput)

        // ── 大脑模型（默认 DeepSeek，可换任何 OpenAI 兼容服务）──
        settingsContent.addView(TextView(this).apply {
            text = "大脑模型名（默认 deepseek-chat）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        brainModelInput = EditText(this).apply {
            setText(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("brain_model", "deepseek-chat"),
            )
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(brainModelInput)
        settingsContent.addView(TextView(this).apply {
            text = "大脑接口地址（OpenAI 兼容）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        brainEndpointInput = EditText(this).apply {
            setText(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("brain_endpoint", "https://api.deepseek.com/chat/completions"),
            )
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(brainEndpointInput)

        // ── 记忆 ──
        settingsContent.addView(Button(this).apply {
            text = "清空管家记忆"
            setOnClickListener {
                MemoryStore.clear(this@MainActivity)
                LogBus.log("记忆已清空")
                refresh()
            }
        })

        // ── 自定义唤醒词 ──
        settingsContent.addView(TextView(this).apply {
            text = "唤醒词（空格分隔多个，默认 小助手）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        wakeInput = EditText(this).apply {
            setText(
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("wake_words", "小助手"),
            )
            setTextColor(Color.BLACK)
        }
        settingsContent.addView(wakeInput)

        // ── 说完判定静音时长（可调）──
        val silRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
        }
        silRow.addView(TextView(this).apply {
            text = "说完判定静音："
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        silRow.addView(Button(this).apply {
            text = "－"
            setOnClickListener { adjustSilence(-0.5f) }
        })
        silLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(pad, 0, pad, 0)
        }
        silRow.addView(silLabel)
        silRow.addView(Button(this).apply {
            text = "＋"
            setOnClickListener { adjustSilence(0.5f) }
        })
        settingsContent.addView(silRow)

        // 遮罩必须先加：FrameLayout 后加的盖在上面，面板要盖在遮罩之上
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeSettings() }
        }
        root.addView(
            scrim,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
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
        main.addView(logScroll)

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
        main.addView(chatScroll)

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
        main.addView(inputRow)
        toggleInputMode()

        refreshSilenceLabel()
        setContentView(root)
        refreshLoop()
    }

    private fun openSettings() {
        settingsPanel.translationX = -settingsPanel.width.toFloat()
        settingsPanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        settingsPanel.animate().translationX(0f).setDuration(200).withEndAction {
            val loc = IntArray(2)
            settingsPanel.getLocationOnScreen(loc)
            LogBus.log("抽屉面板位置：${loc[0]},${loc[1]} 宽=${settingsPanel.width} 高=${settingsPanel.height}")
        }.start()
        LogBus.log("设置抽屉已打开")
        refreshSilenceLabel()
    }

    private fun closeSettings() {
        settingsPanel.animate()
            .translationX(-settingsPanel.width.toFloat())
            .setDuration(200)
            .withEndAction {
                settingsPanel.visibility = View.GONE
                scrim.visibility = View.GONE
            }
            .start()
        LogBus.log("设置抽屉已收起")
    }

    private fun silenceSeconds(): Float =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("end_silence_sec", 3.0f)

    private fun adjustSilence(delta: Float) {
        val v = (silenceSeconds() + delta).coerceIn(1.0f, 10.0f)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat("end_silence_sec", v).apply()
        refreshSilenceLabel()
    }

    private fun refreshSilenceLabel() {
        silLabel.text = "%.1f 秒".format(silenceSeconds())
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
            .edit()
            .putString("deepseek_key", key)
            .putString("vision_key", visionKeyInput.text.toString().trim())
            .putString(
                "vision_model",
                visionModelInput.text.toString().trim().ifBlank { "deepseek-v4-flash-vision-exp" },
            )
            .putString(
                "vision_endpoint",
                visionEndpointInput.text.toString().trim()
                    .ifBlank { "https://api.deepseek.com/chat/completions" },
            )
            .putString(
                "brain_model",
                brainModelInput.text.toString().trim().ifBlank { "deepseek-chat" },
            )
            .putString(
                "brain_endpoint",
                brainEndpointInput.text.toString().trim()
                    .ifBlank { "https://api.deepseek.com/chat/completions" },
            )
            .putString("wake_words", wakeInput.text.toString().trim().ifBlank { "小助手" })
            .apply()
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

        // 终止按钮：只在倾听/执行时显示
        abortBtn.visibility = if (
            StateBus.current == StateBus.State.WORKING ||
            StateBus.current == StateBus.State.LISTENING
        ) View.VISIBLE else View.GONE

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
