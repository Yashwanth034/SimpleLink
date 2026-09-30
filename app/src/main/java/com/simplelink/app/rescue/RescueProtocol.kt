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
        return validate(parts[2], parts[3], senderAddress)
    }

    fun validate(
        code: String,
        requestId: String,
        senderAddress: String?
    ): RescueRequest? {
        val normalized = SessionCode.normalize(code)
        if (!SessionCode.isValid(normalized)) return null
        if (requestId.length !in 8..80) return null
        if (!requestId.all { it.isLetterOrDigit() || it in "._:-" }) return null
        return RescueRequest(normalized, requestId, senderAddress)
    }
}
