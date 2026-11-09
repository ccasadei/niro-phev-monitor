package com.casadei.nirowatchdog.phone

import com.casadei.nirowatchdog.obd.FuelSavings
import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripTotals
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Testo del registro viaggi mostrato nell'app telefono: totalizzatore con le medie misurate, totale di oggi,
 * viaggio in corso, ultimi viaggi. La benzina risparmiata dei singoli viaggi usa la media in ibrido del
 * totalizzatore, la stima più affidabile disponibile.
 */
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
        val counter = log.counter
        val hybrid = counter.hybridLitersPer100Km

        return buildString {
            if (counter.trips == 0) {
                appendLine("TOTALIZZATORE: azzerato, riparte con il prossimo viaggio")
            } else {
                appendLine(
                    "TOTALIZZATORE dal ${dayTime.format(Date(counter.firstStartMs))} " +
                        "(${counter.trips} ${if (counter.trips == 1) "viaggio" else "viaggi"}): " +
                        totalsLine(counter)
                )
                appendLine(
                    "   elettrico ≈ ${km(counter.electricKm)} km a ${kwh(counter.evKwhPer100Km)} kWh/100 km • " +
                        "ibrido ≈ ${km(counter.hybridKm)} km a ${"%.1f".format(Locale.ITALY, hybrid)} L/100 km " +
                        if (counter.hybridMeasured) "(misurato)" else "(valore iniziale)"
                )
                appendLine("   si azzera con due tocchi sull'intestazione della pagina Viaggio di Android Auto")
            }
            appendLine()

            val today = all.filter { sameDay(it.startMs, nowMs, zone) }
            if (today.isEmpty()) {
                appendLine("OGGI: nessun viaggio")
            } else {
                appendLine("OGGI: ${totalsLine(TripTotals.of(today), hybrid)}")
            }

            log.current?.let { cur ->
                appendLine()
                appendLine(
                    "▶ In corso dalle ${time.format(Date(cur.startMs))} — ${km(cur.distanceKm)} km " +
                        "(termico spento ${km(cur.evDistanceKm)}) • ${kwh(cur.netBatteryKwh.toDouble())} kWh netti • " +
                        "${liters(cur.fuelLiters)} L • risparmiati ${liters(cur.savedFuelLiters(hybrid))} L"
                )
            }

            for (t in log.completed.take(MAX_LISTED_TRIPS)) {
                appendLine()
                appendLine(
                    "${dayTime.format(Date(t.startMs))}–${time.format(Date(t.endMs))} (${duration(t.durationMs)}) — " +
                        "${km(t.distanceKm)} km (termico spento ${km(t.evDistanceKm)})"
                )
                appendLine(
                    "   batteria: erogati ${kwh(t.batteryOutKwh.toDouble())} kWh, rientrati ${kwh(t.batteryInKwh.toDouble())}" +
                        " → ${kwh(t.netBatteryKwh.toDouble())} netti${per100(t.kwhPer100Km, "kWh")}" +
                        " • SoC ${pct(t.startSocPct)} → ${pct(t.endSocPct)}"
                )
                appendLine(
                    "   benzina: ${liters(t.fuelLiters)} L stimati${per100(t.litersPer100Km, "L")}" +
                        " • risparmiati ${liters(t.savedFuelLiters(hybrid))} L con l'EV"
                )
            }
        }.trimEnd()
    }

    /** Riga di somme; la benzina risparmiata usa [hybridLitersPer100Km], di default la media misurata nelle somme stesse */
    private fun totalsLine(t: TripTotals, hybridLitersPer100Km: Double = t.hybridLitersPer100Km): String {
        val saved = FuelSavings.savedLiters(t.distanceKm, t.fuelLiters, hybridLitersPer100Km)
        return "${km(t.distanceKm)} km (termico spento ${km(t.evDistanceKm)}) • batteria ${kwh(t.netBatteryKwh)} kWh netti" +
            "${per100(t.kwhPer100Km, "kWh")} • benzina ${liters(t.fuelLiters)} L${per100(t.litersPer100Km, "L")}" +
            " • risparmiati ${liters(saved)} L"
    }

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
