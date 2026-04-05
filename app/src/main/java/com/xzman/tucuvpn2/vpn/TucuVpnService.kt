package com.xzman.tucuvpn2.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.xzman.tucuvpn2.R
import com.xzman.tucuvpn2.ui.MainActivity
import com.xzman.tucuvpn2.utils.AppLogger
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * VPN Service that establishes the TUN interface and forwards traffic
 * through an OpenVPN connection.
 *
 * This service:
 * 1. Builds the VPN TUN interface via VpnService.Builder
 * 2. Manages the VPN tunnel lifecycle
 * 3. Runs as a foreground service with a persistent notification
 */
class TucuVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "tucu_vpn_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.xzman.tucuvpn2.START_VPN"
        const val ACTION_STOP = "com.xzman.tucuvpn2.STOP_VPN"
        const val EXTRA_OVPN_CONFIG = "ovpn_config"
        const val EXTRA_SERVER_IP = "server_ip"
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

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_OVPN_CONFIG) ?: return START_NOT_STICKY
                val serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: return START_NOT_STICKY
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 1194)
                startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
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

                // Build the TUN interface
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

                vpnInterface = builder.establish()
                    ?: throw Exception("No se pudo establecer la interfaz VPN")

                isConnected = true
                updateNotification("Conectado a $serverIp")
                AppLogger.log("Interfaz TUN establecida")

                // Start the OpenVPN client tunnel
                val client = OpenVpnClient(
                    context = this@TucuVpnService,
                    vpnService = this@TucuVpnService,
                    tunFd = vpnInterface!!.fd,
                    ovpnConfig = ovpnConfig,
                    serverIp = serverIp,
                    serverPort = serverPort
                )
                client.run()

            } catch (e: CancellationException) {
                // Job was cancelled, normal flow
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // Ignore close errors
        } finally {
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Notifications ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TucuVPN Estado",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de la conexión VPN"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TucuVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TucuVPN2")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Desconectar", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
