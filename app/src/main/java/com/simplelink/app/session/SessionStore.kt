package com.simplelink.app.session

import android.content.Context

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("simplelink_session", Context.MODE_PRIVATE)

    fun saveActiveCode(code: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_CODE, code)
            .putLong(KEY_EXPIRES, expiresAtMillis)
            .apply()
    }

    fun activeCode(nowMillis: Long = System.currentTimeMillis()): String? {
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        if (expires <= nowMillis) {
            clear()
            return null
        }
        return prefs.getString(KEY_CODE, null)
    }

    fun clear() {
        prefs.edit().remove(KEY_CODE).remove(KEY_EXPIRES).apply()
    }

    companion object {
        private const val KEY_CODE = "active_code"
        private const val KEY_EXPIRES = "active_code_expires"
    }
}
