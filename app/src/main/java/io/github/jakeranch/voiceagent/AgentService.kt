package io.github.jakeranch.voiceagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 常驻语音管家：
 * 文本指令队列 + 唤醒词（本地）→ 本地识别 → DeepSeek 决策 → 无障碍执行 → TTS 朗读。
 * 用户可见内容进 ChatBus（气泡），调试内容进 LogBus（日志面板）。
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var speaker: Speaker
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, build())
            }
        } catch (e: Exception) {
            lastStartError = e.message ?: e.javaClass.simpleName
            isRunning = false
            runCatching { startForeground(NOTIFICATION_ID, build()) }
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        runCatching { speaker.shutdown() }
        super.onDestroy()
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { loop() }
    }

    private suspend fun loop() {
        LocalAsr.ensureInit(this)
        speaker = withContext(Dispatchers.Main) { Speaker(this@AgentService) }
        LogBus.log("语音服务已启动")
        while (true) {
            updateNotification()
            // 0) 键盘输入的文本指令优先处理
            val typed = textQueue.poll()
            if (typed != null) {
                processCommand(typed)
                continue
            }
            if (!LocalAsr.isReady) {
                if (LocalAsr.state == "failed") {
                    StateBus.set(StateBus.State.FAULT)
                    LogBus.log("模型加载失败：" + (LocalAsr.lastError ?: "未知"))
                }
                delay(3_000)
                continue
            }
            // 1) 等唤醒词（15 秒一片，中途可插入文本指令）
            StateBus.set(StateBus.State.IDLE)
            var wakeText: String? = null
            for (i in 0 until 4) {
                val t = textQueue.poll()
                if (t != null) {
                    processCommand(t)
                    break
                }
                val wav = recordUtterance(maxIdleSeconds = 15)
                val txt = wav?.let { LocalAsr.transcribe(it) }
                if (!txt.isNullOrBlank()) {
                    wakeText = txt
                    break
                }
            }
            val wake = wakeText ?: continue
            LogBus.log("听到：$wake") // 调试日志，不进气泡
            if (WAKE_WORDS.none { wake.contains(it) }) continue
            LogBus.log("唤醒成功")

            // 2) 唤醒：柔和短促提示音，进入倾听
            beep()
            StateBus.set(StateBus.State.LISTENING)
            speaker.speak("在的")
            delay(1_200)
            val cmdWav = recordUtterance(maxIdleSeconds = 20)
            val cmd = cmdWav?.let { LocalAsr.transcribe(it) }
            if (cmd.isNullOrBlank()) {
                StateBus.set(StateBus.State.IDLE)
                continue
            }
            ChatBus.user(cmd)
            LogBus.log("指令：$cmd")
            processCommand(cmd)
            StateBus.set(StateBus.State.IDLE)
            delay(2_500)
        }
    }

    private suspend fun processCommand(cmd: String) {
        StateBus.set(StateBus.State.WORKING)
        LogBus.log("正在调用大模型…")
        val key = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getString("deepseek_key", "").orEmpty()
        if (key.isBlank()) {
            LogBus.log("缺少 DeepSeek key")
            val msg = "请先在设置里填写 DeepSeek 密钥"
            ChatBus.agent(msg)
            speaker.speak(msg)
            return
        }
        val screen = HandsService.instance?.dumpText() ?: ""
        val reply = Brain.ask(key, "主人指令：$cmd\n当前屏幕文本：\n$screen")
        if (reply == null) {
            LogBus.log("大脑无回应（key 或网络问题）")
            val msg = "大脑没有回应，请检查密钥和网络"
            ChatBus.agent(msg)
            speaker.speak(msg)
            return
        }
        LogBus.log("大脑：$reply")
        execute(reply)
        beep(ack = false) // 完成提示音
    }

    private fun execute(reply: String) {
        val obj = runCatching { JSONObject(reply.trim()) }.getOrNull()
        if (obj == null) {
            ChatBus.agent(reply.take(200))
            speaker.speak(reply.take(200))
            return
        }
        val hands = HandsService.instance
        val needsHands = obj.optString("action") !in setOf("answer", "done")
        if (needsHands && hands == null) {
            val msg = "请先开启无障碍权限（设置里的按钮）"
            ChatBus.agent(msg)
            speaker.speak(msg)
            return
        }
        when (obj.optString("action")) {
            "answer", "done" -> {
                val t = obj.optString("text").ifBlank { "完成" }
                ChatBus.agent(t)
                speaker.speak(t)
            }
            "tap_text" -> {
                val t = obj.optString("text")
                val ok = hands?.tapByText(t) == true
                val msg = if (ok) "已点击「$t」" else "没有找到要点的按钮"
                ChatBus.agent(msg)
                speaker.speak(msg)
            }
            "tap_xy" -> {
                hands?.tapByFraction(
                    obj.optDouble("x", 0.5).toFloat(),
                    obj.optDouble("y", 0.5).toFloat(),
                )
                ChatBus.agent("已点击屏幕")
                speaker.speak("好的")
            }
            "type" -> {
                val t = obj.optString("text")
                val ok = hands?.typeText(t) == true
                val msg = if (ok) "已输入" else "没有可输入的输入框"
                ChatBus.agent(msg)
                speaker.speak(msg)
            }
            "swipe" -> {
                hands?.swipe(
                    obj.optDouble("x1", 0.5).toFloat(), obj.optDouble("y1", 0.8).toFloat(),
                    obj.optDouble("x2", 0.5).toFloat(), obj.optDouble("y2", 0.2).toFloat(),
                )
                ChatBus.agent("已滑动屏幕")
                speaker.speak("好的")
            }
            "back" -> {
                hands?.back()
                ChatBus.agent("已返回")
                speaker.speak("好的")
            }
            "home" -> {
                hands?.home()
                ChatBus.agent("已回到桌面")
                speaker.speak("好的")
            }
            "open_app" -> {
                val q = obj.optString("text")
                val err = hands?.openApp(q)
                if (err == null) {
                    val msg = "已打开「$q」"
                    ChatBus.agent(msg)
                    speaker.speak("好的，已经打开了")
                } else {
                    ChatBus.agent(err)
                    speaker.speak(err)
                }
            }
            else -> {
                val t = obj.optString("text").ifBlank { "完成" }
                ChatBus.agent(t)
                speaker.speak(t)
            }
        }
    }

    /** 唤醒提示音：柔和、短促（用媒体流，MIUI 上提示流常被静音）。 */
    private fun beep(ack: Boolean = true) {
        if (!speakerEnabled()) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(if (ack) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 150)
            scope.launch {
                delay(400)
                runCatching { tone.release() }
            }
        }
    }

    private fun speakerEnabled(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean("speaker_enabled", true)

    /**
     * 收音：16 kHz 单声道，能量检测到人声后开始收集，静音约 1.2 秒结束。
     */
    private suspend fun recordUtterance(maxIdleSeconds: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val rate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(4096),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                return@withContext null
            }
            val out = ByteArrayOutputStream()
            val buf = ShortArray(1600) // 100 ms
            var speechSeen = false
            var lastSpeechAt = System.currentTimeMillis()
            val startAt = System.currentTimeMillis()
            var noiseFloor = 300.0
            try {
                rec.startRecording()
                while (System.currentTimeMillis() - startAt < maxIdleSeconds * 1000L) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i].toDouble()
                        sum += v * v
                    }
                    val rms = kotlin.math.sqrt(sum / n)
                    // 自适应噪声底：只在不说话的低电平帧更新，躺着远距离轻声也能触发
                    if (!speechSeen && rms < 800) {
                        noiseFloor = noiseFloor * 0.95 + rms * 0.05
                    }
                    val threshold = (noiseFloor * 4).coerceAtLeast(150.0)
                    val isSpeech = rms > threshold
                    if (isSpeech) {
                        speechSeen = true
                        lastSpeechAt = System.currentTimeMillis()
                    }
                    if (speechSeen) {
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            out.write(s and 0xFF)
                            out.write((s shr 8) and 0xFF)
                        }
                        if (!isSpeech && System.currentTimeMillis() - lastSpeechAt > 1_200) break
                        if (System.currentTimeMillis() - startAt > 30_000) break
                    }
                }
            } catch (e: Exception) {
                return@withContext null
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            val pcm = out.toByteArray()
            if (!speechSeen || pcm.size < 16000) return@withContext null // 至少 0.5 秒
            wav(pcm)
        }

    private fun wav(pcm: ByteArray): ByteArray {
        val rate = 16_000
        val channels = 1
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val header = java.nio.ByteBuffer.allocate(44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(rate)
        header.putInt(byteRate)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, build()) }
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "语音管家", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stateText = when (LocalAsr.state) {
            "ready" -> "说「小助手」唤醒"
            "failed" -> "识别模型加载失败：" + (LocalAsr.lastError ?: "未知")
            else -> "识别模型加载中，请稍候"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("语音管家")
            .setContentText(stateText)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "voice_agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.jakeranch.voiceagent.STOP"

        val WAKE_WORDS = listOf("小助手", "小管家", "管家")

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var lastStartError: String? = null

        private val textQueue = ConcurrentLinkedQueue<String>()

        /** 键盘模式提交文本指令（不用唤醒）。 */
        fun submitText(text: String) {
            if (text.isBlank()) return
            ChatBus.user(text)
            textQueue.add(text)
        }

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                lastStartError = e.message ?: e.javaClass.simpleName
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
