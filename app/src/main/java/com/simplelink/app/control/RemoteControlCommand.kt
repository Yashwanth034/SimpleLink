package com.simplelink.app.control

import org.json.JSONObject

sealed interface RemoteControlCommand {
    fun toJson(): JSONObject

    data class Tap(val x: Float, val y: Float) : RemoteControlCommand {
        override fun toJson() = JSONObject().put("type", "tap").put("x", x).put("y", y)
    }

    data class LongPress(val x: Float, val y: Float) : RemoteControlCommand {
        override fun toJson() = JSONObject().put("type", "long_press").put("x", x).put("y", y)
    }

    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long
    ) : RemoteControlCommand {
        override fun toJson() = JSONObject()
            .put("type", "swipe")
            .put("x1", x1).put("y1", y1)
            .put("x2", x2).put("y2", y2)
            .put("durationMs", durationMs)
    }

    data class Global(val action: String) : RemoteControlCommand {
        override fun toJson() = JSONObject().put("type", "global").put("action", action)
    }

    data class TextInput(val text: String) : RemoteControlCommand {
        override fun toJson() = JSONObject().put("type", "text").put("text", text)
    }

    data class SetText(val text: String) : RemoteControlCommand {
        override fun toJson() = JSONObject().put("type", "set_text").put("text", text)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteControlCommand? = when (json.optString("type")) {
            "tap" -> Tap(json.optDouble("x").toFloat(), json.optDouble("y").toFloat())
            "long_press" -> LongPress(json.optDouble("x").toFloat(), json.optDouble("y").toFloat())
            "swipe" -> Swipe(
                json.optDouble("x1").toFloat(),
                json.optDouble("y1").toFloat(),
                json.optDouble("x2").toFloat(),
                json.optDouble("y2").toFloat(),
                json.optLong("durationMs", 300L).coerceIn(80L, 2_000L)
            )
            "global" -> Global(json.optString("action"))
            "text" -> TextInput(json.optString("text").take(2_000))
            "set_text" -> SetText(json.optString("text").take(4_000))
            else -> null
        }
    }
}
