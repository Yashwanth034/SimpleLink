package com.simplelink.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class RemoteControlAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var remoteSessionActive = false

    private val idleDisableRunnable = Runnable {
        if (!remoteSessionActive && !ControlSessionGate.isPrepared(this)) {
            disableForPrivacy()
        }
    }

    fun setRemoteControlSessionActive(active: Boolean) {
        remoteSessionActive = active
        mainHandler.removeCallbacks(idleDisableRunnable)
        restoreRemoteKeyboard()
    }

    fun disableForPrivacy() {
        remoteSessionActive = false
        mainHandler.removeCallbacks(idleDisableRunnable)
        ControlSessionGate.clear(this)
        restoreRemoteKeyboard()
        runCatching { disableSelf() }
    }

    override fun onServiceConnected() {
        instance = this
        remoteSessionActive = false
        restoreRemoteKeyboard()
        mainHandler.removeCallbacks(idleDisableRunnable)

        val remaining = ControlSessionGate.remainingMs(this)
        if (remaining <= 0L) {
            mainHandler.post { disableForPrivacy() }
        } else {
            mainHandler.postDelayed(idleDisableRunnable, remaining + 250L)
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        remoteSessionActive = false
        mainHandler.removeCallbacks(idleDisableRunnable)
        restoreRemoteKeyboard()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun execute(
        json: JSONObject,
        onInputState: ((editable: Boolean, text: String) -> Unit)? = null
    ) {
        if (!remoteSessionActive) return

        when (val command = RemoteControlCommand.fromJson(json)) {
            is RemoteControlCommand.Tap -> tap(command.x, command.y) {
                reportInputState(onInputState)
            }
            is RemoteControlCommand.LongPress -> longPress(command.x, command.y) {
                reportInputState(onInputState)
            }
            is RemoteControlCommand.Swipe -> swipe(command)
            is RemoteControlCommand.Global -> global(command.action)
            is RemoteControlCommand.TextInput -> appendFocusedText(command.text)
            is RemoteControlCommand.SetText -> setFocusedText(command.text)
            null -> Unit
        }
    }

    private fun restoreRemoteKeyboard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            softKeyboardController.showMode = SHOW_MODE_AUTO
        }
    }

    private fun tap(nx: Float, ny: Float, onDone: (() -> Unit)? = null) {
        val metrics = realDisplayMetrics()
        val x = nx.coerceIn(0f, 1f) * (metrics.widthPixels - 1).coerceAtLeast(1)
        val y = ny.coerceIn(0f, 1f) * (metrics.heightPixels - 1).coerceAtLeast(1)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({ onDone?.invoke() }, 120)
                    mainHandler.postDelayed({ onDone?.invoke() }, 320)
                }
            },
            null
        )
    }

    private fun longPress(nx: Float, ny: Float, onDone: (() -> Unit)? = null) {
        val metrics = realDisplayMetrics()
        val x = nx.coerceIn(0f, 1f) * (metrics.widthPixels - 1).coerceAtLeast(1)
        val y = ny.coerceIn(0f, 1f) * (metrics.heightPixels - 1).coerceAtLeast(1)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 650))
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed({ onDone?.invoke() }, 120)
                    mainHandler.postDelayed({ onDone?.invoke() }, 320)
                }
            },
            null
        )
    }

    private fun swipe(command: RemoteControlCommand.Swipe) {
        val metrics = realDisplayMetrics()
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(1)
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(1)
        val path = Path().apply {
            moveTo(
                command.x1.coerceIn(0f, 1f) * maxX,
                command.y1.coerceIn(0f, 1f) * maxY
            )
            lineTo(
                command.x2.coerceIn(0f, 1f) * maxX,
                command.y2.coerceIn(0f, 1f) * maxY
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, command.durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun global(action: String) {
        val value = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        performGlobalAction(value)
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun appendFocusedText(text: String) {
        val node = findFocusedEditableTarget() ?: return
        val next = (editableText(node) + text).take(4_000)
        setNodeText(node, next)
        node.recycle()
    }

    private fun setFocusedText(text: String) {
        val node = findFocusedEditableTarget() ?: return
        setNodeText(node, text.take(4_000))
        node.recycle()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SimpleLink", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    private fun reportInputState(listener: ((Boolean, String) -> Unit)?) {
        listener ?: return
        val node = findFocusedEditableTarget()
        if (node == null) {
            listener(false, "")
            return
        }
        listener(true, editableText(node))
        node.recycle()
    }

    private fun findFocusedEditableTarget(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable) return it
            it.recycle()
        }

        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable) return it
            it.recycle()
        }
        return null
    }

    private fun editableText(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString().orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (node.isShowingHintText) return ""
            val hint = node.hintText?.toString().orEmpty()
            if (hint.isNotEmpty() && raw == hint) return ""
        }
        return raw.take(4_000)
    }

    companion object {
        @Volatile
        var instance: RemoteControlAccessibilityService? = null
            private set
    }
}
