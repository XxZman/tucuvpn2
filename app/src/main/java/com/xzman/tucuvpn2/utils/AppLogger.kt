package com.xzman.tucuvpn2.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe centralized logger.
 * Exposes a [StateFlow] of log lines that the UI can observe.
 */
object AppLogger {

    private const val TAG = "TucuVPN2"
    private const val MAX_LOG_LINES = 200

    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> = _logFlow.asStateFlow()

    private val logBuffer = ArrayDeque<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message"

        Log.d(TAG, message)

        logBuffer.addLast(line)
        if (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.removeFirst()
        }

        _logFlow.value = logBuffer.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        logBuffer.clear()
        _logFlow.value = ""
    }
}
