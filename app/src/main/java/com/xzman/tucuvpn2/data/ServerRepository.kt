package com.xzman.tucuvpn2.data

import com.xzman.tucuvpn2.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses VPN server list from VPNGate API.
 */
class ServerRepository {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val vpnGateUrl = "https://www.vpngate.net/api/iphone/"

    /** Countries to filter (lowercase for comparison) */
    private val allowedCountries = setOf("japan", "united states", "canada", "brazil")

    /**
     * Downloads, parses, and returns a filtered + sorted list of VPN servers.
     * Returns top 50 servers sorted by speed descending.
     */
    suspend fun fetchServers(): Result<List<VpnServer>> = withContext(Dispatchers.IO) {
        try {
            AppLogger.log("Descargando servidores...")

            val request = Request.Builder()
                .url(vpnGateUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Android; VPN Client)")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Error HTTP: ${response.code}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Respuesta vacía del servidor"))

            AppLogger.log("Filtrando servidores...")
            val servers = parseCsv(body)

            val filtered = servers
                .filter { it.countryLong.lowercase() in allowedCountries }
                .filter { it.ovpnConfigDataBase64.isNotBlank() }
                .sortedByDescending { it.speed }
                .take(50)

            AppLogger.log("Se encontraron ${filtered.size} servidores disponibles")
            Result.success(filtered)

        } catch (e: Exception) {
            AppLogger.log("Error al descargar servidores: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Parses the VPNGate CSV response.
     * The CSV format has a header line starting with '#', then a column-name line,
     * followed by data rows.
     *
     * Columns (0-indexed):
     * 0  HostName
     * 1  IP
     * 2  Score
     * 3  Ping
     * 4  Speed
     * 5  CountryLong
     * 6  CountryShort
     * 7  NumVpnSessions
     * 8  Uptime
     * 9  TotalUsers
     * 10 TotalTraffic
     * 11 LogType
     * 12 Operator
     * 13 Message
     * 14 OpenVPN_ConfigData_Base64
     */
    private fun parseCsv(raw: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        val lines = raw.lines()

        // Skip comment/header lines (start with '*' or '#')
        var dataStartIndex = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("*") || line.startsWith("#") || line.isBlank()) {
                dataStartIndex = i + 1
                continue
            }
            // First non-comment line is the column header
            if (line.startsWith("HostName")) {
                dataStartIndex = i + 1
                break
            }
        }

        for (i in dataStartIndex until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank() || line.startsWith("*")) continue

            try {
                val cols = line.split(",")
                if (cols.size < 15) continue

                val server = VpnServer(
                    hostName = cols[0],
                    ip = cols[1],
                    score = cols[2].toLongOrNull() ?: 0L,
                    ping = cols[3].toIntOrNull() ?: 999,
                    speed = cols[4].toLongOrNull() ?: 0L,
                    countryLong = cols[5],
                    countryShort = cols[6],
                    numVpnSessions = cols[7].toIntOrNull() ?: 0,
                    uptime = cols[8].toLongOrNull() ?: 0L,
                    totalUsers = cols[9].toLongOrNull() ?: 0L,
                    totalTraffic = cols[10].toLongOrNull() ?: 0L,
                    logType = cols[11],
                    operator = cols[12],
                    message = cols[13],
                    ovpnConfigDataBase64 = cols[14]
                )
                servers.add(server)
            } catch (e: Exception) {
                // Skip malformed lines
            }
        }

        return servers
    }
}
