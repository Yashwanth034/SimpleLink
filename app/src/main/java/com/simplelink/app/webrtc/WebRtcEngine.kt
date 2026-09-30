package com.simplelink.app.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.simplelink.app.control.RemoteControlAccessibilityService
import com.simplelink.app.control.RemoteControlCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class WebRtcEngine(private val context: Context) {
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null
    private var activeRequestId: String? = null
    private var isHostPeer = false
    private var iceRestartInProgress = false
    private var networkRecoveryInProgress = false
    private var lastNetworkRecoveryAtMs = 0L
    private var sendSignal: ((String, JSONObject) -> Unit)? = null
    private var activeIceServers: List<IceServerSpec> = emptyList()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var isClosing = false
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var displayListenerRegistered = false
    private var lastCaptureWidth = 0
    private var lastCaptureHeight = 0
    private var lastCaptureFps = 0
    private var currentStreamQuality = StreamQuality.HIGH
    private val adaptiveQuality = AdaptiveQualityController()
    private var qualityMonitorRunning = false
    private var qualityStatsInFlight = false
    private val qualityStatsTracker = RtcQualityStatsTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disconnectTimeout = Runnable {
        if (!isClosing) onDisconnected?.invoke()
    }
    private val iceRestartTimeout = Runnable {
        iceRestartInProgress = false
    }
    private val networkRecoveryReset = Runnable {
        networkRecoveryInProgress = false
    }
    private val qualityMonitorRunnable = object : Runnable {
        override fun run() {
            if (!qualityMonitorRunning) return
            collectAdaptiveQualitySample()
            if (qualityMonitorRunning) {
                mainHandler.postDelayed(this, QUALITY_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _remoteScreenSize = MutableStateFlow(ScreenSize(1080, 1920))
    val remoteScreenSize: StateFlow<ScreenSize> = _remoteScreenSize.asStateFlow()

    private val _remoteInputState = MutableStateFlow(RemoteInputState())
    val remoteInputState: StateFlow<RemoteInputState> = _remoteInputState.asStateFlow()

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun startHost(
        requestId: String,
        projectionPermissionData: Intent,
        iceServers: List<IceServerSpec>,
        signalSender: (String, JSONObject) -> Unit
    ) {
        closePeerOnly()
        activeRequestId = requestId
        isHostPeer = true
        iceRestartInProgress = false
        networkRecoveryInProgress = false
        lastNetworkRecoveryAtMs = 0L
        sendSignal = signalSender
        activeIceServers = iceServers
        currentStreamQuality = StreamQuality.HIGH
        adaptiveQuality.reset(SystemClock.elapsedRealtime())
        qualityStatsTracker.reset()
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        peerConnection = createPeerConnection()
        val init = DataChannel.Init().apply { ordered = true }
        dataChannel = peerConnection?.createDataChannel("control", init)?.also(::observeDataChannel)
        startScreenCapture(projectionPermissionData)
        createOffer()
    }

    fun prepareViewer(
        requestId: String,
        iceServers: List<IceServerSpec>,
        signalSender: (String, JSONObject) -> Unit
    ) {
        closePeerOnly()
        activeRequestId = requestId
        isHostPeer = false
        iceRestartInProgress = false
        networkRecoveryInProgress = false
        lastNetworkRecoveryAtMs = 0L
        sendSignal = signalSender
        activeIceServers = iceServers
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        peerConnection = createPeerConnection()
    }

    fun handleSignal(requestId: String, payload: JSONObject) {
        if (activeRequestId == null) activeRequestId = requestId
        if (activeRequestId != requestId) return
        when (payload.optString("kind")) {
            "offer" -> {
                if (peerConnection == null) peerConnection = createPeerConnection()
                val sdp = payload.optString("sdp")
                setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp)) {
                    createAnswer()
                }
            }
            "answer" -> {
                val sdp = payload.optString("sdp")
                setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp)) {
                    completeIceRestart()
                }
            }
            "restart_request" -> {
                if (isHostPeer) restartIceAsHost()
            }
            "candidate" -> {
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate")
                )
                if (remoteDescriptionSet) {
                    peerConnection?.addIceCandidate(candidate)
                } else {
                    pendingRemoteCandidates += candidate
                }
            }
        }
    }

    fun handleNetworkPathChanged() {
        val requestId = activeRequestId ?: return
        if (requestId.startsWith("nearby:")) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkRecoveryAtMs < NETWORK_RECOVERY_COOLDOWN_MS) return
        lastNetworkRecoveryAtMs = now
        beginNetworkRecovery()

        if (isHostPeer) {
            restartIceAsHost()
        } else {
            sendSignal?.invoke(
                requestId,
                JSONObject().put("kind", "restart_request")
            )
        }
    }

    fun sendControl(command: RemoteControlCommand) {
        val message = JSONObject()
            .put("channel", "control")
            .put("payload", command.toJson())
            .toString()
        sendData(message)
    }

    fun close() {
        stopQualityMonitor()
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        screenCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSender = null
        videoSource?.dispose()
        videoSource = null
        mainHandler.removeCallbacks(disconnectTimeout)
        closePeerOnly()
        _remoteVideoTrack.value = null
        _remoteInputState.value = RemoteInputState()
        activeRequestId = null
        isHostPeer = false
        iceRestartInProgress = false
        networkRecoveryInProgress = false
        lastNetworkRecoveryAtMs = 0L
        mainHandler.removeCallbacks(iceRestartTimeout)
        mainHandler.removeCallbacks(networkRecoveryReset)
        sendSignal = null
        activeIceServers = emptyList()
        currentStreamQuality = StreamQuality.HIGH
        adaptiveQuality.reset()
        lastCaptureWidth = 0
        lastCaptureHeight = 0
        lastCaptureFps = 0
        if (displayListenerRegistered) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
    }

    private fun createPeerConnection(): PeerConnection? {
        val configured = activeIceServers.map { spec ->
            val builder = PeerConnection.IceServer.builder(spec.urls)
            if (spec.username.isNotBlank()) builder.setUsername(spec.username)
            if (spec.credential.isNotBlank()) builder.setPassword(spec.credential)
            builder.createIceServer()
        }
        val iceServers = when {
            configured.isNotEmpty() -> configured
            activeRequestId?.startsWith("nearby:") == true -> emptyList()
            else -> listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, observer)
    }

    private fun startScreenCapture(permissionData: Intent) {
        val metrics = currentDisplayMetrics()
        val profile = currentStreamQuality
        val (width, height) = captureDimensions(metrics, profile)

        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                onDisconnected?.invoke()
            }
        })
        val source = factory.createVideoSource(true)
        val helper = SurfaceTextureHelper.create("SimpleLinkCapture", eglBase.eglBaseContext)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(width, height, profile.fps)
        lastCaptureWidth = width
        lastCaptureHeight = height
        lastCaptureFps = profile.fps
        val track = factory.createVideoTrack("screen-video", source)
        track.setEnabled(true)
        localVideoSender = peerConnection?.addTrack(track, listOf("simplelink-screen"))

        screenCapturer = capturer
        videoSource = source
        surfaceTextureHelper = helper
        localVideoTrack = track
        applySenderProfile(profile)

        sendScreenInfo(metrics.widthPixels, metrics.heightPixels)
        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, null)
            displayListenerRegistered = true
        }
    }

    private fun captureDimensions(
        metrics: DisplayMetrics,
        profile: StreamQuality
    ): Pair<Int, Int> {
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val scale = minOf(1f, profile.longEdge / longEdge.toFloat())

        fun even(value: Int): Int {
            val safe = value.coerceAtLeast(2)
            return if (safe % 2 == 0) safe else safe - 1
        }

        return even((metrics.widthPixels * scale).toInt()) to
            even((metrics.heightPixels * scale).toInt())
    }

    private fun applyStreamQuality(profile: StreamQuality) {
        currentStreamQuality = profile
        val metrics = currentDisplayMetrics()
        val (width, height) = captureDimensions(metrics, profile)

        if (
            width != lastCaptureWidth ||
            height != lastCaptureHeight ||
            profile.fps != lastCaptureFps
        ) {
            lastCaptureWidth = width
            lastCaptureHeight = height
            lastCaptureFps = profile.fps
            runCatching {
                screenCapturer?.changeCaptureFormat(width, height, profile.fps)
            }
        }

        applySenderProfile(profile)
    }

    private fun applySenderProfile(profile: StreamQuality) {
        val sender = localVideoSender ?: return
        runCatching {
            val parameters = sender.parameters
            parameters.encodings.forEach { encoding ->
                encoding.maxBitrateBps = profile.maxBitrateBps
                encoding.maxFramerate = profile.fps
            }
            sender.setParameters(parameters)
        }
    }

    private fun startQualityMonitor() {
        if (localVideoSender == null || qualityMonitorRunning) return
        qualityMonitorRunning = true
        qualityStatsInFlight = false
        qualityStatsTracker.reset()
        mainHandler.removeCallbacks(qualityMonitorRunnable)
        mainHandler.post(qualityMonitorRunnable)
    }

    private fun stopQualityMonitor() {
        qualityMonitorRunning = false
        qualityStatsInFlight = false
        mainHandler.removeCallbacks(qualityMonitorRunnable)
        qualityStatsTracker.reset()
    }

    private fun collectAdaptiveQualitySample() {
        val peer = peerConnection ?: return
        if (localVideoSender == null || qualityStatsInFlight) return
        qualityStatsInFlight = true

        peer.getStats { report ->
            val sample = qualityStatsTracker.sample(report)
            mainHandler.post {
                qualityStatsInFlight = false
                if (!qualityMonitorRunning || peerConnection !== peer) return@post
                val next = adaptiveQuality.observe(sample, SystemClock.elapsedRealtime()) ?: return@post
                applyStreamQuality(next)
            }
        }
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun restartIceAsHost() {
        val peer = peerConnection ?: return
        if (!isHostPeer || iceRestartInProgress) return
        beginNetworkRecovery()
        if (peer.signalingState() != PeerConnection.SignalingState.STABLE) {
            mainHandler.postDelayed({ restartIceAsHost() }, 750L)
            return
        }

        iceRestartInProgress = true
        mainHandler.removeCallbacks(iceRestartTimeout)
        mainHandler.postDelayed(iceRestartTimeout, ICE_RESTART_TIMEOUT_MS)
        pendingRemoteCandidates.clear()

        runCatching {
            peer.restartIce()
            createOffer()
        }.onFailure {
            completeIceRestart()
        }
    }

    private fun completeIceRestart() {
        iceRestartInProgress = false
        mainHandler.removeCallbacks(iceRestartTimeout)
    }

    private fun beginNetworkRecovery() {
        networkRecoveryInProgress = true
        mainHandler.removeCallbacks(networkRecoveryReset)
        mainHandler.postDelayed(networkRecoveryReset, NETWORK_RECOVERY_GRACE_MS)
    }

    private fun completeNetworkRecovery() {
        networkRecoveryInProgress = false
        mainHandler.removeCallbacks(networkRecoveryReset)
        mainHandler.removeCallbacks(disconnectTimeout)
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        sendSdp("offer", desc.description)
                    }
                }, desc)
            }
        }, constraints)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        sendSdp("answer", desc.description)
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    private fun setRemoteDescription(description: SessionDescription, after: (() -> Unit)?) {
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                val peer = peerConnection
                pendingRemoteCandidates.forEach { peer?.addIceCandidate(it) }
                pendingRemoteCandidates.clear()
                after?.invoke()
            }
        }, description)
    }

    private fun sendSdp(kind: String, sdp: String) {
        val id = activeRequestId ?: return
        sendSignal?.invoke(id, JSONObject().put("kind", kind).put("sdp", sdp))
    }

    private fun observeDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    val metrics = currentDisplayMetrics()
                    sendScreenInfo(metrics.widthPixels, metrics.heightPixels)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val message = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
                when (message.optString("channel")) {
                    "control" -> message.optJSONObject("payload")?.let { payload ->
                        RemoteControlAccessibilityService.instance?.execute(payload) { editable, text ->
                            sendInputState(editable, text)
                        }
                    }
                    "screen" -> {
                        val payload = message.optJSONObject("payload") ?: return
                        _remoteScreenSize.value = ScreenSize(
                            payload.optInt("width", 1080),
                            payload.optInt("height", 1920)
                        )
                    }
                    "input" -> {
                        val payload = message.optJSONObject("payload") ?: return
                        _remoteInputState.value = RemoteInputState(
                            editable = payload.optBoolean("editable", false),
                            text = payload.optString("text").take(4_000)
                        )
                    }
                }
            }
        })
    }

    private fun sendScreenInfo(width: Int, height: Int) {
        val message = JSONObject()
            .put("channel", "screen")
            .put("payload", JSONObject().put("width", width).put("height", height))
            .toString()
        sendData(message)
    }

    private fun sendInputState(editable: Boolean, text: String) {
        val message = JSONObject()
            .put("channel", "input")
            .put(
                "payload",
                JSONObject()
                    .put("editable", editable)
                    .put("text", text.take(4_000))
            )
            .toString()
        sendData(message)
    }

    private fun sendData(value: String) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8)), false))
    }

    private fun closePeerOnly() {
        stopQualityMonitor()
        completeIceRestart()
        isClosing = true
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        localVideoSender = null
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
        isClosing = false
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val metrics = currentDisplayMetrics()
            val profile = currentStreamQuality
            val (width, height) = captureDimensions(metrics, profile)
            if (
                width != lastCaptureWidth ||
                height != lastCaptureHeight ||
                profile.fps != lastCaptureFps
            ) {
                lastCaptureWidth = width
                lastCaptureHeight = height
                lastCaptureFps = profile.fps
                runCatching {
                    screenCapturer?.changeCaptureFormat(width, height, profile.fps)
                }
            }
            sendScreenInfo(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (isClosing) return
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> completeNetworkRecovery()
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            val id = activeRequestId ?: return
            sendSignal?.invoke(
                id,
                JSONObject()
                    .put("kind", "candidate")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
            )
        }

        override fun onDataChannel(channel: DataChannel) {
            observeDataChannel(channel)
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) _remoteVideoTrack.value = track
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track: MediaStreamTrack? = transceiver.receiver.track()
            if (track is VideoTrack) _remoteVideoTrack.value = track
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (isClosing) return
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    completeNetworkRecovery()
                    applySenderProfile(currentStreamQuality)
                    startQualityMonitor()
                    onConnected?.invoke()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    stopQualityMonitor()
                    mainHandler.removeCallbacks(disconnectTimeout)
                    val timeoutMs = if (networkRecoveryInProgress) {
                        NETWORK_RECOVERY_GRACE_MS
                    } else {
                        NORMAL_DISCONNECT_GRACE_MS
                    }
                    mainHandler.postDelayed(disconnectTimeout, timeoutMs)
                }
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    networkRecoveryInProgress = false
                    mainHandler.removeCallbacks(networkRecoveryReset)
                    stopQualityMonitor()
                    mainHandler.removeCallbacks(disconnectTimeout)
                    onDisconnected?.invoke()
                }
                else -> Unit
            }
        }
    }

    companion object {
        private const val QUALITY_SAMPLE_INTERVAL_MS = 3_000L
        private const val NETWORK_RECOVERY_COOLDOWN_MS = 3_000L
        private const val NORMAL_DISCONNECT_GRACE_MS = 10_000L
        private const val NETWORK_RECOVERY_GRACE_MS = 30_000L
        private const val ICE_RESTART_TIMEOUT_MS = 12_000L
    }
}

data class ScreenSize(val width: Int, val height: Int)

data class RemoteInputState(
    val editable: Boolean = false,
    val text: String = ""
)

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
