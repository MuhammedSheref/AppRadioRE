package com.ameer.appradiore.core.logging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface LogRepository {
    val logs: StateFlow<List<LogEntry>>
    fun log(
        direction: LogDirection,
        protocol: ProtocolType,
        summary: String,
        rawHex: String = "",
        details: String = "",
        isError: Boolean = false
    )
    fun log(entry: LogEntry)
    fun clearLogs()
    suspend fun exportLogs(context: Context): Uri
}

class LogRepositoryImpl @JvmOverloads constructor(
    private val maxCapacity: Int = 2000
) : LogRepository {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    override val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val lock = Any()
    private val logList = ArrayDeque<LogEntry>(maxCapacity)

    override fun log(
        direction: LogDirection,
        protocol: ProtocolType,
        summary: String,
        rawHex: String,
        details: String,
        isError: Boolean
    ) {
        val entry = LogEntry(
            direction = direction,
            protocol = protocol,
            summary = summary,
            rawHex = rawHex,
            details = details,
            isError = isError
        )
        log(entry)
    }

    override fun log(entry: LogEntry) {
        synchronized(lock) {
            if (logList.size >= maxCapacity) {
                logList.removeFirst()
            }
            logList.addLast(entry)
            _logs.value = logList.toList()
        }
    }

    override fun clearLogs() {
        synchronized(lock) {
            logList.clear()
            _logs.value = emptyList()
        }
    }

    override suspend fun exportLogs(context: Context): Uri = withContext(Dispatchers.IO) {
        val logsDir = File(context.cacheDir, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(logsDir, "AppRadio_Logs_$timeStamp.txt")

        val currentLogs = synchronized(lock) { logList.toList() }

        FileWriter(exportFile).use { writer ->
            writer.write("=====================================================\n")
            writer.write("PIONEER APPRADIO (AAM2) LIVE PROTOCOL LOG EXPORT\n")
            writer.write("Export Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
            writer.write("Total Packets/Events Logged: ${currentLogs.size}\n")
            writer.write("=====================================================\n\n")

            for (log in currentLogs) {
                writer.write(log.toExportString())
                writer.write("\n")
            }
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
    }
}
