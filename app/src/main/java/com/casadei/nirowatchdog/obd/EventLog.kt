package com.casadei.nirowatchdog.obd

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log eventi persistente in `files/events.log` (più `events.log.1` dopo la rotazione): sessioni Android Auto,
 * connessioni al dongle, pause del polling e viaggi. Il logcat del telefono ruota in pochi minuti, questo file
 * resta disponibile per giorni:
 * `adb shell run-as com.casadei.nirowatchdog cat files/events.log`.
 */
object EventLog {

    private const val FILE_NAME = "events.log"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile
    private var file: EventLogFile? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file == null) file = EventLogFile(File(context.applicationContext.filesDir, FILE_NAME), MAX_BYTES)
        }
    }

    fun log(message: String) {
        file?.append(message)
    }
}

/** Righe con data e ora; oltre [maxBytes] il file diventa `<nome>.1`, sostituendo quello precedente. */
internal class EventLogFile(
    private val file: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)

    @Synchronized
    fun append(message: String) {
        try {
            if (file.length() >= maxBytes) {
                val previous = File(file.parentFile, file.name + ".1")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText("${format.format(Date(clock()))} ${message.replace('\n', ' ')}\n")
        } catch (_: Exception) {
            // Il log è solo diagnostico: un errore di scrittura non deve disturbare il polling
        }
    }
}
