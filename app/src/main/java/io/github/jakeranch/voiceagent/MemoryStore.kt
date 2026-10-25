package io.github.jakeranch.voiceagent

import android.content.Context

/**
 * 长期记忆：主人说过的重要偏好/事实，每轮自动注入提示词。
 * 存本机 SharedPreferences。
 */
object MemoryStore {

    private const val KEY = "memories"

    fun remember(context: Context, text: String) {
        val p = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        set.add(text.take(200))
        p.edit().putStringSet(KEY, set).apply()
    }

    fun all(context: Context): List<String> =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toList()

    fun clear(context: Context) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
