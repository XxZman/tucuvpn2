package com.xzman.tucuvpn2.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.xzman.tucuvpn2.data.VpnServer
import com.xzman.tucuvpn2.data.ServerRepository
import com.xzman.tucuvpn2.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orchestrates the VPN connection lifecycle:
 * 1. Downloads and filters servers
 * 2. Tests each server sequentially
 * 3. Connects via [TucuVpnService] on success
 * 4. Stays connected until user calls stopConnection()
 */
class VpnConnectionManager(private val context: Context) {

    enum class State {
        IDLE,
        FETCHING,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val repository = ServerRepository()
    private var managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    /** Connection timeout per server attempt in ms */
    private val connectionTimeoutMs = 10_000L

    /**
     * Checks if the system requires VPN permission and returns the prepare Intent.
     * Returns null if permission is already granted.
     */
    fun prepareVpnIntent(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Starts the full VPN connection flow:
     * fetch → filter → iterate servers → connect → stay connected
     */
    fun startConnection() {
        connectionJob?.cancel()
        connectionJob = managerScope.launch {
            try {
                _state.value = State.FETCHING

                val serversResult = repository.fetchServers()
                if (serversResult.isFailure) {
                    AppLogger.log("No se pudo obtener la lista de servidores")
                    _state.value = State.ERROR
                    return@launch
                }

                val servers = serversResult.getOrThrow()
                if (servers.isEmpty()) {
                    AppLogger.log("No se encontraron servidores disponibles")
                    _state.value = State.ERROR
                    return@launch
                }

                AppLogger.log("Probando ${servers.size} servidores...")
                _state.value = State.CONNECTING

                var connected = false
                for ((index, server) in servers.withIndex()) {
                    if (!isActive) break

                    AppLogger.log("Probando servidor ${server.displayName} (${index + 1}/${servers.size})...")

                    val config = server.decodedOvpnConfig()
                    if (config.isBlank()) {
                        AppLogger.log("Configuración inválida para ${server.displayName}, omitiendo...")
                        continue
                    }

                    val (serverIp, serverPort) = extractRemote(config, server.ip)

                    val reachable = withTimeoutOrNull(connectionTimeoutMs) {
                        testTcpReachability(serverIp, serverPort)
                    } ?: false

                    if (!reachable) {
                        AppLogger.log("Error al conectar ${server.displayName}, probando siguiente...")
                        continue
                    }

                    // Server is reachable — start VPN service
                    startVpnService(config, serverIp, serverPort)

                    // Wait for service to confirm connection
                    val confirmed = withTimeoutOrNull(connectionTimeoutMs) {
                        waitForConnection()
                    } ?: false

                    if (confirmed) {
                        AppLogger.log("Conectado correctamente a ${server.displayName} (${server.countryLong})")
                        _state.value = State.CONNECTED
                        connected = true
                        // Connection stays alive until the user calls stopConnection()
                        break
                    } else {
                        AppLogger.log("Error al conectar ${server.displayName}, probando siguiente...")
                        stopVpnService()
                    }
                }

                if (!connected) {
                    AppLogger.log("No se pudo conectar a ningún servidor")
                    _state.value = State.ERROR
                }

            } catch (e: CancellationException) {
                AppLogger.log("Proceso cancelado")
                stopVpnService()
                _state.value = State.DISCONNECTED
            } catch (e: Exception) {
                AppLogger.log("Error inesperado: ${e.message}")
                _state.value = State.ERROR
            }
        }
    }

    /**
     * Cancels the connection process and stops the VPN service.
     */
    fun stopConnection() {
        connectionJob?.cancel()
        stopVpnService()
        _state.value = State.IDLE
        AppLogger.log("Desconectado")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Tests TCP reachability to a server within the given timeout.
     * Used as a pre-check before spending time on a full VPN handshake.
     */
    private suspend fun testTcpReachability(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Polls [TucuVpnService.isConnected] until connected or until
     * the caller's timeout expires.
     */
    private suspend fun waitForConnection(): Boolean {
        val deadline = System.currentTimeMillis() + connectionTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (TucuVpnService.isConnected) return true
            delay(300)
        }
        return TucuVpnService.isConnected
    }

    private fun startVpnService(config: String, serverIp: String, serverPort: Int) {
        val intent = Intent(context, TucuVpnService::class.java).apply {
            action = TucuVpnService.ACTION_START
            putExtra(TucuVpnService.EXTRA_OVPN_CONFIG, config)
            putExtra(TucuVpnService.EXTRA_SERVER_IP, serverIp)
            putExtra(TucuVpnService.EXTRA_SERVER_PORT, serverPort)
        }
        context.startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(context, TucuVpnService::class.java).apply {
            action = TucuVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Extracts (ip, port) from the ovpn config's `remote` directive,
     * falling back to the server's known IP.
     */
    private fun extractRemote(ovpnConfig: String, fallbackIp: String): Pair<String, Int> {
        val remoteRegex = Regex("^\\s*remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE)
        val match = remoteRegex.find(ovpnConfig)
        return if (match != null) {
            Pair(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1194)
        } else {
            Pair(fallbackIp, 1194)
        }
    }
}
