package io.github.jakeranch.voiceagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：操作手机（点按/输入/滑动/返回）。
 * 骨架版：后续阶段实现手势与节点操作。
 */
class HandsService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 后续：屏幕变化时更新文本树缓存，供 DeepSeek 决策。
    }

    override fun onInterrupt() {
    }
}
