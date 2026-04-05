package com.xzman.tucuvpn2.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.xzman.tucuvpn2.utils.AppLogger
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Controls the OpenVPN connection via the ics-openvpn external service API.
 *
 * Architecture:
 *   Our app  ──IPC/AIDL──▶  OpenVPN for Android (de.blinkt.openvpn)
 *                            └── VpnService (TLS, certs, encryption)
 *
 * This is the official, documented way to integrate ics-openvpn from a
 * third-party app. It requires "OpenVPN for Android" to be installed
 * (available on Google Play and Amazon App Store).
 *
 * Relevant ics-openvpn doc:
 *   https://github.com/schwabe/ics-openvpn/blob/master/doc/README.txt
 */
class OpenVpnController(private val context: Context) {

    companion object {
        /** Package name of OpenVPN for Android */
        const val OPENVPN_PACKAGE = "de.blinkt.openvpn"

        /** AIDL service component exposed by ics-openvpn */
        private const val OPENVPN_SERVICE = "de.blinkt.openvpn.api.ExternalOpenVPNService"

        // Status strings emitted by ics-openvpn via IOpenVPNStatusCallback
        const val STATE_CONNECTED    = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_CONNECTING   = "CONNECTING"
        const val STATE_RECONNECTING = "RECONNECTING"
        const val STATE_AUTH         = "AUTH"
        const val STATE_WAIT         = "WAIT"
        const val STATE_EXITING      = "EXITING"
        const val STATE_NONETWORK    = "NONETWORK"
    }

    private var vpnService: IOpenVPNAPIService? = null
    private var serviceConnection: ServiceConnection? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Returns true if OpenVPN for Android is installed on this device.
     */
    fun isOpenVpnInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Binds to the OpenVPN for Android service asynchronously.
     * Returns true on success, false if the app is not installed or binding fails.
     */
    suspend fun bind(): Boolean = suspendCancellableCoroutine { cont ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                vpnService = IOpenVPNAPIService.Stub.asInterface(binder)
                AppLogger.log("Servicio OpenVPN vinculado correctamente")
                if (cont.isActive) cont.resume(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                vpnService = null
                AppLogger.log("Servicio OpenVPN desvinculado")
            }
        }
        serviceConnection = connection

        val intent = Intent().apply {
            component = ComponentName(OPENVPN_PACKAGE, OPENVPN_SERVICE)
        }

        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLogger.log("Error al vincular servicio OpenVPN: ${e.message}")
            false
        }

        if (!bound && cont.isActive) cont.resume(false)

        cont.invokeOnCancellation { unbind() }
    }

    /**
     * Unbinds from the service and releases resources.
     */
    fun unbind() {
        serviceConnection?.let {
            try { context.unbindService(it) } catch (_: Exception) {}
        }
        serviceConnection = null
        vpnService = null
    }

    // ─── VPN Control ─────────────────────────────────────────────────────────

    /**
     * Starts a VPN connection using an inline .ovpn config string.
     * The config must be a fully decoded (plain text) OpenVPN config.
     *
     * ics-openvpn will handle TLS, certificate validation, key exchange,
     * and all encryption — no manual socket work needed.
     */
    fun startVpn(ovpnConfig: String) {
        vpnService?.startVPN(ovpnConfig)
    }

    /**
     * Disconnects the active VPN session.
     */
    fun disconnect() {
        vpnService?.disconnect()
    }

    /**
     * Returns an Intent that, if non-null, must be launched to grant VPN
     * permission before connecting. Null means permission is already granted.
     */
    fun prepareIntent(): Intent? = vpnService?.prepareVPNService()

    /**
     * Registers a callback to receive real-time VPN state changes.
     * States: CONNECTED, DISCONNECTED, CONNECTING, AUTH, WAIT, RECONNECTING…
     */
    fun registerStatusCallback(callback: IOpenVPNStatusCallback) {
        vpnService?.registerStatusCallback(callback)
    }

    /**
     * Unregisters a previously registered status callback.
     */
    fun unregisterStatusCallback(callback: IOpenVPNStatusCallback) {
        vpnService?.unregisterStatusCallback(callback)
    }

    /**
     * Returns the current status string from the OpenVPN service,
     * or null if not bound.
     */
    fun currentStatus(): String? = vpnService?.getStatus()
}
