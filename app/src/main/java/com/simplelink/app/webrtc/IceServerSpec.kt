package com.simplelink.app.webrtc

data class IceServerSpec(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)
