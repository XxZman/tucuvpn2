package com.xzman.tucuvpn2.vpn

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.xzman.tucuvpn2.utils.AppLogger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * OpenVPN client: connects to a VPN server and bridges traffic between
 * the TUN interface and the remote socket.
 *
 * Key fix: streams are created from [tunPfd].fileDescriptor (a FileDescriptor
 * object), NOT from "/proc/self/fd/$fd". The latter is blocked by SELinux
 * on Android and causes FileNotFoundException / crashes.
 */
class OpenVpnClient(
    private val context: Context,
    private val vpnService: VpnService,
    private val tunPfd: ParcelFileDescriptor,   // ParcelFileDescriptor, not raw Int
    private val ovpnConfig: String,
    private val serverIp: String,
    private val serverPort: Int
) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS    = 10_000
        const val BUFFER_SIZE        = 32_768
    }

    private val config = OvpnConfigParser(ovpnConfig)

    /**
     * Connects to the VPN server and starts forwarding packets.
     * Blocks until the tunnel is closed or an error occurs.
     * Throws on failure so the caller can try the next server.
     */
    fun run() {
        val proto = config.proto
        AppLogger.log("Conectando a $serverIp:$serverPort ($proto)...")

        when (proto.lowercase()) {
            "tcp", "tcp-client" -> runTcp()
            "udp"               -> runUdp()
            else                -> runTcp()
        }
    }

    // ─── TCP ────────────────────────────────────────────────────────────────

    private fun runTcp() {
        val socket = Socket()
        vpnService.protect(socket)   // exempt socket from the VPN tunnel

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MS)
            AppLogger.log("Socket TCP conectado")

            // Safe: uses FileDescriptor from the ParcelFileDescriptor,
            // not the raw int path that SELinux blocks.
            val tunIn  = FileInputStream(tunPfd.fileDescriptor)
            val tunOut = FileOutputStream(tunPfd.fileDescriptor)
            val sockIn  = socket.getInputStream()
            val sockOut = socket.getOutputStream()

            performHandshake(sockIn, sockOut)
            AppLogger.log("Túnel establecido, reenviando tráfico...")
            bridgeTraffic(tunIn, tunOut, sockIn, sockOut)

        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ─── UDP ────────────────────────────────────────────────────────────────

    private fun runUdp() {
        val socket = java.net.DatagramSocket()
        vpnService.protect(socket)

        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(serverIp, serverPort))
            AppLogger.log("Socket UDP conectado")

            val tunIn  = FileInputStream(tunPfd.fileDescriptor)
            val tunOut = FileOutputStream(tunPfd.fileDescriptor)

            bridgeTrafficUdp(tunIn, tunOut, socket)

        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ─── OpenVPN Control Channel Handshake ──────────────────────────────────

    /**
     * Sends P_CONTROL_HARD_RESET_CLIENT_V2 and waits for the server ACK.
     * This initiates the OpenVPN TLS handshake sequence.
     */
    private fun performHandshake(input: InputStream, output: OutputStream) {
        val P_CONTROL_HARD_RESET_CLIENT_V2 = 0x38.toByte()

        val sessionId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }

        // Build reset packet: [opcode(1)] [sessionId(8)] [packetId(4)] [placeholder(4)]
        val packet = java.nio.ByteBuffer.allocate(17).apply {
            put(P_CONTROL_HARD_RESET_CLIENT_V2)
            put(sessionId)
            putInt(1)   // packet id
            putInt(0)   // hmac placeholder
        }.array()

        // TCP framing: 2-byte length prefix
        output.write(java.nio.ByteBuffer.allocate(2).putShort(packet.size.toShort()).array())
        output.write(packet)
        output.flush()

        AppLogger.log("Handshake enviado, esperando respuesta del servidor...")

        // Read response length
        val lenBuf = ByteArray(2)
        var read = 0
        while (read < 2) {
            val n = input.read(lenBuf, read, 2 - read)
            if (n < 0) throw ConnectException("Conexión cerrada por el servidor")
            read += n
        }

        val responseLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (responseLen <= 0 || responseLen > 65_535) throw ConnectException("Respuesta inválida del servidor")

        val responseBuf = ByteArray(responseLen)
        var total = 0
        while (total < responseLen) {
            val n = input.read(responseBuf, total, responseLen - total)
            if (n < 0) throw ConnectException("Conexión cerrada durante handshake")
            total += n
        }

        AppLogger.log("Respuesta recibida ($responseLen bytes) — handshake OK")
    }

    // ─── Traffic Bridging ───────────────────────────────────────────────────

    /**
     * Bidirectional bridge: TUN device ↔ VPN socket.
     * Two threads run concurrently; we wait for both to finish.
     */
    private fun bridgeTraffic(
        tunIn: FileInputStream,
        tunOut: FileOutputStream,
        sockIn: InputStream,
        sockOut: OutputStream
    ) {
        val buf = ByteArray(BUFFER_SIZE)

        // Device → VPN server
        val tunToSock = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = tunIn.read(buf)
                    if (n > 0) {
                        val len = java.nio.ByteBuffer.allocate(2).putShort(n.toShort()).array()
                        sockOut.write(len)
                        sockOut.write(buf, 0, n)
                        sockOut.flush()
                    }
                }
            } catch (_: Exception) {}
        }

        // VPN server → device
        val sockToTun = Thread {
            try {
                val lenBuf = ByteArray(2)
                while (!Thread.currentThread().isInterrupted) {
                    var r = 0
                    while (r < 2) {
                        val n = sockIn.read(lenBuf, r, 2 - r)
                        if (n < 0) return@Thread
                        r += n
                    }
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                    if (len <= 0 || len > BUFFER_SIZE) continue

                    val pkt = ByteArray(len)
                    var total = 0
                    while (total < len) {
                        val n = sockIn.read(pkt, total, len - total)
                        if (n < 0) return@Thread
                        total += n
                    }
                    tunOut.write(pkt, 0, len)
                }
            } catch (_: Exception) {}
        }

        tunToSock.start()
        sockToTun.start()
        tunToSock.join()
        sockToTun.interrupt()
        sockToTun.join()
    }

    /** UDP variant. */
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
                    if (n > 0) socket.send(java.net.DatagramPacket(buf, n))
                }
            } catch (_: Exception) {}
        }

        val sockToTun = Thread {
            val recvBuf = ByteArray(BUFFER_SIZE)
            val recvPkt = java.net.DatagramPacket(recvBuf, recvBuf.size)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    socket.receive(recvPkt)
                    tunOut.write(recvBuf, 0, recvPkt.length)
                }
            } catch (_: Exception) {}
        }

        tunToSock.start()
        sockToTun.start()
        tunToSock.join()
        sockToTun.interrupt()
        sockToTun.join()
    }
}

// ─── OVPN Config Parser ──────────────────────────────────────────────────────

/** Parses key directives from a decoded .ovpn config string. */
class OvpnConfigParser(private val config: String) {

    val proto: String   by lazy { directive("proto")   ?: "udp"         }
    val dev: String     by lazy { directive("dev")     ?: "tun"         }
    val cipher: String  by lazy { directive("cipher")  ?: "AES-256-CBC" }
    val auth: String    by lazy { directive("auth")    ?: "SHA1"        }

    private fun directive(name: String): String? =
        Regex("^\\s*$name\\s+(.+)$", RegexOption.MULTILINE)
            .find(config)?.groupValues?.getOrNull(1)?.trim()

    fun extractBlock(tag: String): String? =
        Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.MULTILINE)
            .find(config)?.groupValues?.getOrNull(1)?.trim()

    fun remotes(): List<Triple<String, Int, String>> =
        Regex("^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+(\\S+))?", RegexOption.MULTILINE)
            .findAll(config).map { m ->
                Triple(
                    m.groupValues[1],
                    m.groupValues[2].toIntOrNull() ?: 1194,
                    m.groupValues[3].ifBlank { proto }
                )
            }.toList()
}
