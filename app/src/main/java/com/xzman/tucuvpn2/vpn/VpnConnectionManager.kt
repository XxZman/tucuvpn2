package com.xzman.tucuvpn2.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.xzman.tucuvpn2.data.ServerRepository
import com.xzman.tucuvpn2.utils.AppLogger
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orchestrates the full VPN connection lifecycle:
 *
 *  1. Verifies OpenVPN for Android is installed
 *  2. Binds to its AIDL service
 *  3. Requests user authorization via prepareVPNService() if needed
 *  4. Downloads and filters VPNGate servers
 *  5. Tests TCP reachability per server
 *  6. Calls startVPN() with the decoded .ovpn config
 *  7. Monitors real-time status via IOpenVPNStatusCallback
 *  8. Stays connected until the user calls stopConnection()
 */
class VpnConnectionManager(private val context: Context) {

    // ─── State ────────────────────────────────────────────────────────────────

    enum class State { IDLE, FETCHING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Emits an Intent that the Activity must launch for result.
     * Used for both Android VPN permission and OpenVPN authorization.
     * replay=1 ensures the Activity catches the event even if it
     * subscribes slightly after the emit.
     */
    private val _authIntentFlow = MutableSharedFlow<Intent>(replay = 1)
    val authIntentFlow: SharedFlow<Intent> = _authIntentFlow.asSharedFlow()

    /** Suspended until the user grants or denies the authorization dialog. */
    private var authDeferred: CompletableDeferred<Boolean>? = null

    // ─── Internals ────────────────────────────────────────────────────────────

    private val repository   = ServerRepository()
    private val ovpnCtrl     = OpenVpnController(context)
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    private val reachabilityTimeoutMs   = 10_000L
    private val connectConfirmTimeoutMs = 20_000L

    // ─── Authorization callbacks (called by the Activity) ────────────────────

    /** Call this when the user approved the authorization dialog (RESULT_OK). */
    fun onAuthorizationGranted() { authDeferred?.complete(true) }

    /** Call this when the user dismissed or denied the dialog. */
    fun onAuthorizationDenied()  { authDeferred?.complete(false) }

    // ─── OpenVPN status callback ──────────────────────────────────────────────

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            when (state) {
                OpenVpnController.STATE_CONNECTING    -> {
                    AppLogger.log("Handshake TLS en progreso...")
                    _state.value = State.CONNECTING
                }
                OpenVpnController.STATE_AUTH          -> AppLogger.log("Autenticando con el servidor...")
                OpenVpnController.STATE_WAIT          -> AppLogger.log("Esperando respuesta del servidor...")
                OpenVpnController.STATE_RECONNECTING  -> AppLogger.log("Reconectando...")
                OpenVpnController.STATE_CONNECTED     -> {
                    AppLogger.log("VPN conectada — tráfico redirigido correctamente")
                    _state.value = State.CONNECTED
                }
                OpenVpnController.STATE_EXITING,
                OpenVpnController.STATE_DISCONNECTED  -> {
                    if (_state.value == State.CONNECTED) {
                        AppLogger.log("Desconectado")
                        _state.value = State.DISCONNECTED
                    }
                }
                OpenVpnController.STATE_NONETWORK     -> AppLogger.log("Sin red — esperando conexión...")
                else -> if (!message.isNullOrBlank()) AppLogger.log("OpenVPN: $message")
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Returns Android-level VPN permission intent, or null if already granted. */
    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    /** Starts the full connection flow. Cancels any previous attempt first. */
    fun startConnection() {
        connectionJob?.cancel()
        connectionJob = managerScope.launch {
            try {
                // ── 1. Bind to ics-openvpn service ───────────────────────
                // bindService() is the authoritative check — do NOT use
                // PackageManager.getPackageInfo() which fails on Android 11+
                // due to package-visibility restrictions even with <queries>.
                AppLogger.log("Vinculando con servicio OpenVPN...")
                if (!ovpnCtrl.bind()) {
                    AppLogger.log(
                        "No se pudo conectar al servicio de OpenVPN for Android.\n" +
                        "Asegúrate de que esté instalado (paquete: de.blinkt.openvpn)."
                    )
                    _state.value = State.ERROR
                    return@launch
                }

                // ── 3. Authorization ──────────────────────────────────────
                if (!ensureAuthorized()) {
                    _state.value = State.ERROR
                    ovpnCtrl.unbind()
                    return@launch
                }

                // Now authorized — safe to use all AIDL methods
                ovpnCtrl.registerStatusCallback(statusCallback)

                // ── 4. Fetch and filter servers ───────────────────────────
                _state.value = State.FETCHING
                val result = repository.fetchServers()
                if (result.isFailure) {
                    AppLogger.log("No se pudo obtener la lista de servidores")
                    _state.value = State.ERROR
                    cleanup()
                    return@launch
                }

                val servers = result.getOrThrow()
                if (servers.isEmpty()) {
                    AppLogger.log("No se encontraron servidores disponibles")
                    _state.value = State.ERROR
                    cleanup()
                    return@launch
                }

                AppLogger.log("Probando ${servers.size} servidores...")
                _state.value = State.CONNECTING

                // ── 5. Try each server ────────────────────────────────────
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

                    val reachable = withTimeoutOrNull(reachabilityTimeoutMs) {
                        testTcpReachability(serverIp, serverPort)
                    } ?: false

                    if (!reachable) {
                        AppLogger.log("Error al conectar ${server.displayName}, probando siguiente...")
                        continue
                    }

                    // ── 6. Start real OpenVPN (TLS/certs handled by ics-openvpn)
                    AppLogger.log("Iniciando OpenVPN para ${server.displayName}...")
                    ovpnCtrl.startVpn(ovpnConfig)

                    // ── 7. Wait for CONNECTED confirmation via status callback
                    val confirmed = withTimeoutOrNull(connectConfirmTimeoutMs) {
                        waitForState(State.CONNECTED)
                    } ?: false

                    if (confirmed) {
                        AppLogger.log(
                            "Conectado correctamente a ${server.displayName} (${server.countryLong})"
                        )
                        connected = true
                        break   // stay connected — coroutine exits, VPN keeps running
                    } else {
                        AppLogger.log(
                            "Error al conectar ${server.displayName}, probando siguiente..."
                        )
                        ovpnCtrl.disconnect()
                        delay(800)
                    }
                }

                if (!connected) {
                    AppLogger.log("No se pudo conectar a ningún servidor disponible")
                    _state.value = State.ERROR
                    cleanup()
                }

            } catch (e: CancellationException) {
                // Normal cancellation via stopConnection()
            } catch (e: Exception) {
                AppLogger.log("Error inesperado: ${e.message}")
                _state.value = State.ERROR
                cleanup()
            }
        }
    }

    /** Disconnects and resets. Called when user presses "Desconectar". */
    fun stopConnection() {
        connectionJob?.cancel()
        authDeferred?.complete(false)  // unblock any pending auth wait
        cleanup()
        _state.value = State.IDLE
        AppLogger.log("Desconectado")
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /**
     * Ensures this app is authorized to call ics-openvpn AIDL methods.
     *
     * Newer versions of ics-openvpn throw SecurityException("Unauthorized
     * OpenVPN API Caller") from ALL methods — including prepareVPNService() —
     * until the user has approved the app inside OpenVPN for Android.
     *
     * Strategy:
     *   1. Try prepareVPNService():
     *      • Returns null        → already authorized AND VPN permission granted
     *      • Returns an Intent   → launch it (VPN permission OR auth dialog)
     *      • Throws SecurityException → not authorized at all; launch ConfirmDialog
     *                                   directly (bypasses AIDL entirely)
     *   2. After ConfirmDialog approval, call prepareVPNService() once more to
     *      handle the Android-level VPN permission if it still needs to be granted.
     *
     * Returns true if the app ends up fully authorized, false if the user
     * denied any dialog.
     */
    private suspend fun ensureAuthorized(): Boolean {
        // First attempt — may throw if not authorized yet
        val firstIntent: Intent? = try {
            ovpnCtrl.prepareIntent()
        } catch (e: SecurityException) {
            // Not authorized: launch ConfirmDialog directly (no AIDL needed)
            AppLogger.log("Autorizando TucuVPN2 en OpenVPN for Android...")
            Intent().apply {
                component = android.content.ComponentName(
                    OpenVpnController.OPENVPN_PACKAGE,
                    "${OpenVpnController.OPENVPN_PACKAGE}.api.ConfirmDialog"
                )
            }
        }

        if (firstIntent != null) {
            if (!launchAndWait(firstIntent)) {
                AppLogger.log("Autorización denegada o cancelada")
                return false
            }
            AppLogger.log("Autorización concedida")

            // After ConfirmDialog, still might need Android VPN permission
            val vpnIntent: Intent? = try {
                ovpnCtrl.prepareIntent()
            } catch (_: SecurityException) {
                AppLogger.log("Error de autorización inesperado")
                return false
            }
            if (vpnIntent != null && !launchAndWait(vpnIntent)) {
                AppLogger.log("Permiso VPN de Android denegado")
                return false
            }
        }

        return true
    }

    /** Emits [intent] to the Activity, suspends until the user responds. */
    private suspend fun launchAndWait(intent: Intent): Boolean {
        authDeferred = CompletableDeferred()
        _authIntentFlow.emit(intent)
        val result = authDeferred!!.await()
        authDeferred = null
        return result
    }

    private fun cleanup() {
        // Wrap every AIDL call — if cleanup() is reached via an error path
        // where the app isn't authorized yet, these calls would throw and
        // crash the process (that's exactly what caused the FATAL EXCEPTION).
        try { ovpnCtrl.unregisterStatusCallback(statusCallback) } catch (_: Exception) {}
        try { ovpnCtrl.disconnect() }                            catch (_: Exception) {}
        ovpnCtrl.unbind()   // local ServiceConnection — always safe
    }

    private suspend fun waitForState(target: State): Boolean {
        while (true) {
            when (_state.value) {
                target               -> return true
                State.ERROR,
                State.DISCONNECTED   -> return false
                else                 -> delay(200)
            }
        }
    }

    private suspend fun testTcpReachability(ip: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 5_000) }
                true
            } catch (_: Exception) { false }
        }

    private fun extractRemote(ovpnConfig: String, fallbackIp: String): Pair<String, Int> {
        val match = Regex(
            "^\\s*remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE
        ).find(ovpnConfig)
        return if (match != null)
            Pair(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1194)
        else
            Pair(fallbackIp, 1194)
    }
}
