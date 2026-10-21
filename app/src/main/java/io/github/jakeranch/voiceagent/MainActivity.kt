package io.github.jakeranch.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 极简主界面：状态圆点 + 对话框（识别文字、管家回复、事件）+ key 输入 + 开关。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dot: View
    private lateinit var stateText: TextView
    private lateinit var chat: TextView
    private lateinit var keyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 麦克风权限必须运行时请求，否则收音静默失败
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

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        // 状态行：圆点 + 文字
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotSize = (16 * resources.displayMetrics.density).toInt()
        dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
        }
        row.addView(dot)
        stateText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(pad, 0, 0, 0)
        }
        row.addView(stateText)
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "DeepSeek API Key（只存本机）"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        keyInput = EditText(this).apply {
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deepseek_key", ""))
            setTextColor(Color.BLACK)
        }
        root.addView(keyInput)

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

        // 对话框：识别文字与管家回复都在这里
        chat = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setLineSpacing(4f, 1f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(chat)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshLoop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
        chat.text = LogBus.snapshot().ifBlank { "（暂无消息）" }
    }

    companion object {
        const val PREFS = "voice_agent"
        private const val REQ_PERMS = 42
    }
}
