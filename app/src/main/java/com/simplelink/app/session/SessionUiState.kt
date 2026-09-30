package com.simplelink.app.session

sealed interface SessionUiState {
    data object Home : SessionUiState

    data class Sharing(
        val code: String,
        val status: String = "Waiting for connection…",
        val pending: IncomingRequest? = null,
        val needsControlSetup: Boolean = false,
        val rescueInternetPrompt: Boolean = false
    ) : SessionUiState

    data class Joining(
        val code: String,
        val status: String = "Connecting…",
        val rescueAvailable: Boolean = false
    ) : SessionUiState

    data class Remote(
        val role: Role,
        val status: String = "Connected"
    ) : SessionUiState

    data class Error(
        val message: String,
        val returnTo: SessionUiState = Home
    ) : SessionUiState
}

data class IncomingRequest(
    val requestId: String,
    val label: String = "Another phone",
    val viaRescueSms: Boolean = false,
    val senderAddress: String? = null
)

enum class Role { HOST, VIEWER }
