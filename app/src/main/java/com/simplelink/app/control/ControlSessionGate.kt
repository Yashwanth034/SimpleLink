package com.simplelink.app.control

import android.content.Context

/**
 * Keeps the AccessibilityService enabled only long enough for an explicitly
 * approved remote-control setup. The service disables itself when this window
 * expires unless a real remote session has become active.
 */
object ControlSessionGate {
    private const val PREFS = "simplelink_control_gate"
    private const val KEY_PREPARED_UNTIL = "prepared_until"
    private const val PREPARE_TTL_MS = 5 * 60 * 1000L

    fun prepare(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREPARED_UNTIL, System.currentTimeMillis() + PREPARE_TTL_MS)
            .apply()
    }

    fun isPrepared(context: Context): Boolean = remainingMs(context) > 0L

    fun remainingMs(context: Context): Long {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PREPARED_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREPARED_UNTIL)
            .apply()
    }
}
