package com.casadei.nirowatchdog.car

import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TripPageModelTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 7 novembre 2026, 09:46 UTC */
    private val start = 1_794_044_760_000L

    /** Il viaggio dei dati di esempio del design: 36 km, 4,3 kWh netti, 0,50 L in 47 minuti */
    private val trip = TripRecord(
        startMs = start,
        endMs = start + 47 * 60_000L,
        speedDistanceKm = 36.0,
        evDistanceKm = 29.8,
        startSocPct = 55f,
        endSocPct = 16.5f,
        startDischargedKwh = 1000f,
        endDischargedKwh = 1005.4f,
        startChargedKwh = 900f,
        endChargedKwh = 901.1f,
        fuelLiters = 0.5
    )

    @Test
    fun tripInProgress() {
        val m = TripPageModel.of(TripLog(current = trip), trip.endMs + 1_000L, utc)

        assertEquals(TripPageModel.Status.IN_PROGRESS, m.status)
        assertEquals("VIAGGIO IN CORSO", m.chip)
        assertEquals("dalle 09:46 • 47 min", m.period)
        assertEquals("SoC 55,0 % → 16,5 %", m.soc)
        assertEquals("36,0", m.distance)
        assertEquals(29.8f / 36f, m.evShare, 1e-4f)
        assertEquals(6.2f / 36f, m.iceShare, 1e-4f)
        assertEquals("EV 29,8 km", m.evKm)
        assertEquals("termico 6,2 km", m.iceKm)
        assertEquals("4,3", m.batteryNet)
        assertEquals("erogati 5,4 • recuperati 1,1 • 11,9 kWh/100 km", m.batteryDetail)
        assertEquals("0,50", m.fuel)
        assertEquals("1,4 L/100 km • stima dal MAF", m.fuelDetail)
        assertEquals("1,30", m.saved)
        assertEquals("in ibrido sarebbero serviti 1,80 L (5,0 L/100 km)", m.savedDetail)
    }

    @Test
    fun withoutTripInProgress_showsLastCompletedOne() {
        val older = trip.copy(startMs = start - 86_400_000L, endMs = start - 86_000_000L)
        val m = TripPageModel.of(TripLog(completed = listOf(trip, older)), trip.endMs + 3_600_000L, utc)

        assertEquals(TripPageModel.Status.LAST, m.status)
        assertEquals("ULTIMO VIAGGIO", m.chip)
        assertEquals("07/11 09:46–10:33 • 47 min", m.period)
        assertEquals("36,0", m.distance)
    }

    @Test
    fun staleTripInProgress_isShownAsConcluded() {
        val m = TripPageModel.of(TripLog(current = trip), trip.endMs + 6 * 60_000L, utc)
        assertEquals(TripPageModel.Status.LAST, m.status)
    }

    @Test
    fun longTrip_durationInHours() {
        val long = trip.copy(endMs = start + 65 * 60_000L)
        assertEquals("dalle 09:46 • 1 h 05 min", TripPageModel.of(TripLog(current = long), long.endMs, utc).period)
    }

    @Test
    fun tripJustStarted_barIsEmpty() {
        // Subito dopo l'azzeramento: 0 km, la barra non deve sembrare tutta "termico"
        val fresh = trip.copy(
            startMs = trip.endMs, speedDistanceKm = 0.0, evDistanceKm = 0.0, fuelLiters = 0.0,
            startDischargedKwh = 1005.4f, startChargedKwh = 901.1f, startSocPct = 16.5f
        )
        val m = TripPageModel.of(TripLog(current = fresh), fresh.endMs, utc)
        assertEquals(0f, m.evShare, 0f)
        assertEquals(0f, m.iceShare, 0f)
        assertEquals("0,0", m.distance)
        assertEquals("erogati 0,0 • recuperati 0,0", m.batteryDetail)
        assertEquals("stima dal MAF", m.fuelDetail)
        assertEquals("in ibrido sarebbero serviti 0,00 L (5,0 L/100 km)", m.savedDetail)
    }

    @Test
    fun emptyLog() {
        val m = TripPageModel.of(TripLog(), start, utc)
        assertEquals(TripPageModel.Status.NONE, m.status)
        assertEquals("NESSUN VIAGGIO", m.chip)
        assertEquals("–", m.saved)
        assertEquals("", m.savedDetail)
    }
}
