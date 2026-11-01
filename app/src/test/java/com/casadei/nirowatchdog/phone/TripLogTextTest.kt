package com.casadei.nirowatchdog.phone

import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripRecord
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

    @Test
    fun todayTotals_andTripDetails() {
        val text = TripLogText.format(TripLog(completed = listOf(trip)), morning + 8 * 3_600_000L, utc)

        assertTrue(text, text.startsWith("OGGI: 274,0 km (EV 120,4) • batteria 18,4 kWh netti (6,7 kWh/100 km)"))
        assertTrue(text, text.contains("benzina 9,80 L (3,6 L/100 km)"))
        assertTrue(text, text.contains("01/11 07:49–14:30 (6 h 41 min) — 274,0 km (EV 120,4)"))
        assertTrue(text, text.contains("erogati 25,1 kWh, rientrati 6,7 → 18,4 netti"))
        assertTrue(text, text.contains("SoC 85,5% → 16,0%"))
    }

    @Test
    fun tripsOfOtherDays_areNotInTodayTotals() {
        val text = TripLogText.format(TripLog(completed = listOf(trip)), morning + 2 * 86_400_000L, utc)
        assertTrue(text, text.startsWith("OGGI: nessun viaggio"))
    }
}
