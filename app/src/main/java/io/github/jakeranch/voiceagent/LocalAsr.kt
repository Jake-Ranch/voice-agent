package io.github.jakeranch.voiceagent

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地离线识别：sherpa-onnx + SenseVoice int8。
 * 模型打包在 assets/asr/，首次使用解压到私有目录，之后全离线识别。
 */
object LocalAsr {

    @Volatile
    private var ready = false

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var initStarted = false

    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isReady: Boolean get() = ready

    @Synchronized
    fun ensureInit(context: Context) {
        if (initStarted) return
        initStarted = true
        Thread {
            try {
                state = "copying"
                val dir = File(context.filesDir, "asr").apply { mkdirs() }
                val model = File(dir, "model.int8.onnx")
                val tokens = File(dir, "tokens.txt")
                if (!model.exists() || model.length() < 1_000_000L) {
                    context.assets.open("asr/model.int8.onnx").use { ins ->
                        model.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
                if (!tokens.exists()) {
                    context.assets.open("asr/tokens.txt").use { ins ->
                        tokens.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
                state = "loading"
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "zh",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                recognizer = OfflineRecognizer(config = config)
                ready = true
                state = "ready"
                LogBus.log("本地识别模型就绪")
            } catch (t: Throwable) {
                android.util.Log.e("LocalAsr", "init failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                state = "failed"
                ready = false
                LogBus.log("模型加载失败：$lastError")
            }
        }.start()
    }

    /** 转写一段 16 kHz 单声道 PCM16 WAV（含 44 字节头）。 */
    suspend fun transcribe(wav: ByteArray): String? = withContext(Dispatchers.Default) {
        val rec = recognizer ?: return@withContext null
        if (wav.size <= 44) return@withContext null
        val n = (wav.size - 44) / 2
        val samples = FloatArray(n)
        for (i in 0 until n) {
            val lo = wav[44 + i * 2].toInt() and 0xFF
            val hi = wav[44 + i * 2 + 1].toInt()
            samples[i] = (((hi shl 8) or lo).toShort().toFloat()) / 32768f
        }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            rec.decode(stream)
            rec.getResult(stream).text?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            android.util.Log.e("LocalAsr", "transcribe failed", t)
            null
        } finally {
            runCatching { stream.release() }
        }
    }
}
