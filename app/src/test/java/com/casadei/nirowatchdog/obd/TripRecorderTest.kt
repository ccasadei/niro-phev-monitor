package com.casadei.nirowatchdog.obd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TripRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryStore(var log: TripLog = TripLog()) : TripStore {
        var saves = 0
        override fun load() = log
        override fun save(log: TripLog) {
            this.log = log
            saves++
        }
    }

    private var now = 1_000_000_000L

    private fun recorder(store: TripStore = MemoryStore()) = TripRecorder(store, clock = { now })

    private fun state(
        speed: Int = 0,
        rpm: Int = 0,
        fuelLh: Float = 0f,
        discharged: Float = 1000f,
        charged: Float = 900f,
        soc: Float = 80f
    ) = NiroTelemetryState(
        vehicleSpeedKmh = speed,
        iceRpm = rpm,
        smoothedFuelLh = fuelLh,
        cumulativeDischargedKwh = discharged,
        cumulativeChargedKwh = charged,
        socBms = soc
    )

    /** Un campione al secondo per [seconds] secondi dopo il primo */
    private fun TripRecorder.drive(seconds: Int, sample: () -> NiroTelemetryState) {
        onSample(sample(), null)
        repeat(seconds) {
            now += 1000
            onSample(sample(), null)
        }
    }

    @Test
    fun hybridDriving_integratesDistanceAndFuel() {
        val r = recorder()
        r.drive(36) { state(speed = 100, rpm = 2000, fuelLh = 6f) }

        val cur = r.snapshot.current!!
        assertEquals(1.0, cur.speedDistanceKm, 1e-9)
        assertEquals(0.0, cur.evDistanceKm, 1e-9)
        assertEquals(0.06, cur.fuelLiters, 1e-9)
    }

    @Test
    fun evDriving_countsEvDistanceWithoutFuel() {
        val r = recorder()
        r.drive(50) { state(speed = 72, rpm = 0, fuelLh = 0f) }

        val cur = r.snapshot.current!!
        assertEquals(1.0, cur.evDistanceKm, 1e-9)
        assertEquals(0.0, cur.fuelLiters, 1e-9)
    }

    @Test
    fun stepLongerThanLimit_isNotIntegrated() {
        val r = recorder()
        r.onSample(state(speed = 100), null)
        now += TripRecorder.MAX_INTEGRATION_STEP_MS + 1
        r.onSample(state(speed = 100), null)

        assertEquals(0.0, r.snapshot.current!!.speedDistanceKm, 1e-9)
    }

    @Test
    fun batteryEnergy_fromBmsCounters() {
        val r = recorder()
        r.onSample(state(discharged = 1000.0f, charged = 900.0f, soc = 80f), null)
        now += 1000
        r.onSample(state(discharged = 1005.5f, charged = 901.2f, soc = 75f), null)

        val cur = r.snapshot.current!!
        assertEquals(5.5f, cur.batteryOutKwh, 0.01f)
        assertEquals(1.2f, cur.batteryInKwh, 0.01f)
        assertEquals(4.3f, cur.netBatteryKwh, 0.01f)
        assertEquals(80f, cur.startSocPct, 0.001f)
        assertEquals(75f, cur.endSocPct, 0.001f)
    }

    @Test
    fun odometer_isUsedForLongTripsOnly() {
        val r = recorder()
        r.onSample(state(speed = 50), null) // odometro non ancora letto
        now += 1000
        r.onSample(state(speed = 50), 100000)
        now += 1000
        r.onSample(state(speed = 50), 100274)

        val long = r.snapshot.current!!
        assertEquals(100000, long.startOdometerKm)
        assertEquals(100274, long.endOdometerKm)
        assertEquals(274.0, long.distanceKm, 1e-9)

        // Sotto i 10 km la risoluzione di 1 km è troppo grossolana: vale la velocità integrata
        val short = long.copy(endOdometerKm = 100003)
        assertEquals(short.speedDistanceKm, short.distanceKm, 1e-9)
    }

    @Test
    fun pauseLongerThanGap_closesTripAndStartsNewOne() {
        val r = recorder()
        r.drive(36) { state(speed = 100) }
        val firstStart = r.snapshot.current!!.startMs

        now += TripRecorder.TRIP_GAP_MS + 1
        r.onSample(state(speed = 30), null)

        assertEquals(1, r.snapshot.completed.size)
        assertEquals(firstStart, r.snapshot.completed[0].startMs)
        assertEquals(now, r.snapshot.current!!.startMs)
    }

    @Test
    fun finish_archivesMeaningfulTripAndDropsTrivialOne() {
        val store = MemoryStore()
        val r = recorder(store)
        r.drive(36) { state(speed = 100) }
        r.finish()
        assertNull(store.log.current)
        assertEquals(1, store.log.completed.size)

        // Quadro acceso da fermo, nessun consumo: non viene archiviato
        now += TripRecorder.TRIP_GAP_MS + 1
        r.drive(10) { state(speed = 0) }
        r.finish()
        assertEquals(1, store.log.completed.size)
    }

    @Test
    fun currentTrip_isSavedPeriodically() {
        val store = MemoryStore()
        val r = recorder(store)
        r.drive(65) { state(speed = 50) }

        assertTrue("salvataggi: ${store.saves}", store.saves >= 2)
        assertNotNull(store.log.current)
    }

    @Test
    fun afterRestart_recentTripIsResumedAndStaleTripIsArchived() {
        val trip = TripRecord(
            startMs = now - 3_600_000L,
            endMs = now - 60_000L,
            speedDistanceKm = 50.0,
            startSocPct = 80f,
            endSocPct = 60f,
            startDischargedKwh = 1000f,
            endDischargedKwh = 1003f,
            startChargedKwh = 900f,
            endChargedKwh = 900.5f
        )

        val resumed = recorder(MemoryStore(TripLog(current = trip)))
        now += 1000
        resumed.onSample(state(speed = 50, discharged = 1003.2f), null)
        assertEquals(trip.startMs, resumed.snapshot.current!!.startMs)
        assertEquals(50.0, resumed.snapshot.current!!.speedDistanceKm, 1e-9) // nessuna integrazione sulla pausa

        val stale = recorder(MemoryStore(TripLog(current = trip.copy(endMs = now - TripRecorder.TRIP_GAP_MS - 1))))
        assertNull(stale.snapshot.current)
        assertEquals(1, stale.snapshot.completed.size)
    }

    @Test
    fun tripLog_roundTripsThroughJsonAndFile() {
        val log = TripLog(
            completed = listOf(
                TripRecord(
                    startMs = 1L, endMs = 2L, startOdometerKm = 100000, endOdometerKm = 100274,
                    speedDistanceKm = 271.3, evDistanceKm = 120.4, startSocPct = 85.5f, endSocPct = 16f,
                    startDischargedKwh = 1000.1f, endDischargedKwh = 1025.2f,
                    startChargedKwh = 900.3f, endChargedKwh = 907f, fuelLiters = 9.8
                )
            ),
            current = TripRecord(
                startMs = 3L, endMs = 4L, startSocPct = 16f, endSocPct = 15.5f,
                startDischargedKwh = 1025.2f, endDischargedKwh = 1025.4f,
                startChargedKwh = 907f, endChargedKwh = 907f
            )
        )
        assertEquals(log, TripLog.fromJson(JSONObject(log.toJson().toString())))

        val store = FileTripStore(File(tmp.root, "trips.json"))
        store.save(log)
        assertEquals(log, store.load())
    }

    @Test
    fun corruptedFile_isSetAsideAndLogStartsEmpty() {
        val file = File(tmp.root, "trips.json")
        file.writeText("{ non è json")

        assertEquals(TripLog(), FileTripStore(file).load())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("trips.json.corrotto-") })
    }

    @Test
    fun csv_usesSemicolonsAndDecimalComma() {
        val trip = TripRecord(
            startMs = 0L, endMs = 3_600_000L, startOdometerKm = 100000, endOdometerKm = 100100,
            speedDistanceKm = 99.0, evDistanceKm = 40.5, startSocPct = 80f, endSocPct = 20f,
            startDischargedKwh = 1000f, endDischargedKwh = 1010f,
            startChargedKwh = 900f, endChargedKwh = 902f, fuelLiters = 3.5
        )
        val lines = TripRecord.toCsv(listOf(trip)).trim().lines()
        assertEquals(2, lines.size)
        val cols = lines[1].split(";")
        assertEquals("60", cols[2])
        assertEquals("100,0", cols[3])
        assertEquals("8,0", cols[7])
        assertEquals("8,0", cols[8])
        assertEquals("3,50", cols[9])
        assertEquals("3,5", cols[10])
    }
}
