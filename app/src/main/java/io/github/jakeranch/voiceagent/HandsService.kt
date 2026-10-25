package io.github.jakeranch.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
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
        LogBus.log("无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        LogBus.log("无障碍服务断开")
        super.onDestroy()
    }

    /** 当前窗口的可见文本树（带类名），供 DeepSeek 决策；上限约 8000 字。 */
    fun dumpText(): String {
        val sb = StringBuilder()
        val root = rootInActiveWindow ?: return ""
        return try {
            collect(root, sb, 0)
            sb.toString()
        } catch (t: Throwable) {
            LogBus.log("读屏幕树异常：${t.javaClass.simpleName}: ${t.message}")
            sb.toString()
        }
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12 || sb.length > 12000) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val isEditableNode = node.isEditable
        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || isEditableNode) {
            // 附带中心坐标（0-1 比例），让大脑能用 tap_xy 精确点击；[可输入] 标记可输入框
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            val cx = ((r.left + r.right) / 2f / w).coerceIn(0f, 1f)
            val cy = ((r.top + r.bottom) / 2f / h).coerceIn(0f, 1f)
            val label = when {
                !text.isNullOrEmpty() -> text
                !desc.isNullOrEmpty() -> desc
                else -> "[输入框]"
            }
            val tag = if (isEditableNode) "[可输入]" else ""
            sb.append("  ".repeat(depth))
                .append(label)
                .append(tag)
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

    /** 在当前输入框输入文字（多策略：焦点框 → 任意可编辑节点 → 剪贴板粘贴 → 输入法切换）。 */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            // 1) 有焦点的输入框
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && setTextOn(focused, text)) return true
            // 2) 任意可见的可编辑节点（高德等自定义搜索框不报告焦点）
            val editable = findEditable(root)
            if (editable != null && setTextOn(editable, text)) return true
        }
        // 3) 自绘搜索框：无障碍树里没有输入节点 → 直接走输入法切换通道
        return typeViaIme(text)
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean {
        // 1) 标准 SET_TEXT
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 2) 剪贴板 + 粘贴
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("voice", text))
            if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ) return true
        } catch (e: Exception) {
        }
        // 3) Android 13+ 官方输入法通道注入（对高德等自定义搜索框最有效）
        if (injectViaIme(node, text)) return true
        // 4) 终极：切到我们自己的输入法提交再切回（自绘搜索框也能进）
        return typeViaIme(text)
    }

    /**
     * 终极输入方案：把活动输入法切到"语音管家键盘"，提交文字后切回。
     * 要求用户曾在系统里启用过它一次（否则 AgentImeService.instance 为空）。
     */
    fun typeViaIme(text: String): Boolean {
        val svc = AgentImeService.instance ?: run {
            // 键盘服务没在跑：弹输入法选择器让用户点一次（点完 pendingText 自动提交）
            AgentImeService.pendingText = text
            LogBus.log("输入法通道未绑定，已弹出输入法选择器，用户点选后自动提交")
            runCatching {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
            return true // 已安排提交（等用户点选），避免 agent 死循环重试
        }
        // 已有活跃输入连接：直接提交（连续输入时最常用）
        val live = svc.currentInputConnection
        if (live != null) {
            return try {
                live.commitText(text, 1)
                LogBus.log("输入法通道提交成功（直连）：$text")
                true
            } catch (e: Exception) {
                false
            }
        }
        val token = svc.token ?: return false
        return try {
            val cr = contentResolver
            val prev = android.provider.Settings.Secure.getString(
                cr, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            ).orEmpty()
            AgentImeService.pendingText = text
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            val m = android.view.inputmethod.InputMethodManager::class.java
                .getDeclaredMethod(
                    "setInputMethod",
                    android.os.IBinder::class.java,
                    String::class.java,
                )
            m.isAccessible = true
            m.invoke(imm, token, "$packageName/.AgentImeService")
            // 等提交完成后切回用户原来的输入法
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { m.invoke(imm, token, prev) }
            }, 900)
            true
        } catch (e: Exception) {
            LogBus.log("输入法切换失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** API 33+：伪装成输入法直接 commitText（官方 AccessibilityInputConnection）。 */
    private fun injectViaIme(node: AccessibilityNodeInfo, text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val im = getInputMethod() ?: return false
            val ic = im.currentInputConnection ?: return false
            // commitText 有 2 参（API 33）与 3 参（API 36）两个重载，用反射挑一个
            val m = ic.javaClass.methods.firstOrNull {
                it.name == "commitText" && it.parameterCount == 2
            } ?: ic.javaClass.methods.firstOrNull {
                it.name == "commitText" && it.parameterCount == 3
            } ?: return false
            if (m.parameterCount == 2) {
                m.invoke(ic, text, 1)
            } else {
                m.invoke(ic, text, 1, null)
            }
            true
        } catch (e: Exception) {
            LogBus.log("IME 注入失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // isEditable 或类名含 EditText 都算可输入（高德等自定义搜索框常用后者）
        val cn = node.className?.toString().orEmpty()
        if ((node.isEditable || cn.contains("EditText")) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child)?.let { return it }
            }
        }
        return null
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 按回车/搜索键（输入完内容后确认用）。 */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

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
        // 1) 全名包含
        for (ri in resolves) {
            val label = ri.loadLabel(pm).toString()
            if (label.contains(q, ignoreCase = true)) {
                return openClockIfNeeded(raw, ri.activityInfo.packageName)
            }
        }
        // 2) 宽松匹配：用查询的前 2~3 个字找（"高德地图" → 匹配"高德导航"）
        val fuzzy = listOfNotNull(q.take(3).takeIf { it.length >= 2 }, q.take(2)).distinct()
        for (tok in fuzzy) {
            for (ri in resolves) {
                val label = ri.loadLabel(pm).toString()
                if (label.contains(tok, ignoreCase = true)) {
                    LogBus.log("openApp 宽松匹配：$tok -> $label")
                    return openClockIfNeeded(raw, ri.activityInfo.packageName)
                }
            }
        }
        LogBus.log(
            "openApp 未找到「$raw」；本机应用：" +
                resolves.take(20).joinToString("、") { it.loadLabel(pm).toString() },
        )
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
