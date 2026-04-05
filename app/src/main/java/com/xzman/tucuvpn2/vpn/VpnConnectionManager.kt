package com.xzman.tucuvpn2.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.xzman.tucuvpn2.data.ServerRepository
import com.xzman.tucuvpn2.utils.AppLogger
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orchestrates the full VPN connection lifecycle:
 *
 *  1. Verifies OpenVPN for Android is installed
 *  2. Binds to its AIDL service (IOpenVPNAPIService)
 *  3. Downloads and filters VPNGate servers
 *  4. Tests TCP reachability for each server
 *  5. Passes the decoded .ovpn config to ics-openvpn via startVPN()
 *  6. Monitors real-time status via IOpenVPNStatusCallback
 *  7. Stays connected until the user calls stopConnection()
 *
 * All OpenVPN protocol work (TLS, certificates, encryption, key exchange)
 * is handled by the ics-openvpn library — no manual socket/handshake code.
 */
class VpnConnectionManager(private val context: Context) {

    // ─── State ────────────────────────────────────────────────────────────────

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

    // ─── Internals ────────────────────────────────────────────────────────────

    private val repository   = ServerRepository()
    private val ovpnCtrl     = OpenVpnController(context)
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    /** TCP reachability check timeout per server */
    private val reachabilityTimeoutMs = 10_000L

    /** How long to wait for ics-openvpn to reach CONNECTED after startVPN() */
    private val connectConfirmTimeoutMs = 15_000L

    // ─── Status callback ──────────────────────────────────────────────────────

    /**
     * Receives real-time state updates from ics-openvpn.
     * Maps OpenVPN states to our internal State enum and logs them.
     */
    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            when (state) {
                OpenVpnController.STATE_CONNECTING    -> {
                    AppLogger.log("Handshake TLS en progreso...")
                    _state.value = State.CONNECTING
                }
                OpenVpnController.STATE_AUTH          ->
                    AppLogger.log("Autenticando con el servidor...")
                OpenVpnController.STATE_WAIT          ->
                    AppLogger.log("Esperando respuesta del servidor...")
                OpenVpnController.STATE_RECONNECTING  ->
                    AppLogger.log("Reconectando...")
                OpenVpnController.STATE_CONNECTED     -> {
                    AppLogger.log("VPN conectada — tráfico redirigido")
                    _state.value = State.CONNECTED
                }
                OpenVpnController.STATE_EXITING,
                OpenVpnController.STATE_DISCONNECTED  -> {
                    if (_state.value == State.CONNECTED) {
                        AppLogger.log("Desconectado")
                        _state.value = State.DISCONNECTED
                    }
                }
                OpenVpnController.STATE_NONETWORK     ->
                    AppLogger.log("Sin red — esperando conexión...")
                else -> if (!message.isNullOrBlank())
                    AppLogger.log("OpenVPN: $message")
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a VPN permission Intent if the user hasn't granted access yet.
     * Launch this Intent for result before calling startConnection().
     * Returns null if permission is already granted.
     */
    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    /**
     * Starts the full connection flow.
     * Safe to call multiple times — cancels any running attempt first.
     */
    fun startConnection() {
        connectionJob?.cancel()
        connectionJob = managerScope.launch {
            try {
                // 1 — Check OpenVPN for Android is installed
                if (!ovpnCtrl.isOpenVpnInstalled()) {
                    AppLogger.log(
                        "ERROR: 'OpenVPN for Android' no está instalado.\n" +
                        "Instálalo desde Google Play o Amazon App Store\n" +
                        "(paquete: de.blinkt.openvpn) y vuelve a intentarlo."
                    )
                    _state.value = State.ERROR
                    return@launch
                }

                // 2 — Bind to ics-openvpn service
                AppLogger.log("Vinculando con servicio OpenVPN...")
                val bound = ovpnCtrl.bind()
                if (!bound) {
                    AppLogger.log("No se pudo conectar al servicio OpenVPN")
                    _state.value = State.ERROR
                    return@launch
                }
                ovpnCtrl.registerStatusCallback(statusCallback)

                // 3 — Fetch and filter servers
                _state.value = State.FETCHING
                val result = repository.fetchServers()
                if (result.isFailure) {
                    AppLogger.log("No se pudo obtener la lista de servidores")
                    _state.value = State.ERROR
                    return@launch
                }

                val servers = result.getOrThrow()
                if (servers.isEmpty()) {
                    AppLogger.log("No se encontraron servidores disponibles")
                    _state.value = State.ERROR
                    return@launch
                }

                AppLogger.log("Probando ${servers.size} servidores...")
                _state.value = State.CONNECTING

                // 4 — Try each server in order
                var connected = false
                for ((index, server) in servers.withIndex()) {
                    if (!isActive) break

                    AppLogger.log(
                        "Probando servidor ${server.displayName} " +
                        "(${index + 1}/${servers.size}) — ${server.countryLong}..."
                    )

                    val ovpnConfig = server.decodedOvpnConfig()
                    if (ovpnConfig.isBlank()) {
                        AppLogger.log("Config inválida para ${server.displayName}, omitiendo...")
                        continue
                    }

                    val (serverIp, serverPort) = extractRemote(ovpnConfig, server.ip)

                    // Quick TCP reachability check before handing off to OpenVPN
                    val reachable = withTimeoutOrNull(reachabilityTimeoutMs) {
                        testTcpReachability(serverIp, serverPort)
                    } ?: false

                    if (!reachable) {
                        AppLogger.log("Error al conectar ${server.displayName}, probando siguiente...")
                        continue
                    }

                    // 5 — Pass config to ics-openvpn (handles TLS/certs/encryption)
                    AppLogger.log("Iniciando OpenVPN para ${server.displayName}...")
                    ovpnCtrl.startVpn(ovpnConfig)

                    // 6 — Wait for CONNECTED state via status callback
                    val confirmed = withTimeoutOrNull(connectConfirmTimeoutMs) {
                        waitForState(State.CONNECTED)
                    } ?: false

                    if (confirmed) {
                        AppLogger.log(
                            "Conectado correctamente a ${server.displayName} (${server.countryLong})"
                        )
                        connected = true
                        // Connection stays alive — exit loop, keep scope alive
                        break
                    } else {
                        AppLogger.log(
                            "Error al conectar ${server.displayName}, probando siguiente..."
                        )
                        ovpnCtrl.disconnect()
                        delay(500) // brief pause before trying the next server
                    }
                }

                if (!connected) {
                    AppLogger.log("No se pudo conectar a ningún servidor disponible")
                    _state.value = State.ERROR
                    ovpnCtrl.unregisterStatusCallback(statusCallback)
                    ovpnCtrl.unbind()
                }

                // If connected: coroutine ends here but the VPN keeps running inside
                // ics-openvpn. stopConnection() handles teardown.

            } catch (e: CancellationException) {
                // Normal cancellation — stopConnection() handles cleanup
            } catch (e: Exception) {
                AppLogger.log("Error inesperado: ${e.message}")
                _state.value = State.ERROR
                ovpnCtrl.unregisterStatusCallback(statusCallback)
                ovpnCtrl.unbind()
            }
        }
    }

    /**
     * Disconnects the active VPN and resets state.
     * Called when the user presses "Desconectar".
     */
    fun stopConnection() {
        connectionJob?.cancel()
        ovpnCtrl.unregisterStatusCallback(statusCallback)
        ovpnCtrl.disconnect()
        ovpnCtrl.unbind()
        _state.value = State.IDLE
        AppLogger.log("Desconectado")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Polls the state flow until it reaches [target] or the caller's
     * timeout (via withTimeoutOrNull) expires.
     */
    private suspend fun waitForState(target: State): Boolean {
        while (true) {
            if (_state.value == target) return true
            if (_state.value == State.ERROR || _state.value == State.DISCONNECTED) return false
            delay(200)
        }
    }

    /**
     * Opens a TCP socket to [ip]:[port] to verify the server is reachable
     * before spending the full OpenVPN handshake budget on it.
     */
    private suspend fun testTcpReachability(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 5_000) }
                true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Extracts the (host, port) from the `remote` directive in the .ovpn
     * config, falling back to the server's known IP on port 1194.
     */
    private fun extractRemote(ovpnConfig: String, fallbackIp: String): Pair<String, Int> {
        val match = Regex(
            "^\\s*remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE
        ).find(ovpnConfig)
        return if (match != null) {
            Pair(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1194)
        } else {
            Pair(fallbackIp, 1194)
        }
    }
}
