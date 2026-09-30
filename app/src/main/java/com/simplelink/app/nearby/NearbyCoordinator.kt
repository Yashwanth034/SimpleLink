package com.simplelink.app.nearby

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class NearbyCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)

    private var activeCode: String? = null
    private var targetCode: String? = null
    private var isHost = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null
    private var currentRequestId: String? = null
    private var receiverRegistered = false

    var onIncomingRequest: ((String) -> Unit)? = null
    var onApproved: ((String) -> Unit)? = null
    var onRejected: ((String) -> Unit)? = null
    var onSignal: ((String, JSONObject) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                requestConnectionInfo()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startHost(code: String) {
        stop()
        activeCode = code
        isHost = true
        registerReceiver()
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                advertise(code)
                scope.launch(Dispatchers.IO) { acceptSocket() }
                onStatus?.invoke("Ready nearby")
            }

            override fun onFailure(reason: Int) {
                advertise(code)
                scope.launch(Dispatchers.IO) { acceptSocket() }
                onStatus?.invoke("Nearby mode waiting")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startJoin(code: String) {
        stop()
        targetCode = code
        isHost = false
        registerReceiver()

        manager.setDnsSdResponseListeners(
            channel,
            { _, _, _ -> Unit },
            { _, txt, device ->
                if (txt[TXT_CODE] == code) {
                    @Suppress("DEPRECATION")
                    val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
                    manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            onStatus?.invoke("Connecting nearby…")
                        }

                        override fun onFailure(reason: Int) {
                            onStatus?.invoke("Nearby connection unavailable")
                        }
                    })
                }
            }
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request
        manager.addServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onStatus?.invoke("Looking nearby…")
                    }
                    override fun onFailure(reason: Int) {
                        onStatus?.invoke("Nearby discovery unavailable")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                onStatus?.invoke("Nearby discovery unavailable")
            }
        })
    }

    fun approve(requestId: String) {
        send(JSONObject().put("type", "approved").put("requestId", requestId))
    }

    fun reject(requestId: String) {
        send(JSONObject().put("type", "rejected").put("requestId", requestId))
    }

    fun sendSignal(requestId: String, payload: JSONObject) {
        send(
            JSONObject()
                .put("type", "signal")
                .put("requestId", requestId)
                .put("payload", payload)
        )
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { readJob?.cancel() }
        readJob = null
        runCatching { writer?.close() }
        writer = null
        runCatching { socket?.close() }
        socket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        currentRequestId = null
        serviceRequest?.let { request ->
            runCatching { manager.removeServiceRequest(channel, request, null) }
        }
        serviceRequest = null
        runCatching { manager.clearLocalServices(channel, null) }
        runCatching { manager.clearServiceRequests(channel, null) }
        if (isHost) runCatching { manager.removeGroup(channel, null) }
        unregisterReceiver()
        activeCode = null
        targetCode = null
        isHost = false
    }

    @SuppressLint("MissingPermission")
    private fun advertise(code: String) {
        val service = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE,
            SERVICE_TYPE,
            mapOf(TXT_CODE to code, TXT_PORT to PORT.toString())
        )
        manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            if (!info.groupFormed) return@requestConnectionInfo
            if (!isHost && !info.isGroupOwner) {
                connectToGroupOwner(info)
            }
        }
    }

    private fun connectToGroupOwner(info: WifiP2pInfo) {
        if (socket?.isConnected == true) return
        scope.launch(Dispatchers.IO) {
            repeat(10) {
                val connected = runCatching {
                    val newSocket = Socket(info.groupOwnerAddress, PORT)
                    attachSocket(newSocket)
                    val requestId = "nearby:${UUID.randomUUID()}"
                    currentRequestId = requestId
                    send(
                        JSONObject()
                            .put("type", "join")
                            .put("requestId", requestId)
                            .put("code", targetCode)
                    )
                    true
                }.getOrDefault(false)
                if (connected) return@launch
                kotlinx.coroutines.delay(350)
            }
        }
    }

    private fun acceptSocket() {
        runCatching {
            serverSocket = ServerSocket(PORT)
            val accepted = serverSocket?.accept() ?: return
            attachSocket(accepted)
        }
    }

    private fun attachSocket(newSocket: Socket) {
        socket = newSocket
        writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
        readJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val line = reader.readLine() ?: break
                handle(JSONObject(line))
            }
        }
    }

    @Synchronized
    private fun send(message: JSONObject) {
        runCatching {
            writer?.apply {
                write(message.toString())
                newLine()
                flush()
            }
        }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "join" -> {
                if (!isHost || message.optString("code") != activeCode) return
                val id = message.optString("requestId")
                currentRequestId = id
                onIncomingRequest?.invoke(id)
            }
            "approved" -> onApproved?.invoke(message.optString("requestId"))
            "rejected" -> onRejected?.invoke(message.optString("requestId"))
            "signal" -> {
                val id = message.optString("requestId")
                val payload = message.optJSONObject("payload") ?: return
                onSignal?.invoke(id, payload)
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    companion object {
        private const val SERVICE_INSTANCE = "SimpleLink"
        private const val SERVICE_TYPE = "_simplelink._tcp"
        private const val TXT_CODE = "code"
        private const val TXT_PORT = "port"
        private const val PORT = 8988
    }
}
