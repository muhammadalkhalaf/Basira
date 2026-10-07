package com.basira.app.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.repository.ConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConnectivityObserver] based on [ConnectivityManager.registerDefaultNetworkCallback].
 *
 * A network counts as online only when it has internet capability and was validated by the system,
 * so captive portals report offline. Registration lives as long as the process.
 */
@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @param:ApplicationContext context: Context,
) : ConnectivityObserver {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<ConnectivityStatus> = _status.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _status.value = statusOf(networkCapabilities)
            }

            override fun onLost(network: Network) {
                _status.value = ConnectivityStatus.OFFLINE
            }

            override fun onUnavailable() {
                _status.value = ConnectivityStatus.OFFLINE
            }
        })
    }

    private fun initialStatus(): ConnectivityStatus {
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            ?: return ConnectivityStatus.OFFLINE
        return statusOf(capabilities)
    }

    private fun statusOf(capabilities: NetworkCapabilities): ConnectivityStatus =
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            ConnectivityStatus.ONLINE
        } else {
            ConnectivityStatus.OFFLINE
        }
}
