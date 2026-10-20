package io.github.jakeranch.voiceagent

/**
 * 本地离线识别（sherpa-onnx SenseVoice）。
 * 骨架版：只有状态字段；下一阶段接入真实模型。
 */
object LocalAsr {

    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isReady: Boolean get() = state == "ready"
}
