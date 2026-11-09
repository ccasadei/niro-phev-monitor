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
 * - [evDistanceKm]: km con il motore termico spento. In ibrido il termico si spegne spesso e l'auto va con
 *   l'energia che lo stesso termico ha messo in batteria: non sono km "elettrici" nel senso della presa.
 * - [evOnlyKm] / [evOnlyKwh]: km e kWh netti con il termico spento e la batteria sopra la riserva HEV, cioè
 *   la guida elettrica vera e propria, per misurare il consumo elettrico (vedi [FuelSavings]).
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
    val fuelLiters: Double = 0.0,
    val evOnlyKm: Double = 0.0,
    val evOnlyKwh: Double = 0.0
) {
    companion object {
        /** Sotto questa distanza l'odometro (1 km di risoluzione) è troppo grossolano: si usa la velocità integrata */
        const val MIN_ODOMETER_TRIP_KM = 10

        fun fromJson(o: JSONObject): TripRecord {
            val trip = TripRecord(
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
                fuelLiters = o.optDouble("fuelLiters", 0.0),
                evOnlyKm = o.optDouble("evOnlyKm", 0.0),
                evOnlyKwh = o.optDouble("evOnlyKwh", 0.0)
            )
            // Viaggi registrati prima della misura del consumo elettrico: quelli senza benzina sono stati
            // interamente in elettrico e valgono come misura; gli altri non si possono separare
            return if (!o.has("evOnlyKm") && trip.fuelLiters < FuelSavings.MIN_FUEL_FOR_HYBRID_L) {
                trip.copy(evOnlyKm = trip.distanceKm, evOnlyKwh = trip.netBatteryKwh.toDouble())
            } else {
                trip
            }
        }

        private const val CSV_HEADER =
            "inizio;fine;durata_min;km;km_termico_spento;kwh_erogati;kwh_rientrati;kwh_netti;kwh_100km;" +
                "benzina_litri;litri_100km;soc_inizio;soc_fine;odometro_inizio;odometro_fine;benzina_risparmiata_litri"

        /**
         * Esportazione CSV per fogli di calcolo italiani: separatore ';' e virgola decimale. La benzina risparmiata
         * usa il consumo in ibrido [hybridLitersPer100Km] (quello del totalizzatore).
         */
        fun toCsv(trips: List<TripRecord>, hybridLitersPer100Km: Double): String = buildString {
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
                        "%.2f".format(Locale.ITALY, t.savedFuelLiters(hybridLitersPer100Km))
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

    /** Benzina risparmiata rispetto alla guida solo in ibrido al consumo [hybridLitersPer100Km] (stima, mai negativa) */
    fun savedFuelLiters(hybridLitersPer100Km: Double): Double =
        FuelSavings.savedLiters(distanceKm, fuelLiters, hybridLitersPer100Km)

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
        put("evOnlyKm", evOnlyKm)
        put("evOnlyKwh", evOnlyKwh)
    }
}

/**
 * Viaggi conclusi (dal più recente), viaggio in corso e totalizzatore della pagina Viaggio.
 *
 * Il totalizzatore somma tutti i viaggi iniziati dopo [counterSinceMs], l'ultimo azzeramento fatto a mano:
 * [counterBase] contiene i viaggi già conclusi, [counter] aggiunge quello in corso. Non dipende dall'elenco
 * dei viaggi, che conserva solo gli ultimi.
 */
data class TripLog(
    val completed: List<TripRecord> = emptyList(),
    val current: TripRecord? = null,
    val counterSinceMs: Long = 0L,
    val counterBase: TripTotals = TripTotals.ZERO
) {
    /** Totali del totalizzatore, compreso il viaggio in corso */
    val counter: TripTotals
        get() {
            val cur = current?.takeIf { it.startMs >= counterSinceMs && it.isMeaningful } ?: return counterBase
            return counterBase + TripTotals.of(cur)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 2)
        put("completed", JSONArray().apply { completed.forEach { put(it.toJson()) } })
        current?.let { put("current", it.toJson()) }
        put("counterSinceMs", counterSinceMs)
        put("counter", counterBase.toJson())
    }

    /** Il registro dopo la chiusura del viaggio in corso: archiviato e sommato al totalizzatore, se significativo. */
    fun closingCurrent(): TripLog {
        val cur = current ?: return this
        if (!cur.isMeaningful) return copy(current = null)
        return copy(
            completed = listOf(cur) + completed,
            current = null,
            counterBase = if (cur.startMs >= counterSinceMs) counterBase + TripTotals.of(cur) else counterBase
        )
    }

    /**
     * Il registro come va mostrato all'istante [nowMs]: un viaggio "in corso" senza campioni da più di
     * [TRIP_GAP_MS] è in realtà concluso (Android ha chiuso l'app prima che lo archiviasse) e va tra i conclusi.
     */
    fun normalized(nowMs: Long): TripLog {
        val cur = current ?: return this
        if (nowMs - cur.endMs <= TRIP_GAP_MS) return this
        return closingCurrent()
    }

    companion object {
        /** Pausa senza dati oltre la quale un viaggio si considera concluso (sosta a motore spento) */
        const val TRIP_GAP_MS = 5 * 60_000L

        fun fromJson(o: JSONObject): TripLog {
            val arr = o.optJSONArray("completed") ?: JSONArray()
            val completed = (0 until arr.length()).map { TripRecord.fromJson(arr.getJSONObject(it)) }
            val current = o.optJSONObject("current")?.let { TripRecord.fromJson(it) }
            val counter = o.optJSONObject("counter")
            return if (counter != null) {
                TripLog(completed, current, o.optLong("counterSinceMs", 0L), TripTotals.fromJson(counter))
            } else {
                // Registro creato prima del totalizzatore: parte da tutti i viaggi già registrati
                TripLog(completed, current, 0L, TripTotals.of(completed))
            }
        }
    }
}

/**
 * Somme di più viaggi (una giornata, il totalizzatore) e stime ricavate dalle somme: consumo elettrico e in
 * ibrido misurati, km coperti con l'energia della batteria e benzina risparmiata (vedi [FuelSavings]).
 */
data class TripTotals(
    val distanceKm: Double = 0.0,
    val evDistanceKm: Double = 0.0,
    val batteryOutKwh: Double = 0.0,
    val batteryInKwh: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val evOnlyKm: Double = 0.0,
    val evOnlyKwh: Double = 0.0,
    val trips: Int = 0,
    val drivingMs: Long = 0L,
    /** Inizio del primo viaggio sommato (0 se nessuno) */
    val firstStartMs: Long = 0L
) {
    val netBatteryKwh: Double
        get() = batteryOutKwh - batteryInKwh

    val kwhPer100Km: Double?
        get() = if (distanceKm >= 1.0) netBatteryKwh * 100.0 / distanceKm else null

    val litersPer100Km: Double?
        get() = if (distanceKm >= 1.0) fuelLiters * 100.0 / distanceKm else null

    /** Consumo della guida elettrica (kWh/100 km), misurato sui km in elettrico vero e proprio */
    val evKwhPer100Km: Double
        get() = FuelSavings.evKwhPer100Km(evOnlyKm, evOnlyKwh)

    /** km coperti con l'energia netta presa dalla batteria (stima) */
    val electricKm: Double
        get() = FuelSavings.electricKm(distanceKm, netBatteryKwh, evKwhPer100Km)

    /** km coperti con la benzina, anche quelli a termico spento in ibrido (stima) */
    val hybridKm: Double
        get() = distanceKm - electricKm

    /** true quando la media in ibrido viene dalla benzina misurata, false se è ancora il valore iniziale */
    val hybridMeasured: Boolean
        get() = fuelLiters >= FuelSavings.MIN_FUEL_FOR_HYBRID_L

    /** Consumo in ibrido sul percorso (L/100 km) */
    val hybridLitersPer100Km: Double
        get() = FuelSavings.hybridLitersPer100Km(fuelLiters, hybridKm)

    /** Benzina che sarebbe servita per gli stessi km guidando solo in ibrido */
    val hybridReferenceLiters: Double
        get() = distanceKm.coerceAtLeast(0.0) * hybridLitersPer100Km / 100.0

    /** Benzina risparmiata grazie all'energia della batteria (stima, mai negativa) */
    val savedFuelLiters: Double
        get() = FuelSavings.savedLiters(distanceKm, fuelLiters, hybridLitersPer100Km)

    operator fun plus(o: TripTotals) = TripTotals(
        distanceKm = distanceKm + o.distanceKm,
        evDistanceKm = evDistanceKm + o.evDistanceKm,
        batteryOutKwh = batteryOutKwh + o.batteryOutKwh,
        batteryInKwh = batteryInKwh + o.batteryInKwh,
        fuelLiters = fuelLiters + o.fuelLiters,
        evOnlyKm = evOnlyKm + o.evOnlyKm,
        evOnlyKwh = evOnlyKwh + o.evOnlyKwh,
        trips = trips + o.trips,
        drivingMs = drivingMs + o.drivingMs,
        firstStartMs = listOf(firstStartMs, o.firstStartMs).filter { it > 0L }.minOrNull() ?: 0L
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("distanceKm", distanceKm)
        put("evDistanceKm", evDistanceKm)
        put("batteryOutKwh", batteryOutKwh)
        put("batteryInKwh", batteryInKwh)
        put("fuelLiters", fuelLiters)
        put("evOnlyKm", evOnlyKm)
        put("evOnlyKwh", evOnlyKwh)
        put("trips", trips)
        put("drivingMs", drivingMs)
        put("firstStartMs", firstStartMs)
    }

    companion object {
        val ZERO = TripTotals()

        fun of(t: TripRecord) = TripTotals(
            distanceKm = t.distanceKm,
            evDistanceKm = t.evDistanceKm,
            batteryOutKwh = t.batteryOutKwh.toDouble(),
            batteryInKwh = t.batteryInKwh.toDouble(),
            fuelLiters = t.fuelLiters,
            evOnlyKm = t.evOnlyKm,
            evOnlyKwh = t.evOnlyKwh,
            trips = 1,
            drivingMs = t.durationMs.coerceAtLeast(0L),
            firstStartMs = t.startMs
        )

        fun of(trips: List<TripRecord>): TripTotals = trips.fold(ZERO) { acc, t -> acc + of(t) }

        fun fromJson(o: JSONObject) = TripTotals(
            distanceKm = o.optDouble("distanceKm", 0.0),
            evDistanceKm = o.optDouble("evDistanceKm", 0.0),
            batteryOutKwh = o.optDouble("batteryOutKwh", 0.0),
            batteryInKwh = o.optDouble("batteryInKwh", 0.0),
            fuelLiters = o.optDouble("fuelLiters", 0.0),
            evOnlyKm = o.optDouble("evOnlyKm", 0.0),
            evOnlyKwh = o.optDouble("evOnlyKwh", 0.0),
            trips = o.optInt("trips", 0),
            drivingMs = o.optLong("drivingMs", 0L),
            firstStartMs = o.optLong("firstStartMs", 0L)
        )
    }
}

/**
 * Stima della benzina risparmiata grazie all'energia della batteria, con bilancio energetico:
 *
 * 1. consumo elettrico `e` (kWh/100 km), misurato sui km con il termico spento e la batteria sopra la riserva;
 * 2. km elettrici = kWh netti usciti dalla batteria ÷ `e`; il resto sono km a benzina (ibrido), compresi quelli
 *    a termico spento in cui l'auto usa l'energia messa in batteria dal termico;
 * 3. consumo in ibrido `h` (L/100 km) = benzina ÷ km a benzina;
 * 4. benzina risparmiata = km totali × `h` − benzina usata (cioè i km elettrici al consumo dell'ibrido).
 *
 * Le due medie partono da un valore iniziale ([DEFAULT_EV_KWH_PER_100KM], [DEFAULT_HYBRID_L_PER_100KM]) che
 * pesa come [EV_PRIOR_KM] e [HYBRID_PRIOR_KM] km di misura: con pochi dati la stima resta vicina al valore
 * iniziale, dopo qualche centinaio di km conta quasi solo la misura. Finché il termico non ha consumato almeno
 * [MIN_FUEL_FOR_HYBRID_L] la media in ibrido è il valore iniziale.
 */
object FuelSavings {
    /** Consumo indicativo della Niro SG2 PHEV in ibrido con la batteria in riserva (L/100 km) */
    const val DEFAULT_HYBRID_L_PER_100KM = 5.0
    /** Consumo indicativo della Niro SG2 PHEV in elettrico (kWh/100 km, lato batteria) */
    const val DEFAULT_EV_KWH_PER_100KM = 15.0
    const val HYBRID_PRIOR_KM = 50.0
    const val EV_PRIOR_KM = 20.0
    const val MIN_FUEL_FOR_HYBRID_L = 0.05

    fun evKwhPer100Km(evOnlyKm: Double, evOnlyKwh: Double): Double {
        val km = evOnlyKm.coerceAtLeast(0.0)
        val kwh = (evOnlyKwh + DEFAULT_EV_KWH_PER_100KM / 100.0 * EV_PRIOR_KM).coerceAtLeast(0.0)
        return (kwh / (km + EV_PRIOR_KM) * 100.0).coerceIn(5.0, 40.0)
    }

    fun electricKm(distanceKm: Double, netBatteryKwh: Double, evKwhPer100Km: Double): Double =
        (netBatteryKwh / evKwhPer100Km * 100.0).coerceIn(0.0, distanceKm.coerceAtLeast(0.0))

    fun hybridLitersPer100Km(fuelLiters: Double, hybridKm: Double): Double {
        if (fuelLiters < MIN_FUEL_FOR_HYBRID_L) return DEFAULT_HYBRID_L_PER_100KM
        val liters = fuelLiters + DEFAULT_HYBRID_L_PER_100KM / 100.0 * HYBRID_PRIOR_KM
        return liters / (hybridKm.coerceAtLeast(0.0) + HYBRID_PRIOR_KM) * 100.0
    }

    fun savedLiters(distanceKm: Double, fuelLiters: Double, hybridLitersPer100Km: Double): Double =
        (distanceKm.coerceAtLeast(0.0) * hybridLitersPer100Km / 100.0 - fuelLiters).coerceAtLeast(0.0)
}
