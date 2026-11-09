package com.casadei.nirowatchdog.phone

import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripRecord
import com.casadei.nirowatchdog.obd.TripTotals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TripLogTextTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 1 novembre 2026, 07:49 UTC */
    private val morning = 1_793_519_340_000L

    private val trip = TripRecord(
        startMs = morning,
        endMs = morning + (6 * 60 + 41) * 60_000L,
        startOdometerKm = 100000,
        endOdometerKm = 100274,
        speedDistanceKm = 271.0,
        evDistanceKm = 120.4,
        startSocPct = 85.5f,
        endSocPct = 16f,
        startDischargedKwh = 1000f,
        endDischargedKwh = 1025.1f,
        startChargedKwh = 900f,
        endChargedKwh = 906.7f,
        fuelLiters = 9.8
    )

    @Test
    fun emptyLog_explainsHowItFillsUp() {
        assertTrue(TripLogText.format(TripLog(), morning, utc).startsWith("Nessun viaggio registrato"))
    }

    private val log = TripLog(completed = listOf(trip), counterBase = TripTotals.of(trip))

    @Test
    fun counterTodayTotals_andTripDetails() {
        val text = TripLogText.format(log, morning + 8 * 3_600_000L, utc)

        // 18,4 kWh netti a 15 kWh/100 km (valore iniziale, nessun km in elettrico puro) = 122,7 km elettrici;
        // i restanti 151,3 km a benzina con 9,80 L danno, con il valore iniziale, 6,1 L/100 km in ibrido
        assertTrue(
            text,
            text.startsWith(
                "TOTALIZZATORE dal 01/11 07:49 (1 viaggio): 274,0 km (termico spento 120,4) • batteria 18,4 kWh netti " +
                    "(6,7 kWh/100 km) • benzina 9,80 L (3,6 L/100 km) • risparmiati 6,94 L"
            )
        )
        assertTrue(text, text.contains("elettrico ≈ 122,7 km a 15,0 kWh/100 km • ibrido ≈ 151,3 km a 6,1 L/100 km (misurato)"))
        assertTrue(text, text.contains("OGGI: 274,0 km (termico spento 120,4) • batteria 18,4 kWh netti (6,7 kWh/100 km)"))
        assertTrue(text, text.contains("01/11 07:49–14:30 (6 h 41 min) — 274,0 km (termico spento 120,4)"))
        assertTrue(text, text.contains("erogati 25,1 kWh, rientrati 6,7 → 18,4 netti"))
        assertTrue(text, text.contains("SoC 85,5% → 16,0%"))
        assertTrue(text, text.contains("benzina: 9,80 L stimati (3,6 L/100 km) • risparmiati 6,94 L con l'EV"))
    }

    @Test
    fun counterReset_isShownAsEmpty() {
        val text = TripLogText.format(log.copy(counterSinceMs = trip.endMs + 1, counterBase = TripTotals.ZERO), morning + 8 * 3_600_000L, utc)
        assertTrue(text, text.startsWith("TOTALIZZATORE: azzerato, riparte con il prossimo viaggio"))
        assertTrue(text, text.contains("01/11 07:49–14:30")) // l'elenco dei viaggi resta
    }

    @Test
    fun staleTripInProgress_isListedAsConcluded() {
        // Android ha chiuso l'app prima che archiviasse il viaggio: dopo 5 minuti senza dati non è più "in corso"
        val text = TripLogText.format(TripLog(current = trip), trip.endMs + 10 * 60_000L, utc)
        assertFalse(text, text.contains("In corso"))
        assertTrue(text, text.contains("01/11 07:49–14:30"))
        assertTrue(text, text.contains("TOTALIZZATORE dal 01/11 07:49 (1 viaggio)"))
    }

    @Test
    fun tripsOfOtherDays_areNotInTodayTotals() {
        val text = TripLogText.format(log, morning + 2 * 86_400_000L, utc)
        assertTrue(text, text.contains("OGGI: nessun viaggio"))
    }
}
