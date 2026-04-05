package com.xzman.tucuvpn2.data

/**
 * Represents a VPN server fetched from VPNGate.
 */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speed: Long,
    val countryLong: String,
    val countryShort: String,
    val numVpnSessions: Int,
    val uptime: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val ovpnConfigDataBase64: String
) {
    /** Decoded OpenVPN config string */
    fun decodedOvpnConfig(): String {
        return try {
            String(android.util.Base64.decode(ovpnConfigDataBase64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            ""
        }
    }

    /** Display label for logs */
    val displayName: String
        get() = "${countryShort}_${hostName.take(8)}"
}
