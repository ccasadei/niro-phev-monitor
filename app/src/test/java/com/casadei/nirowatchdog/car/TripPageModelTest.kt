package com.casadei.nirowatchdog.car

import com.casadei.nirowatchdog.obd.TripLog
import com.casadei.nirowatchdog.obd.TripRecord
import com.casadei.nirowatchdog.obd.TripTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TripPageModelTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 7 novembre 2026, 09:46 UTC */
    private val start = 1_794_044_760_000L

    /** 8 novembre 2026, 10:00 UTC */
    private val day2 = start + 86_400_000L + 14 * 60_000L

    /** 20 km in elettrico in 30 minuti: 3 kWh netti, niente benzina */
    private val evTrip = TripRecord(
        startMs = start, endMs = start + 30 * 60_000L, speedDistanceKm = 20.0, evDistanceKm = 20.0,
        startSocPct = 90f, endSocPct = 63f, startDischargedKwh = 1000f, endDischargedKwh = 1003.5f,
        startChargedKwh = 900f, endChargedKwh = 900.5f, evOnlyKm = 20.0, evOnlyKwh = 3.0
    )

    /** Il giorno dopo 100 km in ibrido in un'ora: 4 L, batteria in pari */
    private val hybridTrip = TripRecord(
        startMs = day2, endMs = day2 + 60 * 60_000L, speedDistanceKm = 100.0, evDistanceKm = 40.0,
        startSocPct = 63f, endSocPct = 63f, startDischargedKwh = 1003.5f, endDischargedKwh = 1013.5f,
        startChargedKwh = 900.5f, endChargedKwh = 910.5f, fuelLiters = 4.0
    )

    @Test
    fun counterWhileDriving_sumsAllTripsSinceReset() {
        val log = TripLog(completed = listOf(evTrip), current = hybridTrip, counterBase = TripTotals.of(evTrip))
        val m = TripPageModel.of(log, hybridTrip.endMs + 1_000L, utc)

        assertEquals(TripPageModel.Status.DRIVING, m.status)
        assertTrue(m.canReset)
        assertEquals("IN VIAGGIO", m.chip)
        assertEquals("dal 07/11 09:46 • 2 viaggi • 1 h 30 min", m.period)
        assertEquals("120,0", m.distance)
        assertEquals(20f / 120f, m.electricShare, 1e-4f)
        assertEquals(100f / 120f, m.hybridShare, 1e-4f)
        assertEquals("elettrico ≈ 20,0 km", m.electricKm)
        assertEquals("ibrido ≈ 100,0 km", m.hybridKm)
        assertEquals("3,0", m.batteryNet)
        assertEquals("erogati 13,5 • recuperati 10,5 • EV 15,0 kWh/100 km", m.batteryDetail)
        assertEquals("4,00", m.fuel)
        assertEquals("media 3,3 L/100 km • ibrido 4,3 L/100 km (misurato)", m.fuelDetail)
        assertEquals("1,20", m.saved)
        assertEquals("in solo ibrido sarebbero serviti 5,20 L (4,3 L/100 km)", m.savedDetail)
    }

    @Test
    fun betweenTrips_counterIsKept() {
        val log = TripLog(
            completed = listOf(hybridTrip, evTrip),
            counterBase = TripTotals.of(evTrip) + TripTotals.of(hybridTrip)
        )
        val m = TripPageModel.of(log, hybridTrip.endMs + 3 * 3_600_000L, utc)
        assertEquals(TripPageModel.Status.PAUSED, m.status)
        assertEquals("IN PAUSA", m.chip)
        assertEquals("120,0", m.distance)
        assertEquals("1,20", m.saved)
    }

    @Test
    fun staleTripInProgress_isAddedToCounterAndShownPaused() {
        val log = TripLog(completed = listOf(evTrip), current = hybridTrip, counterBase = TripTotals.of(evTrip))
        val m = TripPageModel.of(log, hybridTrip.endMs + 6 * 60_000L, utc)
        assertEquals(TripPageModel.Status.PAUSED, m.status)
        assertEquals("120,0", m.distance)
    }

    @Test
    fun engineNeverOn_usesInitialHybridConsumption() {
        val m = TripPageModel.of(TripLog(current = evTrip), evTrip.endMs + 1_000L, utc)
        assertEquals("dal 07/11 09:46 • 1 viaggio • 30 min", m.period)
        assertEquals("elettrico ≈ 20,0 km", m.electricKm)
        assertEquals("ibrido ≈ 0,0 km", m.hybridKm)
        assertEquals("media 0,0 L/100 km • ibrido 5,0 L/100 km (valore iniziale)", m.fuelDetail)
        assertEquals("1,00", m.saved)
        assertEquals("in solo ibrido sarebbero serviti 1,00 L (5,0 L/100 km)", m.savedDetail)
    }

    @Test
    fun afterReset_emptyUntilNextTripThenFromZero() {
        val resetAt = day2 + 2 * 3_600_000L // 08/11 12:00
        val reset = TripLog(completed = listOf(hybridTrip, evTrip), counterSinceMs = resetAt)
        val empty = TripPageModel.of(reset, resetAt + 60_000L, utc)
        assertEquals(TripPageModel.Status.EMPTY, empty.status)
        assertFalse(empty.canReset)
        assertEquals("NESSUN DATO", empty.chip)
        assertEquals("azzerato il 08/11 12:00 • riparte con il prossimo viaggio", empty.period)
        assertEquals("–", empty.saved)

        // Primo campione del viaggio successivo: 0 km, la barra resta vuota
        val t = resetAt + 5 * 60_000L
        val fresh = TripRecord(
            startMs = t, endMs = t, startSocPct = 63f, endSocPct = 63f, startDischargedKwh = 1013.5f,
            endDischargedKwh = 1013.5f, startChargedKwh = 910.5f, endChargedKwh = 910.5f
        )
        val m = TripPageModel.of(reset.copy(current = fresh), t, utc)
        assertEquals(TripPageModel.Status.DRIVING, m.status)
        assertEquals("dal 08/11 12:05 • 1 viaggio • 0 min", m.period)
        assertEquals("0,0", m.distance)
        assertEquals(0f, m.electricShare, 0f)
        assertEquals(0f, m.hybridShare, 0f)
        assertEquals("ibrido 5,0 L/100 km (valore iniziale)", m.fuelDetail)
        assertEquals("0,00", m.saved)
    }

    @Test
    fun emptyLog() {
        val m = TripPageModel.of(TripLog(), start, utc)
        assertEquals(TripPageModel.Status.EMPTY, m.status)
        assertEquals("NESSUN DATO", m.chip)
        assertEquals("si compila da solo con Android Auto e dongle OBD collegati", m.period)
        assertEquals("", m.savedDetail)
    }
}
