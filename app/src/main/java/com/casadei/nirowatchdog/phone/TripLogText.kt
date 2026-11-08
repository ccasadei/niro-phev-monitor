package com.casadei.nirowatchdog.phone

import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripTotals
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Testo del registro viaggi mostrato nell'app telefono: totale di oggi, viaggio in corso, ultimi viaggi. */
internal object TripLogText {

    private const val MAX_LISTED_TRIPS = 30

    fun format(rawLog: TripLog, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        // Un viaggio "in corso" fermo da oltre 5 minuti è concluso: Android ha chiuso l'app prima di archiviarlo
        val log = rawLog.normalized(nowMs)
        val all = listOfNotNull(log.current) + log.completed
        if (all.isEmpty()) {
            return "Nessun viaggio registrato. Il registro si compila da solo quando Android Auto si collega " +
                "all'auto e il dongle OBD risponde."
        }

        val time = SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = zone }
        val dayTime = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).apply { timeZone = zone }

        return buildString {
            val today = all.filter { sameDay(it.startMs, nowMs, zone) }
            if (today.isEmpty()) {
                appendLine("OGGI: nessun viaggio")
            } else {
                appendLine("OGGI: ${totalsLine(TripTotals.of(today))}")
            }

            log.current?.let { cur ->
                appendLine()
                appendLine(
                    "▶ In corso dalle ${time.format(Date(cur.startMs))} — ${km(cur.distanceKm)} km " +
                        "(EV ${km(cur.evDistanceKm)}) • ${kwh(cur.netBatteryKwh.toDouble())} kWh netti • " +
                        "${liters(cur.fuelLiters)} L • risparmiati ${liters(cur.savedFuelLiters)} L"
                )
            }

            for (t in log.completed.take(MAX_LISTED_TRIPS)) {
                appendLine()
                appendLine(
                    "${dayTime.format(Date(t.startMs))}–${time.format(Date(t.endMs))} (${duration(t.durationMs)}) — " +
                        "${km(t.distanceKm)} km (EV ${km(t.evDistanceKm)})"
                )
                appendLine(
                    "   batteria: erogati ${kwh(t.batteryOutKwh.toDouble())} kWh, rientrati ${kwh(t.batteryInKwh.toDouble())}" +
                        " → ${kwh(t.netBatteryKwh.toDouble())} netti${per100(t.kwhPer100Km, "kWh")}" +
                        " • SoC ${pct(t.startSocPct)} → ${pct(t.endSocPct)}"
                )
                appendLine(
                    "   benzina: ${liters(t.fuelLiters)} L stimati${per100(t.litersPer100Km, "L")}" +
                        " • risparmiati ${liters(t.savedFuelLiters)} L con l'EV"
                )
            }
        }.trimEnd()
    }

    private fun totalsLine(t: TripTotals): String =
        "${km(t.distanceKm)} km (EV ${km(t.evDistanceKm)}) • batteria ${kwh(t.netBatteryKwh)} kWh netti" +
            "${per100(t.kwhPer100Km, "kWh")} • benzina ${liters(t.fuelLiters)} L${per100(t.litersPer100Km, "L")}" +
            " • risparmiati ${liters(t.savedFuelLiters)} L"

    private fun sameDay(aMs: Long, bMs: Long, zone: TimeZone): Boolean {
        val a = Calendar.getInstance(zone).apply { timeInMillis = aMs }
        val b = Calendar.getInstance(zone).apply { timeInMillis = bMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun km(v: Double) = "%.1f".format(Locale.ITALY, v)
    private fun kwh(v: Double) = "%.1f".format(Locale.ITALY, v)
    private fun liters(v: Double) = "%.2f".format(Locale.ITALY, v)
    private fun pct(v: Float) = "%.1f%%".format(Locale.ITALY, v)

    private fun per100(v: Double?, unit: String) =
        v?.let { " (${"%.1f".format(Locale.ITALY, it)} $unit/100 km)" } ?: ""

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
