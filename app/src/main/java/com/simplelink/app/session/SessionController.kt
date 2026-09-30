package com.simplelink.app.session

import android.content.Context
import android.content.Intent
import com.simplelink.app.control.ControlAvailability
import com.simplelink.app.control.ControlSessionGate
import com.simplelink.app.control.RemoteControlCommand
import com.simplelink.app.control.RemoteControlAccessibilityService
import com.simplelink.app.nearby.NearbyCoordinator
import com.simplelink.app.rescue.RescueRequest
import com.simplelink.app.rescue.RescueSmsRetriever
import com.simplelink.app.signaling.SignalingClient
import com.simplelink.app.util.ConnectivityMonitor
import com.simplelink.app.webrtc.IceServerSpec
import com.simplelink.app.webrtc.ScreenSize
import com.simplelink.app.webrtc.WebRtcEngine
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class SessionController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SessionStore(context)
    private val connectivity = ConnectivityMonitor(context)
    private val signaling = SignalingClient(scope)
    private val nearby = NearbyCoordinator(context, scope)
    val webRtc = WebRtcEngine(context)

    private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Home)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    val remoteVideoTrack = webRtc.remoteVideoTrack
    val remoteScreenSize: StateFlow<ScreenSize> = webRtc.remoteScreenSize
    val remoteInputState = webRtc.remoteInputState

    private var pendingProjectionRequestId: String? = null
    private var approvedRescueRequestId: String? = null
    private var deniedRescueRequestId: String? = null
    private var resumeAllowAfterAccessibility = false
    private var controlReadyJob: Job? = null
    private var joinRetryJob: Job? = null
    private var reconnectJob: Job? = null
    private var hostLeaseJob: Job? = null
    private var hostSessionKey: String? = null
    private var nearbyFallbackKey: String? = null
    private var internetIceServers: List<IceServerSpec> = emptyList()

    init {
        signaling.onIncomingRequest = { requestId ->
            when (requestId) {
                deniedRescueRequestId -> signaling.reject(requestId)
                approvedRescueRequestId -> continueApprovedRescue(requestId)
                else -> incomingRequest(requestId, "Another phone")
            }
        }
        signaling.onRequestSent = {
            joinRetryJob?.cancel()
            joinRetryJob = null
            val current = _state.value
            if (current is SessionUiState.Joining) {
                _state.value = current.copy(status = "Waiting for approval…", rescueAvailable = false)
            }
        }
        signaling.onApproved = { requestId ->
            joinRetryJob?.cancel()
            joinRetryJob = null
            if (_state.value is SessionUiState.Joining) {
                startViewerWebRtc(requestId)
            }
        }
        signaling.onRejected = { failJoin("Connection was denied") }
        signaling.onHostRegistered = { code ->
            val current = _state.value
            if (current is SessionUiState.Sharing && current.code == code) {
                _state.value = current.copy(
                    status = if (approvedRescueRequestId != null) {
                        "Internet restored. Reconnecting…"
                    } else {
                        "Waiting for connection…"
                    },
                    rescueInternetPrompt = false
                )
            }
        }
        signaling.onCodeConflict = { handleCodeConflict() }
        signaling.onNotFound = {
            val current = _state.value
            if (current is SessionUiState.Joining) {
                _state.value = current.copy(
                    status = "Phone not found or code expired",
                    rescueAvailable = true
                )
            }
        }
        signaling.onBusy = { reason ->
            val current = _state.value
            if (current is SessionUiState.Joining) {
                val status = when (reason) {
                    "session_busy" -> "Phone is already in a support session"
                    "rate_limited" -> "Too many attempts · Try again shortly"
                    "daily_capacity", "admission_paused" ->
                        "SimpleLink is temporarily at capacity · Try again later"
                    else -> "Connection temporarily unavailable"
                }
                _state.value = current.copy(status = status, rescueAvailable = false)
            }
        }
        signaling.onPeerLeft = { endRemoteSession("Connection ended") }
        signaling.onSignal = webRtc::handleSignal
        signaling.onIceConfig = { internetIceServers = it }
        signaling.onUnavailable = {
            val current = _state.value
            val internetAvailable = connectivity.hasInternet()
            when (current) {
                is SessionUiState.Sharing -> {
                    if (current.pending == null && pendingProjectionRequestId == null) {
                        if (internetAvailable) {
                            _state.value = current.copy(
                                status = "SimpleLink server unavailable · Retrying…"
                            )
                        } else {
                            requestNearbyFallback(NearbyMode.HOST, current.code)
                            _state.value = current.copy(
                                status = "Offline · Looking for nearby connection…"
                            )
                        }
                    }
                }
                is SessionUiState.Joining -> if (current.status != "Waiting for approval…") {
                    if (internetAvailable) {
                        _state.value = current.copy(
                            status = "SimpleLink server unavailable · Retrying…",
                            rescueAvailable = false
                        )
                    } else {
                        requestNearbyFallback(NearbyMode.JOIN, current.code)
                        _state.value = if (joinRetryJob?.isActive == true) {
                            current.copy(status = "Rescue sent. Waiting for phone…", rescueAvailable = false)
                        } else {
                            current.copy(status = "Looking for the phone nearby…", rescueAvailable = true)
                        }
                    }
                }
                else -> Unit
            }
        }

        nearby.onIncomingRequest = { requestId -> incomingRequest(requestId, "Nearby phone") }
        nearby.onApproved = { requestId -> startViewerWebRtc(requestId) }
        nearby.onRejected = { failJoin("Connection was denied") }
        nearby.onSignal = webRtc::handleSignal
        nearby.onStatus = { status ->
            val current = _state.value
            when (current) {
                is SessionUiState.Sharing -> if (!connectivity.hasInternet()) {
                    _state.value = current.copy(status = status)
                }
                is SessionUiState.Joining -> if (!connectivity.hasInternet()) {
                    _state.value = current.copy(status = status)
                }
                else -> Unit
            }
        }

        webRtc.onConnected = {
            val current = _state.value
            if (current is SessionUiState.Remote) {
                _state.value = current.copy(status = "Connected")
            }
        }
        webRtc.onDisconnected = { endRemoteSession("Connection ended") }
    }

    fun startSharing() {
        deniedRescueRequestId = null
        val code = SessionCode.generate()
        val key = UUID.randomUUID().toString()
        val hasInternet = connectivity.hasInternet()
        hostSessionKey = key
        store.saveActiveCode(code, System.currentTimeMillis() + CODE_TTL_MS)
        _state.value = SessionUiState.Sharing(
            code = code,
            status = if (hasInternet) "Connecting…" else "Ready nearby or by rescue SMS",
            needsControlSetup = !ControlAvailability.isEnabled(context)
        )
        if (hasInternet) {
            signaling.registerHost(code, key)
        } else {
            requestNearbyFallback(NearbyMode.HOST, code)
        }
        RescueSmsRetriever.arm(context)
        maintainHostLease(code, key)
        _events.tryEmit(SessionEvent.EnsureNotificationPermission)
    }

    fun startNearbyHost(code: String) {
        runCatching { nearby.startHost(code) }
    }

    fun stopSharing() {
        cleanup()
        _state.value = SessionUiState.Home
    }

    fun startJoining(rawCode: String) {
        val code = SessionCode.normalize(rawCode)
        if (!SessionCode.isValid(code)) return
        val hasInternet = connectivity.hasInternet()
        joinRetryJob?.cancel()
        _state.value = SessionUiState.Joining(code)
        if (hasInternet) {
            signaling.join(code)
            scope.launch {
                delay(4_000)
                val current = _state.value
                if (
                    current is SessionUiState.Joining &&
                    current.code == code &&
                    current.status == "Connecting…"
                ) {
                    _state.value = current.copy(status = "Phone not found or code expired", rescueAvailable = true)
                }
            }
        } else {
            requestNearbyFallback(NearbyMode.JOIN, code)
            _state.value = SessionUiState.Joining(code, "Looking for the phone nearby…")
        }
    }

    fun startNearbyJoin(code: String) {
        runCatching { nearby.startJoin(code) }
    }

    private fun incomingRequest(requestId: String, label: String) {
        val current = _state.value
        if (current is SessionUiState.Sharing && current.pending == null) {
            _state.value = current.copy(
                pending = IncomingRequest(requestId = requestId, label = label),
                status = "Connection request"
            )
        }
    }

    fun incomingRescueRequest(request: RescueRequest) {
        val activeCode = store.activeCode() ?: return
        if (activeCode != request.code || deniedRescueRequestId == request.requestId) return
        val current = _state.value
        val base = if (current is SessionUiState.Sharing && current.code == request.code) {
            current
        } else {
            SessionUiState.Sharing(
                code = request.code,
                status = "Rescue request",
                needsControlSetup = !ControlAvailability.isEnabled(context)
            )
        }
        _state.value = base.copy(
            pending = IncomingRequest(
                requestId = request.requestId,
                label = "Rescue request",
                viaRescueSms = true,
                senderAddress = request.senderAddress
            ),
            status = "Rescue request"
        )
    }

    fun prepareControlSetup() {
        ControlSessionGate.prepare(context)
        _events.tryEmit(SessionEvent.OpenAccessibilitySettings)
    }

    fun allowPending() {
        val current = _state.value as? SessionUiState.Sharing ?: return
        val request = current.pending ?: return

        if (!ControlAvailability.isEnabled(context)) {
            resumeAllowAfterAccessibility = true
            ControlSessionGate.prepare(context)
            _events.tryEmit(SessionEvent.OpenAccessibilitySettings)
            _state.value = current.copy(status = "Enable SimpleLink Control to continue")
            return
        }

        if (RemoteControlAccessibilityService.instance == null) {
            resumeAllowAfterAccessibility = true
            _state.value = current.copy(status = "Starting control…")
            waitForControlReady()
            return
        }

        controlReadyJob?.cancel()
        controlReadyJob = null
        resumeAllowAfterAccessibility = false

        if (request.viaRescueSms) {
            approvedRescueRequestId = request.requestId
            _state.value = current.copy(
                pending = null,
                status = "Turn on internet to continue",
                rescueInternetPrompt = true
            )
            _events.tryEmit(SessionEvent.OpenInternetPanel)
            beginReconnectLoop(current.code)
            return
        }

        approveTransportRequest(request.requestId)
    }

    fun denyPending() {
        disableIdleControl()
        val current = _state.value as? SessionUiState.Sharing ?: return
        current.pending?.let { request ->
            if (request.viaRescueSms) {
                deniedRescueRequestId = request.requestId
                approvedRescueRequestId = null
            } else {
                rejectTransportRequest(request.requestId)
            }
        }
        _state.value = current.copy(pending = null, status = "Waiting for connection…")
    }

    private fun approveTransportRequest(requestId: String) {
        pendingProjectionRequestId = requestId
        val current = _state.value
        if (current is SessionUiState.Sharing) {
            _state.value = current.copy(pending = null, status = "Waiting for screen permission…")
        }
        _events.tryEmit(SessionEvent.RequestScreenCapture(requestId))
    }

    private fun continueApprovedRescue(requestId: String) {
        if (approvedRescueRequestId != requestId) return
        approvedRescueRequestId = null
        approveTransportRequest(requestId)
    }

    private fun rejectTransportRequest(requestId: String) {
        if (requestId.startsWith(NEARBY_PREFIX)) nearby.reject(requestId)
        else signaling.reject(requestId)
    }

    fun onProjectionPermissionGranted(data: Intent) {
        val requestId = pendingProjectionRequestId ?: return
        pendingProjectionRequestId = null

        if (requestId.startsWith(NEARBY_PREFIX)) {
            nearby.approve(requestId)
        } else {
            val code = store.activeCode()
            val key = hostSessionKey
            if (code != null && key != null) {
                signaling.registerHost(code, key)
            }
            signaling.approve(requestId)
        }

        val started = runCatching {
            val control = RemoteControlAccessibilityService.instance
                ?: error("SimpleLink Control service is not ready")
            control.setRemoteControlSessionActive(true)
            webRtc.startHost(requestId, data, iceServersFor(requestId), ::sendSignal)
        }.isSuccess
        if (started) {
            _state.value = SessionUiState.Remote(Role.HOST, "Starting secure session…")
        } else {
            rejectTransportRequest(requestId)
            _events.tryEmit(SessionEvent.StopProjectionService)
            disableIdleControl()
            val code = store.activeCode()
            _state.value = if (code != null) {
                SessionUiState.Sharing(
                    code = code,
                    status = "Could not start screen sharing",
                    needsControlSetup = !ControlAvailability.isEnabled(context)
                )
            } else {
                SessionUiState.Home
            }
        }
    }

    fun projectionDenied() {
        val requestId = pendingProjectionRequestId
        pendingProjectionRequestId = null
        if (requestId != null) rejectTransportRequest(requestId)
        _events.tryEmit(SessionEvent.StopProjectionService)
        disableIdleControl()
        val code = store.activeCode()
        if (code != null) {
            _state.value = SessionUiState.Sharing(
                code = code,
                status = "Screen permission is required",
                needsControlSetup = !ControlAvailability.isEnabled(context)
            )
        } else {
            _state.value = SessionUiState.Home
        }
    }

    private fun startViewerWebRtc(requestId: String) {
        webRtc.prepareViewer(requestId, iceServersFor(requestId), ::sendSignal)
        _state.value = SessionUiState.Remote(Role.VIEWER, "Starting secure session…")
    }

    fun sendControl(command: RemoteControlCommand) = webRtc.sendControl(command)

    fun disconnect() {
        cleanup()
        _state.value = SessionUiState.Home
    }

    fun backHome() {
        cleanup()
        _state.value = SessionUiState.Home
    }

    fun requestRescueSms(code: String) {
        val normalized = SessionCode.normalize(code)
        if (!SessionCode.isValid(normalized)) return
        _events.tryEmit(SessionEvent.PickRescueContact(normalized, UUID.randomUUID().toString()))
    }

    fun openInternetHelp() {
        _events.tryEmit(SessionEvent.OpenInternetPanel)
    }

    fun onRescueSmsDispatched(code: String, requestId: String) {
        val normalized = SessionCode.normalize(code)
        val current = _state.value
        if (current !is SessionUiState.Joining || current.code != normalized) return
        _state.value = current.copy(status = "Rescue sent. Waiting for phone…", rescueAvailable = false)
        beginJoinRetryLoop(normalized, requestId)
    }

    fun onAccessibilityReturned() {
        val current = _state.value
        if (current is SessionUiState.Sharing) {
            val enabled = ControlAvailability.isEnabled(context)
            _state.value = current.copy(needsControlSetup = !enabled)
            if (enabled && resumeAllowAfterAccessibility && current.pending != null) {
                allowPending()
            }
        }
    }

    private fun waitForControlReady() {
        controlReadyJob?.cancel()
        controlReadyJob = scope.launch {
            repeat(30) {
                if (RemoteControlAccessibilityService.instance != null) {
                    controlReadyJob = null
                    allowPending()
                    return@launch
                }
                delay(100)
            }

            controlReadyJob = null
            val current = _state.value
            if (current is SessionUiState.Sharing && current.pending != null) {
                _state.value = current.copy(
                    status = "Control did not start · Try again",
                    needsControlSetup = true
                )
            }
        }
    }

    private fun disableIdleControl() {
        resumeAllowAfterAccessibility = false
        controlReadyJob?.cancel()
        controlReadyJob = null
        ControlSessionGate.clear(context)
        RemoteControlAccessibilityService.instance?.disableForPrivacy()
    }

    private fun iceServersFor(requestId: String): List<IceServerSpec> {
        return if (requestId.startsWith(NEARBY_PREFIX)) emptyList() else internetIceServers
    }

    private fun sendSignal(requestId: String, payload: JSONObject) {
        if (requestId.startsWith(NEARBY_PREFIX)) nearby.sendSignal(requestId, payload)
        else signaling.sendSignal(requestId, payload)
    }

    private fun requestNearbyFallback(mode: NearbyMode, code: String) {
        val key = "${mode.name}:$code"
        if (nearbyFallbackKey == key) return
        nearbyFallbackKey = key
        _events.tryEmit(SessionEvent.EnsureNearbyPermission(mode, code))
    }

    private fun maintainHostLease(code: String, hostKey: String) {
        hostLeaseJob?.cancel()
        hostLeaseJob = scope.launch {
            var nextLocalRefresh = System.currentTimeMillis() + HOST_LOCAL_REFRESH_MS
            while (true) {
                delay(HOST_SIGNAL_REFRESH_MS)
                val current = _state.value as? SessionUiState.Sharing ?: return@launch
                if (current.code != code) return@launch

                val now = System.currentTimeMillis()
                if (now >= nextLocalRefresh) {
                    store.saveActiveCode(code, now + CODE_TTL_MS)
                    RescueSmsRetriever.arm(context)
                    nextLocalRefresh = now + HOST_LOCAL_REFRESH_MS
                }

                if (current.pending == null && pendingProjectionRequestId == null && connectivity.hasInternet()) {
                    signaling.registerHost(code, hostKey)
                }
            }
        }
    }

    private fun beginJoinRetryLoop(code: String, requestId: String) {
        joinRetryJob?.cancel()
        joinRetryJob = scope.launch {
            repeat(RESCUE_JOIN_ATTEMPTS) {
                val current = _state.value
                if (current !is SessionUiState.Joining || current.code != code) return@launch
                if (connectivity.hasInternet()) signaling.join(code, requestId)
                delay(RESCUE_JOIN_INTERVAL_MS)
            }
            val current = _state.value
            if (current is SessionUiState.Joining && current.code == code) {
                _state.value = current.copy(status = "Could not reach the phone", rescueAvailable = true)
            }
        }
    }

    private fun beginReconnectLoop(code: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            repeat(RESCUE_RECONNECT_ATTEMPTS) {
                if (store.activeCode() != code) return@launch
                if (connectivity.hasInternet()) {
                    val key = hostSessionKey ?: return@launch
                    signaling.registerHost(code, key)
                    val current = _state.value
                    if (current is SessionUiState.Sharing) {
                        _state.value = current.copy(
                            status = "Internet restored. Reconnecting…",
                            rescueInternetPrompt = false
                        )
                    }
                    return@launch
                }
                delay(RESCUE_RECONNECT_INTERVAL_MS)
            }
            val current = _state.value
            if (current is SessionUiState.Sharing && current.code == code) {
                _state.value = current.copy(status = "Still offline. Turn on internet to continue")
            }
        }
    }

    private fun handleCodeConflict() {
        val current = _state.value as? SessionUiState.Sharing ?: return
        deniedRescueRequestId = null
        if (approvedRescueRequestId != null) {
            _state.value = SessionUiState.Error("The temporary code is no longer available")
            return
        }
        val replacement = SessionCode.generate()
        store.saveActiveCode(replacement, System.currentTimeMillis() + CODE_TTL_MS)
        val key = hostSessionKey ?: UUID.randomUUID().toString().also { hostSessionKey = it }
        _state.value = current.copy(code = replacement, status = "Waiting for connection…")
        signaling.registerHost(replacement, key)
        runCatching { nearby.startHost(replacement) }
        RescueSmsRetriever.arm(context)
        maintainHostLease(replacement, key)
    }

    private fun endRemoteSession(message: String) {
        if (_state.value !is SessionUiState.Remote) return
        cleanup()
        _state.value = SessionUiState.Error(message)
    }

    private fun failJoin(message: String) {
        cleanup()
        _state.value = SessionUiState.Error(message)
    }

    private fun cleanup() {
        ControlSessionGate.clear(context)
        RemoteControlAccessibilityService.instance?.disableForPrivacy()
        controlReadyJob?.cancel()
        joinRetryJob?.cancel()
        reconnectJob?.cancel()
        hostLeaseJob?.cancel()
        controlReadyJob = null
        joinRetryJob = null
        reconnectJob = null
        hostLeaseJob = null
        hostSessionKey = null
        nearbyFallbackKey = null
        approvedRescueRequestId = null
        deniedRescueRequestId = null
        resumeAllowAfterAccessibility = false
        _events.tryEmit(SessionEvent.StopProjectionService)
        store.clear()
        signaling.close()
        nearby.stop()
        webRtc.close()
        pendingProjectionRequestId = null
    }

    companion object {
        private const val CODE_TTL_MS = 5 * 60 * 1000L
        private const val NEARBY_PREFIX = "nearby:"
        private const val RESCUE_JOIN_INTERVAL_MS = 5_000L
        private const val RESCUE_JOIN_ATTEMPTS = 60
        private const val RESCUE_RECONNECT_INTERVAL_MS = 2_000L
        private const val RESCUE_RECONNECT_ATTEMPTS = 150
        private const val HOST_SIGNAL_REFRESH_MS = 20_000L
        private const val HOST_LOCAL_REFRESH_MS = 4 * 60 * 1000L
    }
}

enum class NearbyMode { HOST, JOIN }

sealed interface SessionEvent {
    data class RequestScreenCapture(val requestId: String) : SessionEvent
    data class EnsureNearbyPermission(val mode: NearbyMode, val code: String) : SessionEvent
    data class PickRescueContact(val code: String, val requestId: String) : SessionEvent
    data object OpenInternetPanel : SessionEvent
    data object OpenAccessibilitySettings : SessionEvent
    data object StopProjectionService : SessionEvent
    data object EnsureNotificationPermission : SessionEvent
}
