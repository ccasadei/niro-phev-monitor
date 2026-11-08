package com.casadei.nirowatchdog.car

import com.casadei.nirowatchdog.obd.FuelSavings
import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Testi della pagina "Viaggio" di Android Auto: il viaggio in corso oppure, se non ce n'è uno, l'ultimo concluso.
 * Separati dal disegno su Canvas per poterli verificare con i test JVM.
 */
internal data class TripPageModel(
    val status: Status,
    val chip: String,
    val period: String,
    val soc: String,
    val distance: String,
    /** Quote dei km percorsi in elettrico e con il termico (0..1), per la barra; entrambe 0 a viaggio appena partito */
    val evShare: Float,
    val iceShare: Float,
    val evKm: String,
    val iceKm: String,
    val batteryNet: String,
    val batteryDetail: String,
    val fuel: String,
    val fuelDetail: String,
    val saved: String,
    val savedDetail: String
) {
    enum class Status { IN_PROGRESS, LAST, NONE }

    companion object {
        private const val NO_VALUE = "–"

        fun of(log: TripLog, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): TripPageModel {
            // Un viaggio "in corso" senza dati da oltre 5 minuti è in realtà concluso
            val normalized = log.normalized(nowMs)
            val current = normalized.current
            val trip = current ?: normalized.completed.firstOrNull() ?: return empty()

            val time = SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = zone }
            val dayTime = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).apply { timeZone = zone }
            val inProgress = current != null
            val period = if (inProgress) {
                "dalle ${time.format(Date(trip.startMs))} • ${duration(trip.durationMs)}"
            } else {
                "${dayTime.format(Date(trip.startMs))}–${time.format(Date(trip.endMs))} • ${duration(trip.durationMs)}"
            }

            val distance = trip.distanceKm
            val evKm = trip.evDistanceKm.coerceIn(0.0, distance.coerceAtLeast(0.0))
            return TripPageModel(
                status = if (inProgress) Status.IN_PROGRESS else Status.LAST,
                chip = if (inProgress) "VIAGGIO IN CORSO" else "ULTIMO VIAGGIO",
                period = period,
                soc = "SoC ${one(trip.startSocPct.toDouble())} % → ${one(trip.endSocPct.toDouble())} %",
                distance = one(distance),
                evShare = if (distance >= 0.05) (evKm / distance).toFloat() else 0f,
                iceShare = if (distance >= 0.05) ((distance - evKm) / distance).toFloat() else 0f,
                evKm = "EV ${one(evKm)} km",
                iceKm = "termico ${one(distance - evKm)} km",
                batteryNet = one(trip.netBatteryKwh.toDouble()),
                batteryDetail = batteryDetail(trip),
                fuel = two(trip.fuelLiters),
                fuelDetail = trip.litersPer100Km?.let { "${one(it)} L/100 km • stima dal MAF" } ?: "stima dal MAF",
                saved = two(trip.savedFuelLiters),
                savedDetail = "in ibrido sarebbero serviti ${two(trip.hybridReferenceLiters)} L " +
                    "(${one(FuelSavings.HYBRID_REFERENCE_L_PER_100KM)} L/100 km)"
            )
        }

        private fun empty() = TripPageModel(
            status = Status.NONE,
            chip = "NESSUN VIAGGIO",
            period = "si registra da solo con Android Auto e dongle OBD collegati",
            soc = "",
            distance = NO_VALUE,
            evShare = 0f,
            iceShare = 0f,
            evKm = "",
            iceKm = "",
            batteryNet = NO_VALUE,
            batteryDetail = "",
            fuel = NO_VALUE,
            fuelDetail = "",
            saved = NO_VALUE,
            savedDetail = ""
        )

        private fun batteryDetail(t: TripRecord): String {
            val base = "erogati ${one(t.batteryOutKwh.toDouble())} • recuperati ${one(t.batteryInKwh.toDouble())}"
            return t.kwhPer100Km?.let { "$base • ${one(it)} kWh/100 km" } ?: base
        }

        private fun one(v: Double) = "%.1f".format(Locale.ITALY, v)
        private fun two(v: Double) = "%.2f".format(Locale.ITALY, v)

        private fun duration(ms: Long): String {
            val minutes = (ms / 60_000L).coerceAtLeast(0L)
            return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)} min" else "$minutes min"
        }
    }
}
