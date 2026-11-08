package com.casadei.nirowatchdog.obd

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un viaggio registrato: dai primi dati BMS validi fino a una pausa senza dati più lunga di
 * [TripLog.TRIP_GAP_MS] (lo scollegamento di Android Auto da solo non lo chiude).
 *
 * - Distanza: dall'odometro del cruscotto (risoluzione 1 km) se letto a inizio e fine e il viaggio è di
 *   almeno [MIN_ODOMETER_TRIP_KM], altrimenti integrando la velocità.
 * - Energia: differenza dei contatori cumulativi del BMS (risoluzione 0,1 kWh). "Erogata" è l'energia
 *   uscita dalla batteria HV, "rientrata" quella recuperata in frenata o caricata dal motore termico.
 * - Benzina: stima integrando nel tempo il consumo istantaneo calcolato dal MAF (L/h).
 * - Benzina risparmiata: vedi [FuelSavings].
 */
data class TripRecord(
    val startMs: Long,
    val endMs: Long,
    val startOdometerKm: Int? = null,
    val endOdometerKm: Int? = null,
    val speedDistanceKm: Double = 0.0,
    val evDistanceKm: Double = 0.0,
    val startSocPct: Float,
    val endSocPct: Float,
    val startDischargedKwh: Float,
    val endDischargedKwh: Float,
    val startChargedKwh: Float,
    val endChargedKwh: Float,
    val fuelLiters: Double = 0.0
) {
    companion object {
        /** Sotto questa distanza l'odometro (1 km di risoluzione) è troppo grossolano: si usa la velocità integrata */
        const val MIN_ODOMETER_TRIP_KM = 10

        fun fromJson(o: JSONObject): TripRecord = TripRecord(
            startMs = o.getLong("startMs"),
            endMs = o.getLong("endMs"),
            startOdometerKm = if (o.has("startOdometerKm")) o.getInt("startOdometerKm") else null,
            endOdometerKm = if (o.has("endOdometerKm")) o.getInt("endOdometerKm") else null,
            speedDistanceKm = o.optDouble("speedDistanceKm", 0.0),
            evDistanceKm = o.optDouble("evDistanceKm", 0.0),
            startSocPct = o.getDouble("startSocPct").toFloat(),
            endSocPct = o.getDouble("endSocPct").toFloat(),
            startDischargedKwh = o.getDouble("startDischargedKwh").toFloat(),
            endDischargedKwh = o.getDouble("endDischargedKwh").toFloat(),
            startChargedKwh = o.getDouble("startChargedKwh").toFloat(),
            endChargedKwh = o.getDouble("endChargedKwh").toFloat(),
            fuelLiters = o.optDouble("fuelLiters", 0.0)
        )

        private const val CSV_HEADER =
            "inizio;fine;durata_min;km;km_ev;kwh_erogati;kwh_rientrati;kwh_netti;kwh_100km;" +
                "benzina_litri;litri_100km;soc_inizio;soc_fine;odometro_inizio;odometro_fine;benzina_risparmiata_litri"

        /** Esportazione CSV per fogli di calcolo italiani: separatore ';' e virgola decimale. */
        fun toCsv(trips: List<TripRecord>): String = buildString {
            appendLine(CSV_HEADER)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY)
            for (t in trips.sortedBy { it.startMs }) {
                appendLine(
                    listOf(
                        dateFormat.format(Date(t.startMs)),
                        dateFormat.format(Date(t.endMs)),
                        (t.durationMs / 60_000L).toString(),
                        "%.1f".format(Locale.ITALY, t.distanceKm),
                        "%.1f".format(Locale.ITALY, t.evDistanceKm),
                        "%.1f".format(Locale.ITALY, t.batteryOutKwh),
                        "%.1f".format(Locale.ITALY, t.batteryInKwh),
                        "%.1f".format(Locale.ITALY, t.netBatteryKwh),
                        t.kwhPer100Km?.let { "%.1f".format(Locale.ITALY, it) } ?: "",
                        "%.2f".format(Locale.ITALY, t.fuelLiters),
                        t.litersPer100Km?.let { "%.1f".format(Locale.ITALY, it) } ?: "",
                        "%.1f".format(Locale.ITALY, t.startSocPct),
                        "%.1f".format(Locale.ITALY, t.endSocPct),
                        t.startOdometerKm?.toString() ?: "",
                        t.endOdometerKm?.toString() ?: "",
                        "%.2f".format(Locale.ITALY, t.savedFuelLiters)
                    ).joinToString(";")
                )
            }
        }
    }

    val durationMs: Long
        get() = endMs - startMs

    val odometerDistanceKm: Int?
        get() = if (startOdometerKm != null && endOdometerKm != null) endOdometerKm - startOdometerKm else null

    val distanceKm: Double
        get() {
            val odo = odometerDistanceKm
            return if (odo != null && odo >= MIN_ODOMETER_TRIP_KM) odo.toDouble() else speedDistanceKm
        }

    val batteryOutKwh: Float
        get() = (endDischargedKwh - startDischargedKwh).coerceAtLeast(0f)

    val batteryInKwh: Float
        get() = (endChargedKwh - startChargedKwh).coerceAtLeast(0f)

    val netBatteryKwh: Float
        get() = batteryOutKwh - batteryInKwh

    val kwhPer100Km: Double?
        get() = if (distanceKm >= 1.0) netBatteryKwh * 100.0 / distanceKm else null

    val litersPer100Km: Double?
        get() = if (distanceKm >= 1.0) fuelLiters * 100.0 / distanceKm else null

    /** Benzina che sarebbe servita per gli stessi km guidando solo in ibrido */
    val hybridReferenceLiters: Double
        get() = FuelSavings.hybridLiters(distanceKm)

    /** Benzina risparmiata grazie alla batteria (stima, mai negativa) */
    val savedFuelLiters: Double
        get() = FuelSavings.savedLiters(distanceKm, fuelLiters)

    /** false per i viaggi senza movimento né consumi apprezzabili (es. quadro acceso da fermo): non si archiviano */
    val isMeaningful: Boolean
        get() = distanceKm >= 0.3 || batteryOutKwh >= 0.2f || fuelLiters >= 0.05

    fun toJson(): JSONObject = JSONObject().apply {
        put("startMs", startMs)
        put("endMs", endMs)
        startOdometerKm?.let { put("startOdometerKm", it) }
        endOdometerKm?.let { put("endOdometerKm", it) }
        put("speedDistanceKm", speedDistanceKm)
        put("evDistanceKm", evDistanceKm)
        put("startSocPct", startSocPct.toDouble())
        put("endSocPct", endSocPct.toDouble())
        put("startDischargedKwh", startDischargedKwh.toDouble())
        put("endDischargedKwh", endDischargedKwh.toDouble())
        put("startChargedKwh", startChargedKwh.toDouble())
        put("endChargedKwh", endChargedKwh.toDouble())
        put("fuelLiters", fuelLiters)
    }
}

/** Viaggi conclusi (dal più recente) e viaggio in corso. */
data class TripLog(
    val completed: List<TripRecord> = emptyList(),
    val current: TripRecord? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("completed", JSONArray().apply { completed.forEach { put(it.toJson()) } })
        current?.let { put("current", it.toJson()) }
    }

    /**
     * Il registro come va mostrato all'istante [nowMs]: un viaggio "in corso" senza campioni da più di
     * [TRIP_GAP_MS] è in realtà concluso (Android ha chiuso l'app prima che lo archiviasse) e va tra i conclusi.
     */
    fun normalized(nowMs: Long): TripLog {
        val cur = current ?: return this
        if (nowMs - cur.endMs <= TRIP_GAP_MS) return this
        return TripLog(completed = if (cur.isMeaningful) listOf(cur) + completed else completed, current = null)
    }

    companion object {
        /** Pausa senza dati oltre la quale un viaggio si considera concluso (sosta a motore spento) */
        const val TRIP_GAP_MS = 5 * 60_000L

        fun fromJson(o: JSONObject): TripLog {
            val arr = o.optJSONArray("completed") ?: JSONArray()
            return TripLog(
                completed = (0 until arr.length()).map { TripRecord.fromJson(arr.getJSONObject(it)) },
                current = o.optJSONObject("current")?.let { TripRecord.fromJson(it) }
            )
        }
    }
}

/** Somme di più viaggi, ad esempio quelli di una giornata. */
data class TripTotals(
    val distanceKm: Double,
    val evDistanceKm: Double,
    val batteryOutKwh: Double,
    val batteryInKwh: Double,
    val fuelLiters: Double
) {
    val netBatteryKwh: Double
        get() = batteryOutKwh - batteryInKwh

    val kwhPer100Km: Double?
        get() = if (distanceKm >= 1.0) netBatteryKwh * 100.0 / distanceKm else null

    val litersPer100Km: Double?
        get() = if (distanceKm >= 1.0) fuelLiters * 100.0 / distanceKm else null

    val hybridReferenceLiters: Double
        get() = FuelSavings.hybridLiters(distanceKm)

    val savedFuelLiters: Double
        get() = FuelSavings.savedLiters(distanceKm, fuelLiters)

    companion object {
        fun of(trips: List<TripRecord>) = TripTotals(
            distanceKm = trips.sumOf { it.distanceKm },
            evDistanceKm = trips.sumOf { it.evDistanceKm },
            batteryOutKwh = trips.sumOf { it.batteryOutKwh.toDouble() },
            batteryInKwh = trips.sumOf { it.batteryInKwh.toDouble() },
            fuelLiters = trips.sumOf { it.fuelLiters }
        )
    }
}

/**
 * Stima della benzina risparmiata guidando con la batteria: la benzina che sarebbe servita per gli stessi km in
 * ibrido con la batteria in riserva ([HYBRID_REFERENCE_L_PER_100KM]) meno quella stimata dal MAF. È una stima:
 * entrambi i termini sono approssimati e il consumo in ibrido dipende da percorso, velocità e temperatura.
 */
object FuelSavings {
    /** Consumo indicativo della Niro SG2 PHEV in ibrido con la batteria in riserva (L/100 km) */
    const val HYBRID_REFERENCE_L_PER_100KM = 5.0

    fun hybridLiters(distanceKm: Double): Double = distanceKm.coerceAtLeast(0.0) * HYBRID_REFERENCE_L_PER_100KM / 100.0

    fun savedLiters(distanceKm: Double, fuelLiters: Double): Double =
        (hybridLiters(distanceKm) - fuelLiters).coerceAtLeast(0.0)
}
