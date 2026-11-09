package com.casadei.nirowatchdog.car

import com.casadei.nirowatchdog.obd.TripLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Testi della pagina "Viaggio" di Android Auto: il totalizzatore, cioè tutti i viaggi dall'ultimo azzeramento
 * fatto a mano, compreso quello in corso. Separati dal disegno su Canvas per poterli verificare con i test JVM.
 */
internal data class TripPageModel(
    val status: Status,
    val chip: String,
    val period: String,
    val distance: String,
    /** Quote dei km coperti con l'energia della batteria e con la benzina (0..1), per la barra */
    val electricShare: Float,
    val hybridShare: Float,
    val electricKm: String,
    val hybridKm: String,
    val batteryNet: String,
    val batteryDetail: String,
    val fuel: String,
    val fuelDetail: String,
    val saved: String,
    val savedDetail: String
) {
    enum class Status { DRIVING, PAUSED, EMPTY }

    /** Il totalizzatore ha qualcosa da azzerare */
    val canReset: Boolean
        get() = status != Status.EMPTY

    companion object {
        private const val NO_VALUE = "–"

        fun of(log: TripLog, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): TripPageModel {
            // Un viaggio "in corso" senza dati da oltre 5 minuti è in realtà concluso
            val normalized = log.normalized(nowMs)
            val current = normalized.current
            val c = normalized.counter
            val dayTime = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).apply { timeZone = zone }
            if (current == null && c.trips == 0) {
                val period = if (normalized.counterSinceMs > 0L) {
                    "azzerato il ${dayTime.format(Date(normalized.counterSinceMs))} • riparte con il prossimo viaggio"
                } else {
                    "si compila da solo con Android Auto e dongle OBD collegati"
                }
                return empty(period)
            }

            // Il viaggio appena partito entra nelle somme solo quando diventa significativo, ma si conta già
            val trips = c.trips + if (current != null && !current.isMeaningful) 1 else 0
            val since = c.firstStartMs.takeIf { it > 0L } ?: current?.startMs ?: nowMs
            val distance = c.distanceKm
            val h = c.hybridLitersPer100Km
            return TripPageModel(
                status = if (current != null) Status.DRIVING else Status.PAUSED,
                chip = if (current != null) "IN VIAGGIO" else "IN PAUSA",
                period = "dal ${dayTime.format(Date(since))} • $trips ${if (trips == 1) "viaggio" else "viaggi"} • " +
                    duration(c.drivingMs),
                distance = one(distance),
                electricShare = if (distance >= 0.05) (c.electricKm / distance).toFloat() else 0f,
                hybridShare = if (distance >= 0.05) (c.hybridKm / distance).toFloat() else 0f,
                electricKm = "elettrico ≈ ${one(c.electricKm)} km",
                hybridKm = "ibrido ≈ ${one(c.hybridKm)} km",
                batteryNet = one(c.netBatteryKwh),
                batteryDetail = "erogati ${one(c.batteryOutKwh)} • recuperati ${one(c.batteryInKwh)} • " +
                    "EV ${one(c.evKwhPer100Km)} kWh/100 km",
                fuel = two(c.fuelLiters),
                fuelDetail = (c.litersPer100Km?.let { "media ${one(it)} L/100 km • " } ?: "") +
                    "ibrido ${one(h)} L/100 km " + if (c.hybridMeasured) "(misurato)" else "(valore iniziale)",
                saved = two(c.savedFuelLiters),
                savedDetail = "in solo ibrido sarebbero serviti ${two(c.hybridReferenceLiters)} L (${one(h)} L/100 km)"
            )
        }

        private fun empty(period: String) = TripPageModel(
            status = Status.EMPTY,
            chip = "NESSUN DATO",
            period = period,
            distance = NO_VALUE,
            electricShare = 0f,
            hybridShare = 0f,
            electricKm = "",
            hybridKm = "",
            batteryNet = NO_VALUE,
            batteryDetail = "",
            fuel = NO_VALUE,
            fuelDetail = "",
            saved = NO_VALUE,
            savedDetail = ""
        )

        private fun one(v: Double) = "%.1f".format(Locale.ITALY, v)
        private fun two(v: Double) = "%.2f".format(Locale.ITALY, v)

        private fun duration(ms: Long): String {
            val minutes = (ms / 60_000L).coerceAtLeast(0L)
            return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)} min" else "$minutes min"
        }
    }
}
