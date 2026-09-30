package com.simplelink.app.signaling

import com.simplelink.app.BuildConfig
import com.simplelink.app.webrtc.IceServerSpec
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SignalingClient(private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var open = false
    private var connecting = false
    private var reconnectScheduled = false
    private var intentionalClose = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var hostRegistration: String? = null
    private var joinRegistration: String? = null
    private var viewerKey = UUID.randomUUID().toString()
    private var sessionCode: String? = null
    private val pendingMessages = ArrayDeque<String>()

    var onIncomingRequest: ((String) -> Unit)? = null
    var onRequestSent: ((String) -> Unit)? = null
    var onApproved: ((String) -> Unit)? = null
    var onRejected: (() -> Unit)? = null
    var onHostRegistered: ((String) -> Unit)? = null
    var onCodeConflict: (() -> Unit)? = null
    var onPeerLeft: (() -> Unit)? = null
    var onSignal: ((String, JSONObject) -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null
    var onNotFound: (() -> Unit)? = null
    var onIceConfig: ((List<IceServerSpec>) -> Unit)? = null

    fun registerHost(code: String, hostKey: String) {
        val text = JSONObject()
            .put("type", "register")
            .put("code", code)
            .put("hostKey", hostKey)
            .toString()
        synchronized(this) {
            sessionCode = code
            hostRegistration = text
            joinRegistration = null
        }
        enqueueText(text)
    }

    fun join(code: String, requestId: String = UUID.randomUUID().toString()) {
        val text = JSONObject()
            .put("type", "join")
            .put("code", code)
            .put("requestId", requestId)
            .put("viewerKey", viewerKey)
            .toString()
        synchronized(this) {
            sessionCode = code
            joinRegistration = text
            hostRegistration = null
        }
        enqueueText(text)
    }

    fun approve(requestId: String, code: String? = null, hostKey: String? = null) {
        val message = JSONObject()
            .put("type", "approve")
            .put("requestId", requestId)
        if (!code.isNullOrBlank()) message.put("code", code)
        if (!hostKey.isNullOrBlank()) message.put("hostKey", hostKey)
        enqueue(message)
    }

    fun reject(requestId: String) {
        enqueue(JSONObject().put("type", "reject").put("requestId", requestId))
    }

    fun sendSignal(requestId: String, payload: JSONObject) {
        enqueue(
            JSONObject()
                .put("type", "signal")
                .put("requestId", requestId)
                .put("payload", payload)
        )
    }

    @Synchronized
    fun close() {
        reconnectJob?.cancel()
        reconnectJob = null
        pendingMessages.clear()
        hostRegistration = null
        joinRegistration = null
        sessionCode = null
        viewerKey = UUID.randomUUID().toString()
        open = false
        connecting = false
        reconnectScheduled = false
        reconnectAttempt = 0
        intentionalClose = true
        socket?.close(1000, "done")
        socket = null
    }

    private fun enqueue(message: JSONObject) = enqueueText(message.toString())

    @Synchronized
    private fun enqueueText(text: String) {
        if (BuildConfig.SIGNALING_URL.isBlank() || BuildConfig.SIGNALING_URL.contains("example.com")) {
            scope.launch { onUnavailable?.invoke() }
            return
        }

        val current = socket
        if (open && current != null) {
            current.send(text)
            return
        }

        if (!pendingMessages.contains(text)) pendingMessages.addLast(text)
        if (current == null && !connecting) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectScheduled = false
            intentionalClose = false
            connectLocked()
        }
    }

    private fun connectLocked() {
        connecting = true
        val code = sessionCode
        if (code.isNullOrBlank()) {
            connecting = false
            scope.launch { onUnavailable?.invoke() }
            return
        }
        val separator = if (BuildConfig.SIGNALING_URL.contains("?")) "&" else "?"
        val socketUrl = "${BuildConfig.SIGNALING_URL}${separator}code=$code"
        val request = runCatching { Request.Builder().url(socketUrl).build() }
            .getOrElse {
                connecting = false
                pendingMessages.clear()
                scope.launch { onUnavailable?.invoke() }
                return
            }

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val queued = synchronized(this@SignalingClient) {
                    if (socket !== webSocket) return
                    open = true
                    connecting = false
                    reconnectScheduled = false
                    reconnectAttempt = 0
                    buildList {
                        while (pendingMessages.isNotEmpty()) add(pendingMessages.removeFirst())
                    }
                }
                queued.forEach(webSocket::send)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                scope.launch { handle(message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val retry = synchronized(this@SignalingClient) {
                    if (socket !== webSocket) return@synchronized false
                    socket = null
                    open = false
                    connecting = false
                    !intentionalClose
                }
                if (retry) handleTransportLoss()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val retry = synchronized(this@SignalingClient) {
                    if (socket !== webSocket) return@synchronized false
                    socket = null
                    open = false
                    connecting = false
                    !intentionalClose
                }
                if (retry) handleTransportLoss()
            }
        })
    }

    private fun handleTransportLoss() {
        scope.launch { onUnavailable?.invoke() }
        scheduleReconnect()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (intentionalClose || reconnectScheduled) return

        val persistent = joinRegistration ?: hostRegistration
        if (persistent != null && !pendingMessages.contains(persistent)) {
            pendingMessages.addFirst(persistent)
        }
        if (pendingMessages.isEmpty()) return

        reconnectScheduled = true
        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(15_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(this@SignalingClient) {
                reconnectScheduled = false
                if (!intentionalClose && socket == null && !connecting) {
                    connectLocked()
                }
            }
        }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "registered" -> onHostRegistered?.invoke(message.optString("code"))
            "incoming_request" -> onIncomingRequest?.invoke(message.optString("requestId"))
            "request_sent" -> onRequestSent?.invoke(message.optString("requestId"))
            "approved" -> onApproved?.invoke(message.optString("requestId"))
            "rejected" -> {
                synchronized(this) { joinRegistration = null }
                onRejected?.invoke()
            }
            "peer_left" -> onPeerLeft?.invoke()
            "signal" -> {
                val requestId = message.optString("requestId")
                val payload = message.optJSONObject("payload") ?: return
                onSignal?.invoke(requestId, payload)
            }
            "ice_config" -> {
                val array = message.optJSONArray("servers") ?: return
                val servers = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val urlsArray = item.optJSONArray("urls") ?: continue
                        val urls = buildList {
                            for (j in 0 until urlsArray.length()) add(urlsArray.optString(j))
                        }.filter { it.isNotBlank() }
                        if (urls.isNotEmpty()) {
                            add(IceServerSpec(urls, item.optString("username"), item.optString("credential")))
                        }
                    }
                }
                onIceConfig?.invoke(servers)
            }
            "busy" -> {
                if (message.optString("reason") == "code_in_use") onCodeConflict?.invoke()
                else onUnavailable?.invoke()
            }
            "not_found", "expired" -> onNotFound?.invoke()
            "error" -> onUnavailable?.invoke()
        }
    }
}
