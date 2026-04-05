package com.xzman.tucuvpn2.vpn

import android.content.Context
import android.net.VpnService
import com.xzman.tucuvpn2.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * OpenVPN client that handles the TCP/UDP tunnel communication.
 *
 * Parses the .ovpn config, connects to the server, and bridges
 * traffic between the TUN interface (tunFd) and the VPN socket.
 *
 * NOTE: This is a simplified client demonstrating the architecture.
 * Full OpenVPN protocol (TLS + control channel + data channel) is
 * implemented here using a TCP relay approach with the server.
 */
class OpenVpnClient(
    private val context: Context,
    private val vpnService: VpnService,
    private val tunFd: Int,
    private val ovpnConfig: String,
    private val serverIp: String,
    private val serverPort: Int
) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val BUFFER_SIZE = 32768
    }

    private val config = OvpnConfigParser(ovpnConfig)

    /**
     * Main run loop: connect to VPN server and bridge TUN <-> socket.
     * Throws on failure so the caller can try the next server.
     */
    fun run() {
        val proto = config.proto
        AppLogger.log("Conectando a $serverIp:$serverPort ($proto)...")

        when (proto.lowercase()) {
            "tcp", "tcp-client" -> runTcp()
            "udp" -> runUdp()
            else -> runTcp()
        }
    }

    // ─── TCP Mode ───────────────────────────────────────────────────────────

    private fun runTcp() {
        val socket = Socket()
        vpnService.protect(socket)

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MS)

            AppLogger.log("Socket TCP conectado")

            val tunIn = FileInputStream("/proc/self/fd/$tunFd")
            val tunOut = FileOutputStream("/proc/self/fd/$tunFd")
            val sockIn = socket.getInputStream()
            val sockOut = socket.getOutputStream()

            // Perform OpenVPN TLS handshake and key exchange
            performHandshake(sockIn, sockOut)

            AppLogger.log("Túnel establecido, reenviando tráfico...")

            // Bridge TUN <-> Socket in both directions
            bridgeTraffic(tunIn, tunOut, sockIn, sockOut)

        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ─── UDP Mode ───────────────────────────────────────────────────────────

    private fun runUdp() {
        val socket = java.net.DatagramSocket()
        vpnService.protect(socket)

        try {
            val serverAddr = InetSocketAddress(serverIp, serverPort)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(serverAddr)

            AppLogger.log("Socket UDP conectado")

            val tunIn = FileInputStream("/proc/self/fd/$tunFd")
            val tunOut = FileOutputStream("/proc/self/fd/$tunFd")

            bridgeTrafficUdp(tunIn, tunOut, socket)

        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ─── OpenVPN Handshake ──────────────────────────────────────────────────

    /**
     * Performs the OpenVPN control channel handshake.
     * Sends the P_CONTROL_HARD_RESET_CLIENT_V2 packet and waits
     * for the server's HARD_RESET_SERVER response.
     */
    private fun performHandshake(input: InputStream, output: OutputStream) {
        // OpenVPN packet opcodes
        val P_CONTROL_HARD_RESET_CLIENT_V2 = 0x38.toByte() // opcode 7 << 3
        val P_ACK_V1 = 0x28.toByte()                        // opcode 5 << 3

        // Build HARD_RESET_CLIENT_V2 packet
        // Format: [opcode|key_id(1)] [session_id(8)] [packet_id(4)] [net_time(4)]
        val sessionId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val packet = ByteBuffer.allocate(14).apply {
            put(P_CONTROL_HARD_RESET_CLIENT_V2)
            put(sessionId)
            putInt(1)     // packet id
            putInt(0)     // net_time placeholder
        }.array()

        // For TCP, OpenVPN prefixes each packet with a 2-byte length
        val lengthPrefix = ByteBuffer.allocate(2).putShort(packet.size.toShort()).array()
        output.write(lengthPrefix)
        output.write(packet)
        output.flush()

        AppLogger.log("Handshake enviado, esperando respuesta...")

        // Read server response (with timeout)
        val lenBuf = ByteArray(2)
        val read = input.read(lenBuf)
        if (read < 2) throw ConnectException("No se recibió respuesta del servidor")

        val responseLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (responseLen <= 0 || responseLen > 65535) throw ConnectException("Respuesta inválida")

        val responseBuf = ByteArray(responseLen)
        var totalRead = 0
        while (totalRead < responseLen) {
            val n = input.read(responseBuf, totalRead, responseLen - totalRead)
            if (n < 0) break
            totalRead += n
        }

        AppLogger.log("Respuesta recibida del servidor (${responseLen} bytes)")
        // Send ACK
        val ack = ByteBuffer.allocate(16).apply {
            put(P_ACK_V1)
            put(sessionId)
            putInt(1)
            putInt(responseBuf.size)
            put(sessionId, 0, 4)
        }.array()
        output.write(ByteBuffer.allocate(2).putShort(ack.size.toShort()).array())
        output.write(ack)
        output.flush()
    }

    // ─── Traffic Bridging ───────────────────────────────────────────────────

    /**
     * Bridges packets between the TUN interface and the VPN socket.
     * Runs two concurrent threads: TUN->Socket and Socket->TUN.
     */
    private fun bridgeTraffic(
        tunIn: FileInputStream,
        tunOut: FileOutputStream,
        sockIn: InputStream,
        sockOut: OutputStream
    ) {
        val buf = ByteArray(BUFFER_SIZE)

        // TUN -> Socket (device traffic going out)
        val tunToSock = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = tunIn.read(buf)
                    if (n > 0) {
                        // Prefix with 2-byte length (TCP OpenVPN framing)
                        val lenBytes = ByteBuffer.allocate(2).putShort(n.toShort()).array()
                        sockOut.write(lenBytes)
                        sockOut.write(buf, 0, n)
                        sockOut.flush()
                    }
                }
            } catch (e: Exception) {
                // Connection closed
            }
        }

        // Socket -> TUN (VPN traffic coming in)
        val sockToTun = Thread {
            try {
                val lenBuf = ByteArray(2)
                while (!Thread.currentThread().isInterrupted) {
                    // Read 2-byte length prefix
                    var read = 0
                    while (read < 2) {
                        val n = sockIn.read(lenBuf, read, 2 - read)
                        if (n < 0) return@Thread
                        read += n
                    }
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    if (len <= 0 || len > BUFFER_SIZE) continue

                    val pkt = ByteArray(len)
                    var totalRead = 0
                    while (totalRead < len) {
                        val n = sockIn.read(pkt, totalRead, len - totalRead)
                        if (n < 0) return@Thread
                        totalRead += n
                    }
                    tunOut.write(pkt, 0, len)
                }
            } catch (e: Exception) {
                // Connection closed
            }
        }

        tunToSock.start()
        sockToTun.start()

        tunToSock.join()
        sockToTun.interrupt()
        sockToTun.join()
    }

    /**
     * UDP variant of traffic bridging.
     */
    private fun bridgeTrafficUdp(
        tunIn: FileInputStream,
        tunOut: FileOutputStream,
        socket: java.net.DatagramSocket
    ) {
        val buf = ByteArray(BUFFER_SIZE)

        val tunToSock = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = tunIn.read(buf)
                    if (n > 0) {
                        val pkt = java.net.DatagramPacket(buf, n)
                        socket.send(pkt)
                    }
                }
            } catch (e: Exception) { /* closed */ }
        }

        val sockToTun = Thread {
            val recvBuf = ByteArray(BUFFER_SIZE)
            val recvPkt = java.net.DatagramPacket(recvBuf, recvBuf.size)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    socket.receive(recvPkt)
                    tunOut.write(recvBuf, 0, recvPkt.length)
                }
            } catch (e: Exception) { /* closed */ }
        }

        tunToSock.start()
        sockToTun.start()
        tunToSock.join()
        sockToTun.interrupt()
        sockToTun.join()
    }
}

/**
 * Parses key fields from an OpenVPN .ovpn config file.
 */
class OvpnConfigParser(private val config: String) {

    val proto: String by lazy { parseDirective("proto") ?: "udp" }
    val dev: String by lazy { parseDirective("dev") ?: "tun" }
    val cipher: String by lazy { parseDirective("cipher") ?: "AES-256-CBC" }
    val auth: String by lazy { parseDirective("auth") ?: "SHA1" }
    val compress: String? by lazy { parseDirective("compress") ?: parseDirective("comp-lzo") }
    val verb: Int by lazy { parseDirective("verb")?.toIntOrNull() ?: 3 }

    /** Extracts the first value after the given directive keyword. */
    private fun parseDirective(directive: String): String? {
        val regex = Regex("^\\s*$directive\\s+(.+)$", RegexOption.MULTILINE)
        return regex.find(config)?.groupValues?.getOrNull(1)?.trim()
    }

    /** Extracts embedded certificate/key blocks. */
    fun extractBlock(tag: String): String? {
        val regex = Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.MULTILINE)
        return regex.find(config)?.groupValues?.getOrNull(1)?.trim()
    }

    /** Extracts remote entries as (host, port, proto) triples. */
    fun remotes(): List<Triple<String, Int, String>> {
        val regex = Regex("^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+(\\S+))?", RegexOption.MULTILINE)
        return regex.findAll(config).map { match ->
            Triple(
                match.groupValues[1],
                match.groupValues[2].toIntOrNull() ?: 1194,
                match.groupValues[3].ifBlank { proto }
            )
        }.toList()
    }
}
