package com.xzman.tucuvpn2.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.xzman.tucuvpn2.utils.AppLogger
import kotlinx.coroutines.*

/**
 * VPN Service that establishes the TUN interface and forwards traffic
 * through an OpenVPN connection.
 *
 * NOTE: On Android 14+ (targetSdk 34) VpnService must NOT call
 * startForeground(). The system manages the VPN notification itself
 * via the BIND_VPN_SERVICE permission. Calling startForeground() here
 * causes MissingForegroundServiceTypeException.
 */
class TucuVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.xzman.tucuvpn2.START_VPN"
        const val ACTION_STOP  = "com.xzman.tucuvpn2.STOP_VPN"
        const val EXTRA_OVPN_CONFIG = "ovpn_config"
        const val EXTRA_SERVER_IP   = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var currentServerIp: String = ""
    }

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): TucuVpnService = this@TucuVpnService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config    = intent.getStringExtra(EXTRA_OVPN_CONFIG) ?: return START_NOT_STICKY
                val serverIp  = intent.getStringExtra(EXTRA_SERVER_IP)   ?: return START_NOT_STICKY
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 1194)
                startVpn(config, serverIp, serverPort)
            }
        }
        return START_STICKY
    }

    /**
     * Establishes the VPN tunnel.
     * Sets up the TUN interface with VpnService.Builder and begins
     * the OpenVPN handshake via [OpenVpnClient].
     */
    private fun startVpn(ovpnConfig: String, serverIp: String, serverPort: Int) {
        connectionJob = serviceScope.launch {
            try {
                AppLogger.log("Iniciando servicio VPN...")
                currentServerIp = serverIp

                val builder = Builder().apply {
                    addAddress("10.8.0.2", 24)
                    addRoute("0.0.0.0", 0)
                    addDnsServer("8.8.8.8")
                    addDnsServer("8.8.4.4")
                    setSession("TucuVPN2")
                    setMtu(1500)
                    allowFamily(android.system.OsConstants.AF_INET)
                    allowFamily(android.system.OsConstants.AF_INET6)
                }

                val pfd = builder.establish()
                    ?: throw Exception("No se pudo establecer la interfaz VPN (permiso denegado)")
                vpnInterface = pfd

                isConnected = true
                AppLogger.log("Interfaz TUN establecida")

                // Pass the ParcelFileDescriptor directly — never use /proc/self/fd/$fd
                // because SELinux blocks that path on Android.
                val client = OpenVpnClient(
                    context    = this@TucuVpnService,
                    vpnService = this@TucuVpnService,
                    tunPfd     = pfd,
                    ovpnConfig = ovpnConfig,
                    serverIp   = serverIp,
                    serverPort = serverPort
                )
                client.run()

            } catch (e: CancellationException) {
                // Job cancelled — normal shutdown flow
            } catch (e: Exception) {
                AppLogger.log("Error en servicio VPN: ${e.message}")
                isConnected = false
            } finally {
                closeVpnInterface()
            }
        }
    }

    fun stopVpn() {
        AppLogger.log("Deteniendo servicio VPN...")
        connectionJob?.cancel()
        isConnected = false
        currentServerIp = ""
        closeVpnInterface()
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
