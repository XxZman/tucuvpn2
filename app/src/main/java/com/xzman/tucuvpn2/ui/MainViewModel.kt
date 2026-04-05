package com.xzman.tucuvpn2.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.xzman.tucuvpn2.utils.AppLogger
import com.xzman.tucuvpn2.vpn.VpnConnectionManager
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val vpnManager = VpnConnectionManager(application)

    val vpnState: StateFlow<VpnConnectionManager.State> = vpnManager.state
    val logText:  StateFlow<String>  = AppLogger.logFlow

    /**
     * Emits an Intent that must be launched for result by the Activity.
     * Can be an Android VPN permission dialog OR an OpenVPN for Android
     * authorization dialog — both are handled the same way.
     */
    val authIntentFlow: SharedFlow<Intent> = vpnManager.authIntentFlow

    fun prepareVpnIntent(): Intent? = vpnManager.prepareVpnIntent()

    fun connect() {
        AppLogger.clear()
        vpnManager.startConnection()
    }

    fun disconnect() = vpnManager.stopConnection()

    /** Called by the Activity when the authorization dialog returns RESULT_OK. */
    fun onAuthorizationGranted() = vpnManager.onAuthorizationGranted()

    /** Called by the Activity when the dialog is cancelled or denied. */
    fun onAuthorizationDenied()  = vpnManager.onAuthorizationDenied()

    override fun onCleared() {
        vpnManager.stopConnection()
        super.onCleared()
    }
}
