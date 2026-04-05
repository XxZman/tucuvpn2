package com.xzman.tucuvpn2.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xzman.tucuvpn2.utils.AppLogger
import com.xzman.tucuvpn2.vpn.VpnConnectionManager
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for [MainActivity].
 * Holds the [VpnConnectionManager] across configuration changes and
 * exposes the connection state and log output as StateFlows.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val vpnManager = VpnConnectionManager(application)

    /** Current VPN state */
    val vpnState: StateFlow<VpnConnectionManager.State> = vpnManager.state

    /** Formatted log text */
    val logText: StateFlow<String> = AppLogger.logFlow

    /** Returns a VPN permission intent if needed, null if already granted */
    fun prepareVpnIntent(): Intent? = vpnManager.prepareVpnIntent()

    /** Starts the VPN connection flow */
    fun connect() {
        AppLogger.clear()
        vpnManager.startConnection()
    }

    /** Stops the VPN and resets state */
    fun disconnect() {
        vpnManager.stopConnection()
    }

    override fun onCleared() {
        vpnManager.stopConnection()
        super.onCleared()
    }
}
