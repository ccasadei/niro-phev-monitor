package com.casadei.nirowatchdog.obd

import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Persistenza del registro viaggi; interfaccia separata per poter testare [TripRecorder] senza file. */
internal interface TripStore {
    fun load(): TripLog
    fun save(log: TripLog)
}

/** Registro viaggi in un file JSON nella memoria privata dell'app (scrittura atomica via file temporaneo). */
internal class FileTripStore(private val file: File) : TripStore {

    override fun load(): TripLog {
        if (!file.exists()) return TripLog()
        return try {
            TripLog.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            // File illeggibile: lo mette da parte invece di sovrascriverlo, e riparte da un registro vuoto
            file.renameTo(File(file.parentFile, file.name + ".corrotto-" + System.currentTimeMillis()))
            TripLog()
        }
    }

    override fun save(log: TripLog) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(log.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Costruisce il registro viaggi dai campioni del polling live (uno per ciclo con risposta BMS valida).
 *
 * - Un viaggio si apre al primo campione e si chiude quando tra due campioni passano più di [TRIP_GAP_MS]:
 *   una sosta breve (Android Auto che si scollega e ricollega, dongle che si riconnette) non lo spezza.
 *   [closeNow] lo chiude subito. I viaggi senza movimento né consumi ([TripRecord.isMeaningful]) non vengono
 *   archiviati.
 * - Il viaggio in corso viene salvato ogni [SAVE_INTERVAL_MS] e con [persist] quando il polling si ferma:
 *   se Android chiude il processo, al riavvio viene ripreso (pausa breve) oppure archiviato.
 *
 * Thread-safe: i metodi sono sincronizzati, perché il polling gira su un thread IO e azzeramento e lettura
 * arrivano dall'interfaccia.
 */
internal class TripRecorder(
    private val store: TripStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (TripLog) -> Unit = {},
    /** Riceve un messaggio all'inizio e alla chiusura di ogni viaggio (log eventi) */
    private val onEvent: (String) -> Unit = {}
) {
    companion object {
        const val TRIP_GAP_MS = TripLog.TRIP_GAP_MS
        /** Oltre questo intervallo tra due campioni non si integra (dati mancanti, es. riconnessione al dongle) */
        const val MAX_INTEGRATION_STEP_MS = 5_000L
        const val SAVE_INTERVAL_MS = 30_000L
        const val MAX_STORED_TRIPS = 500
    }

    private var log: TripLog
    private var lastSampleMs = 0L
    private var lastSaveMs = 0L

    init {
        log = store.load()
        val resumed = log.current
        if (resumed != null) {
            if (clock() - resumed.endMs > TRIP_GAP_MS) closeCurrent() else lastSampleMs = resumed.endMs
        }
    }

    @get:Synchronized
    val snapshot: TripLog
        get() = log

    /**
     * Aggiunge un campione: [s] è lo stato dopo il ciclo di polling, [freshOdometerKm] l'odometro letto in
     * questo ciclo (null se non letto o non valido).
     */
    @Synchronized
    fun onSample(s: NiroTelemetryState, freshOdometerKm: Int?) {
        val now = clock()
        var cur = log.current
        if (cur != null && now - lastSampleMs > TRIP_GAP_MS) {
            closeCurrent()
            cur = null
        }

        cur = if (cur == null) {
            onEvent(String.format(Locale.ITALY, "Viaggio iniziato (SoC %.1f %%)", s.socBms))
            TripRecord(
                startMs = now,
                endMs = now,
                startOdometerKm = freshOdometerKm,
                startSocPct = s.socBms,
                endSocPct = s.socBms,
                startDischargedKwh = s.cumulativeDischargedKwh,
                endDischargedKwh = s.cumulativeDischargedKwh,
                startChargedKwh = s.cumulativeChargedKwh,
                endChargedKwh = s.cumulativeChargedKwh
            )
        } else {
            var speedKm = cur.speedDistanceKm
            var evKm = cur.evDistanceKm
            var fuel = cur.fuelLiters
            val stepMs = now - lastSampleMs
            if (stepMs in 1..MAX_INTEGRATION_STEP_MS) {
                val hours = stepMs / 3_600_000.0
                val stepKm = s.vehicleSpeedKmh * hours
                speedKm += stepKm
                if (s.isEvMode) {
                    evKm += stepKm
                } else {
                    fuel += s.smoothedFuelLh * hours
                }
            }
            cur.copy(
                endMs = now,
                speedDistanceKm = speedKm,
                evDistanceKm = evKm,
                fuelLiters = fuel,
                endSocPct = s.socBms,
                endDischargedKwh = s.cumulativeDischargedKwh,
                endChargedKwh = s.cumulativeChargedKwh,
                startOdometerKm = cur.startOdometerKm ?: freshOdometerKm,
                endOdometerKm = if (freshOdometerKm != null && cur.startOdometerKm != null) freshOdometerKm else cur.endOdometerKm
            )
        }

        lastSampleMs = now
        log = log.copy(current = cur)
        if (now - lastSaveMs >= SAVE_INTERVAL_MS) {
            persist()
        }
        onChange(log)
    }

    /**
     * Chiude subito il viaggio in corso (azzeramento dalla pagina Viaggio): viene archiviato come gli altri e il
     * prossimo campione ne apre uno nuovo da zero.
     */
    @Synchronized
    fun closeNow() {
        if (log.current == null) return
        onEvent("Viaggio chiuso a mano dalla pagina Viaggio")
        closeCurrent()
    }

    /** Salva subito il viaggio in corso (es. quando il polling si interrompe senza chiuderlo). */
    @Synchronized
    fun persist() {
        lastSaveMs = clock()
        try {
            store.save(log)
        } catch (_: Exception) {
            // Memoria piena o simili: si riprova al prossimo salvataggio
        }
    }

    @Synchronized
    fun clear() {
        log = TripLog()
        lastSampleMs = 0L
        persist()
        onChange(log)
    }

    private fun closeCurrent() {
        val cur = log.current ?: return
        val completed = if (cur.isMeaningful) (listOf(cur) + log.completed).take(MAX_STORED_TRIPS) else log.completed
        log = TripLog(completed = completed, current = null)
        persist()
        onEvent(
            if (cur.isMeaningful) {
                String.format(
                    Locale.ITALY, "Viaggio chiuso: %.1f km (EV %.1f), %.1f kWh netti, %.2f L",
                    cur.distanceKm, cur.evDistanceKm, cur.netBatteryKwh, cur.fuelLiters
                )
            } else {
                "Viaggio scartato: nessun movimento né consumo"
            }
        )
        onChange(log)
    }
}
