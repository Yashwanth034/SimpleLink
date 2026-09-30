package com.simplelink.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

data class NetworkPath(
    val id: String,
    val transport: NetworkTransport
)

class ConnectivityMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: ((NetworkPath?, NetworkPath?) -> Unit)? = null
    private var lastPath: NetworkPath? = null

    fun hasInternet(): Boolean = currentPath() != null

    fun currentPath(): NetworkPath? {
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return null
        }

        return NetworkPath(
            id = network.toString(),
            transport = transportFor(caps)
        )
    }

    @Synchronized
    fun startWatching(onChanged: (NetworkPath?, NetworkPath?) -> Unit) {
        listener = onChanged
        if (callback != null) return

        lastPath = currentPath()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = dispatchIfChanged()
            override fun onLost(network: Network) = dispatchIfChanged()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = dispatchIfChanged()
        }

        callback = networkCallback
        connectivity.registerDefaultNetworkCallback(networkCallback, mainHandler)
    }

    @Synchronized
    fun stopWatching() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        listener = null
        lastPath = null
    }

    private fun dispatchIfChanged() {
        val current = currentPath()
        val previous: NetworkPath?
        val target: ((NetworkPath?, NetworkPath?) -> Unit)?

        synchronized(this) {
            if (current == lastPath) return
            previous = lastPath
            lastPath = current
            target = listener
        }

        target?.invoke(previous, current)
    }

    private fun transportFor(caps: NetworkCapabilities): NetworkTransport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        else -> NetworkTransport.OTHER
    }
}
