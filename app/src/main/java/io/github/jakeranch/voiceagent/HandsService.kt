package io.github.jakeranch.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍之手：读屏幕文本树 + 点按/输入/滑动/返回/打开应用。
 */
class HandsService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 当前窗口的可见文本树（带类名），供 DeepSeek 决策；上限约 8000 字。 */
    fun dumpText(): String {
        val sb = StringBuilder()
        val root = rootInActiveWindow ?: return ""
        collect(root, sb, 0)
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12 || sb.length > 8000) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            sb.append("  ".repeat(depth))
                .append(if (!text.isNullOrEmpty()) text else desc)
                .append('\n')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, sb, depth + 1) }
        }
    }

    /** 点击包含 [text] 的可点击元素；必要时点它的父级。 */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            if (n.isVisibleToUser && n.isClickable) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (n in nodes) {
            if (n.isVisibleToUser && n.parent != null) {
                n.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    /** 按比例坐标点击（0 到 1）。 */
    fun tapByFraction(fx: Float, fy: Float): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return tapAt(w * fx, h * fy)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 按比例坐标滑动。 */
    fun swipe(fx1: Float, fy1: Float, fx2: Float, fy2: Float): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w * fx1, h * fy1)
            lineTo(w * fx2, h * fy2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 在当前焦点输入框输入文字。 */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 按名称打开应用（只支持有主入口的应用）。 */
    fun openApp(labelOrPkg: String): Boolean {
        val pm = packageManager
        val query = labelOrPkg.trim()
        if (query.contains('.') && pm.getLaunchIntentForPackage(query) != null) {
            launch(pm.getLaunchIntentForPackage(query)!!)
            return true
        }
        for (app in pm.getInstalledApplications(0)) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.contains(query, ignoreCase = true)) {
                pm.getLaunchIntentForPackage(app.packageName)?.let {
                    launch(it)
                    return true
                }
            }
        }
        return false
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        @Volatile
        var instance: HandsService? = null
    }
}
