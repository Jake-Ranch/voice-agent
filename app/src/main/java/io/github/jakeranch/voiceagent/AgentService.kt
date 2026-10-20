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
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 常驻语音管家：能量端点检测收音 → 本地识别 → 唤醒词匹配 →
 * 听指令 → DeepSeek 决策 → 无障碍执行 → TTS 朗读。
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var speaker: Speaker
    private var loopJob: kotlinx.coroutines.Job? = null

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
        speaker = Speaker(this)
        while (true) {
            updateNotification()
            if (!LocalAsr.isReady) {
                delay(3_000)
                continue
            }
            // 1) 等唤醒词
            val wakeWav = recordUtterance(maxIdleSeconds = 60) ?: continue
            val wakeText = LocalAsr.transcribe(wakeWav) ?: continue
            if (WAKE_WORDS.none { wakeText.contains(it) }) continue

            // 2) 唤醒反馈，听指令
            speaker.speak("在的")
            val cmdWav = recordUtterance(maxIdleSeconds = 20) ?: continue
            val cmd = LocalAsr.transcribe(cmdWav) ?: continue
            if (cmd.isBlank()) continue

            // 3) DeepSeek 决策
            val key = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString("deepseek_key", "").orEmpty()
            if (key.isBlank()) {
                speaker.speak("请先在应用里填写 DeepSeek 密钥")
                continue
            }
            val screen = HandsService.instance?.dumpText() ?: ""
            val reply = Brain.ask(key, "主人指令：$cmd\n当前屏幕文本：\n$screen") ?: continue

            // 4) 执行
            execute(reply)
            delay(2_000)
        }
    }

    private fun execute(reply: String) {
        val obj = runCatching { JSONObject(reply.trim()) }.getOrNull()
        if (obj == null) {
            speaker.speak(reply.take(200))
            return
        }
        val hands = HandsService.instance
        when (obj.optString("action")) {
            "answer", "done" -> speaker.speak(obj.optString("text"))
            "tap_text" -> if (hands == null || !hands.tapByText(obj.optString("text"))) {
                speaker.speak("没有找到要点的按钮")
            }
            "tap_xy" -> hands?.tapByFraction(
                obj.optDouble("x", 0.5).toFloat(),
                obj.optDouble("y", 0.5).toFloat(),
            )
            "type" -> if (hands == null || !hands.typeText(obj.optString("text"))) {
                speaker.speak("没有可输入的输入框")
            }
            "swipe" -> hands?.swipe(
                obj.optDouble("x1", 0.5).toFloat(), obj.optDouble("y1", 0.8).toFloat(),
                obj.optDouble("x2", 0.5).toFloat(), obj.optDouble("y2", 0.2).toFloat(),
            )
            "back" -> hands?.back()
            "home" -> hands?.home()
            "open_app" -> if (hands == null || !hands.openApp(obj.optString("text"))) {
                speaker.speak("没有找到这个应用")
            }
            else -> speaker.speak(obj.optString("text").ifBlank { "完成" })
        }
    }

    /**
     * 收音：16 kHz 单声道，能量检测到人声后开始收集，静音约 1.2 秒结束。
     * [maxIdleSeconds] 内完全没人说话则返回 null。
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
                    val isSpeech = rms > SPEECH_RMS
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
        private const val SPEECH_RMS = 400.0

        val WAKE_WORDS = listOf("小助手", "小管家", "管家")

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var lastStartError: String? = null

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
