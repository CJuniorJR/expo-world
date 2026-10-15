package br.com.expoworld.location

import android.content.Context
import android.net.*
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/** Foreground-only diagnostics. No network requests, location or IP addresses. */
class ConnectivityObserver(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    val online = MutableStateFlow(readOnline())
    private var registered = false
    private fun readOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { online.value = readOnline() }
        override fun onLost(network: Network) { online.value = readOnline() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { online.value = readOnline() }
    }
    fun start() {
        online.value = readOnline()
        if (registered) return
        if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
        else manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        registered = true
    }
    fun stop() { if (registered) { manager.unregisterNetworkCallback(callback); registered = false } }
}
