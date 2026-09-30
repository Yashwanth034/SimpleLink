package com.simplelink.app.rescue

import com.simplelink.app.session.SessionCode
import java.util.UUID

data class RescueRequest(
    val code: String,
    val requestId: String,
    val senderAddress: String?
)

object RescueProtocol {
    private const val PREFIX = "SL1"

    fun createRequest(code: String, requestId: String = UUID.randomUUID().toString()): String {
        return "$PREFIX|R|${SessionCode.normalize(code)}|$requestId"
    }

    fun createSmsMessage(code: String, requestId: String, appHash: String?): String {
        val normalized = SessionCode.normalize(code)
        val lines = mutableListOf(
            "<#> ${createRequest(normalized, requestId)}",
            "simplelink://rescue?c=$normalized&r=$requestId"
        )
        if (!appHash.isNullOrBlank()) lines += appHash
        return lines.joinToString("\n")
    }

    fun parse(message: String, senderAddress: String?): RescueRequest? {
        val protocol = message.lineSequence()
            .map { it.trim().removePrefix("<#>").trim() }
            .firstOrNull { it.startsWith("$PREFIX|R|") }
            ?: return null
        val parts = protocol.split('|')
        if (parts.size != 4 || parts[0] != PREFIX || parts[1] != "R") return null
        val code = SessionCode.normalize(parts[2])
        if (!SessionCode.isValid(code)) return null
        val requestId = parts[3]
        if (requestId.length !in 8..80) return null
        return RescueRequest(code, requestId, senderAddress)
    }
}
