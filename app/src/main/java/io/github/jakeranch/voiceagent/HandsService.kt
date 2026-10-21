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
        if (depth > 12 || sb.length > 12000) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            // 附带中心坐标（0-1 比例），让大脑能用 tap_xy 精确点击
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            val cx = ((r.left + r.right) / 2f / w).coerceIn(0f, 1f)
            val cy = ((r.top + r.bottom) / 2f / h).coerceIn(0f, 1f)
            sb.append("  ".repeat(depth))
                .append(if (!text.isNullOrEmpty()) text else desc)
                .append(String.format(" (%.2f,%.2f)", cx, cy))
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

    /** 按名称打开应用（只支持有主入口的应用）。返回 null 表示成功，否则为原因。 */
    fun openApp(labelOrPkg: String): String? {
        // 剥掉可能混进来的动词和"应用"后缀
        val raw = labelOrPkg.trim()
            .replace(Regex("^(请|帮我|给我)?(打开|启动|运行|进入)"), "")
            .replace(Regex("(应用|app)$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (raw.isEmpty()) return "没有指定应用名"
        val q = NAME_ALIASES[raw] ?: raw
        LogBus.log("openApp: raw=$raw q=$q")
        val pm = packageManager

        // 直接是包名
        if (q.contains('.') && pm.getLaunchIntentForPackage(q) != null) {
            return openClockIfNeeded(raw, q)
        }
        // 按名称找（用 ACTION_MAIN/LAUNCHER 查询，比 getLaunchIntentForPackage 更可靠）
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = pm.queryIntentActivities(launcher, 0)
        for (ri in resolves) {
            val label = ri.loadLabel(pm).toString()
            if (label.contains(q, ignoreCase = true)) {
                return openClockIfNeeded(raw, ri.activityInfo.packageName)
            }
        }
        return "找不到叫「$raw」的应用"
    }

    /** 闹钟/时钟类指令直接进系统闹钟设置页，而不是停在时钟首页。 */
    private fun openClockIfNeeded(raw: String, pkg: String): String? {
        if (pkg.contains("clock") || raw.contains("闹钟") || raw.contains("秒表") || raw.contains("计时器") || pkg.contains("deskclock")) {
            return try {
                startActivity(
                    Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                null
            } catch (e: Exception) {
                openByPackage(pkg)
            }
        }
        return openByPackage(pkg)
    }

    private fun openByPackage(pkg: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "「$pkg」没有启动入口"
        launch(intent)
        return null
    }

    /**
     * 设置 X 分钟后的系统闹钟。无障碍服务由系统绑定，启动界面有豁免。
     * 返回人类可读结果；"被拦"表示两种启动都被系统拒绝。
     */
    fun setAlarm(minutes: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, minutes)
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = cal.get(java.util.Calendar.MINUTE)
        fun mkIntent(skipUi: Boolean): Intent =
            Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hh)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mm)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "语音管家提醒")
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 1) 全自动（部分手机的时钟支持免确认）
        if (launchAlarm(mkIntent(true))) return "已设置 $minutes 分钟后（$hh:$mm）的闹钟"
        // 2) 预填好时间的编辑页（点一下保存）
        if (launchAlarm(mkIntent(false))) return "闹钟已预填好（$hh:$mm），点一下保存即可"
        return "闹钟界面被拦"
    }

    private fun launchAlarm(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        @Volatile
        var instance: HandsService? = null

        /** 常见叫法 → 应用实际名称。 */
        private val NAME_ALIASES = mapOf(
            "闹钟" to "时钟",
            "秒表" to "时钟",
            "计时器" to "时钟",
            "相册" to "图库",
            "照片" to "图库",
        )
    }
}
