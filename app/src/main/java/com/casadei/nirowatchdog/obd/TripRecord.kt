package com.casadei.nirowatchdog.obd

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un viaggio registrato: dai primi dati BMS validi fino alla fine della sessione Android Auto o a una
 * pausa senza dati più lunga di [TripRecorder.TRIP_GAP_MS].
 *
 * - Distanza: dall'odometro del cruscotto (risoluzione 1 km) se letto a inizio e fine e il viaggio è di
 *   almeno [MIN_ODOMETER_TRIP_KM], altrimenti integrando la velocità.
 * - Energia: differenza dei contatori cumulativi del BMS (risoluzione 0,1 kWh). "Erogata" è l'energia
 *   uscita dalla batteria HV, "rientrata" quella recuperata in frenata o caricata dal motore termico.
 * - Benzina: stima integrando nel tempo il consumo istantaneo calcolato dal MAF (L/h).
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
                "benzina_litri;litri_100km;soc_inizio;soc_fine;odometro_inizio;odometro_fine"

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
                        t.endOdometerKm?.toString() ?: ""
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

    companion object {
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
