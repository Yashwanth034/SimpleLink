package com.simplelink.app.control

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object ControlAvailability {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RemoteControlAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
