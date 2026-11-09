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
        soc: Float = 80f,
        kw: Float = 0.6f
    ) = NiroTelemetryState(
        vehicleSpeedKmh = speed,
        iceRpm = rpm,
        smoothedFuelLh = fuelLh,
        cumulativeDischargedKwh = discharged,
        cumulativeChargedKwh = charged,
        socBms = soc,
        hvPowerKw = kw
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
    fun persistAndShortStop_keepTheSameTrip() {
        val store = MemoryStore()
        val r = recorder(store)
        r.drive(36) { state(speed = 100) }
        val start = r.snapshot.current!!.startMs

        // Polling fermato (Android Auto scollegato): il viaggio viene solo salvato, non chiuso
        r.persist()
        assertEquals(start, store.log.current!!.startMs)
        assertTrue(store.log.completed.isEmpty())

        // Sosta di 3 minuti e ripartenza: stesso viaggio, senza integrare la pausa
        now += 3 * 60_000L
        r.onSample(state(speed = 50), null)
        assertEquals(start, r.snapshot.current!!.startMs)
        assertTrue(r.snapshot.completed.isEmpty())
        assertEquals(1.0, r.snapshot.current!!.speedDistanceKm, 1e-9)
    }

    @Test
    fun evOnlyDriving_measuredOnlyWithEngineOffAndBatteryAboveReserve() {
        val r = recorder()
        r.drive(36) { state(speed = 100, rpm = 0, soc = 80f, kw = 18f) }
        val ev = r.snapshot.current!!
        assertEquals(1.0, ev.evOnlyKm, 1e-9)
        assertEquals(0.18, ev.evOnlyKwh, 1e-9) // 18 kW per 36 s

        // Batteria in riserva: il termico spento è ibrido, non guida elettrica
        now += TripRecorder.TRIP_GAP_MS + 1
        r.drive(36) { state(speed = 100, rpm = 0, soc = 16f, kw = 18f) }
        val reserve = r.snapshot.current!!
        assertEquals(1.0, reserve.evDistanceKm, 1e-9)
        assertEquals(0.0, reserve.evOnlyKm, 1e-9)

        // Termico acceso: benzina, nessuna misura elettrica
        now += TripRecorder.TRIP_GAP_MS + 1
        r.drive(36) { state(speed = 100, rpm = 2000, fuelLh = 6f, soc = 80f, kw = 5f) }
        assertEquals(0.0, r.snapshot.current!!.evOnlyKm, 1e-9)
    }

    @Test
    fun counter_keepsTripsAcrossStopsAndRestarts() {
        val store = MemoryStore()
        val r = recorder(store)
        r.drive(36) { state(speed = 100) }
        now += 3_600_000L // sosta di un'ora: il viaggio si chiude, il totalizzatore resta
        r.drive(72) { state(speed = 100) }

        assertEquals(1, r.snapshot.completed.size)
        assertEquals(2, r.snapshot.counter.trips)
        assertEquals(3.0, r.snapshot.counter.distanceKm, 1e-9)

        // Dopo un riavvio dell'app (e un'altra sosta lunga) il totalizzatore è ancora lì
        r.persist()
        now += 3_600_000L
        val restarted = recorder(store)
        assertEquals(2, restarted.snapshot.counter.trips)
        assertEquals(3.0, restarted.snapshot.counter.distanceKm, 1e-9)
    }

    @Test
    fun resetCounter_closesTripKeepsHistoryAndStartsFromZero() {
        val store = MemoryStore()
        val events = mutableListOf<String>()
        val r = TripRecorder(store, clock = { now }, onEvent = { events += it })
        r.drive(36) { state(speed = 100) }
        now += 3_600_000L
        r.drive(36) { state(speed = 100) }

        r.resetCounter()
        // Eventi: iniziato, chiuso (sosta), iniziato, azzeramento
        assertEquals("Totalizzatore azzerato a mano: 2,0 km, 0,0 kWh netti, 0,00 L, risparmiati 0,10 L", events[3])
        assertNull(r.snapshot.current)
        assertEquals(2, store.log.completed.size) // l'elenco dei viaggi resta
        assertEquals(TripTotals.ZERO, store.log.counter)
        assertEquals(now, store.log.counterSinceMs)

        // Il polling prosegue: nuovo viaggio, l'unico nel totalizzatore
        now += 1000
        r.drive(36) { state(speed = 100) }
        assertEquals(1, r.snapshot.counter.trips)
        assertEquals(1.0, r.snapshot.counter.distanceKm, 1e-9)
    }

    @Test
    fun legacyLog_counterStartsFromAllTripsAndEvTripsMeasureConsumption() {
        val legacy = JSONObject(
            """
            {"version":1,"completed":[
              {"startMs":1000,"endMs":2000,"speedDistanceKm":20.0,"evDistanceKm":20.0,"startSocPct":90,"endSocPct":63,
               "startDischargedKwh":1000,"endDischargedKwh":1003.5,"startChargedKwh":900,"endChargedKwh":900.5,"fuelLiters":0.0},
              {"startMs":3000,"endMs":4000,"speedDistanceKm":100.0,"evDistanceKm":40.0,"startSocPct":63,"endSocPct":63,
               "startDischargedKwh":1003.5,"endDischargedKwh":1013.5,"startChargedKwh":900.5,"endChargedKwh":910.5,"fuelLiters":4.0}
            ]}
            """.trimIndent()
        )
        val log = TripLog.fromJson(legacy)

        // Il viaggio senza benzina vale come misura del consumo elettrico, quello ibrido no
        assertEquals(100.0, log.completed[1].distanceKm, 1e-9)
        assertEquals(0.0, log.completed[1].evOnlyKm, 1e-9)
        assertEquals(20.0, log.completed[0].evOnlyKm, 1e-9)
        assertEquals(3.0, log.completed[0].evOnlyKwh, 1e-9)

        assertEquals(2, log.counter.trips)
        assertEquals(120.0, log.counter.distanceKm, 1e-9)
        assertEquals(1000L, log.counter.firstStartMs)
        assertEquals(log, TripLog.fromJson(JSONObject(log.toJson().toString())))
    }

    @Test
    fun fuelSavings_fromMeasuredConsumptions() {
        // 20 km in elettrico con 3 kWh, poi 100 km in ibrido con 4 L e batteria in pari
        val t = TripTotals(
            distanceKm = 120.0, batteryOutKwh = 13.5, batteryInKwh = 10.5, fuelLiters = 4.0,
            evOnlyKm = 20.0, evOnlyKwh = 3.0, trips = 2
        )
        assertEquals(15.0, t.evKwhPer100Km, 1e-9) // (3 kWh + 3 kWh iniziali) / (20 km + 20 km)
        assertEquals(20.0, t.electricKm, 1e-9) // 3 kWh netti a 15 kWh/100 km
        assertEquals(100.0, t.hybridKm, 1e-9)
        assertTrue(t.hybridMeasured)
        assertEquals(6.5 / 150.0 * 100.0, t.hybridLitersPer100Km, 1e-9) // (4 L + 2,5 L iniziali) / (100 + 50 km)
        assertEquals(1.2, t.savedFuelLiters, 1e-9) // i 20 km elettrici a 4,33 L/100 km

        // Termico mai acceso: vale il riferimento di 5,0 L/100 km
        val ev = TripTotals(distanceKm = 36.0, batteryOutKwh = 5.4, batteryInKwh = 1.1, evOnlyKm = 36.0, evOnlyKwh = 4.3)
        assertEquals(FuelSavings.DEFAULT_HYBRID_L_PER_100KM, ev.hybridLitersPer100Km, 1e-9)
        assertEquals(1.8, ev.savedFuelLiters, 1e-9)

        // Con centinaia di km a benzina conta quasi solo la misura (3,5 L/100 km)
        val long = TripTotals(distanceKm = 1000.0, fuelLiters = 35.0, batteryOutKwh = 100.0, batteryInKwh = 100.0)
        assertEquals(3.57, long.hybridLitersPer100Km, 0.01)

        // Più benzina del riferimento: nessun risparmio, mai negativo
        assertEquals(0.0, FuelSavings.savedLiters(36.0, 2.5, 5.0), 1e-9)
    }

    @Test
    fun events_atTripStartAndClose() {
        val events = mutableListOf<String>()
        val r = TripRecorder(MemoryStore(), clock = { now }, onEvent = { events += it })
        r.drive(36) { state(speed = 100, soc = 55f) }
        now += TripRecorder.TRIP_GAP_MS + 1
        r.onSample(state(speed = 0), null)

        assertEquals("Viaggio iniziato (SoC 55,0 %)", events[0])
        assertEquals("Viaggio chiuso: 1,0 km (EV 1,0), 0,0 kWh netti, 0,00 L", events[1])
        assertEquals(3, events.size) // e subito dopo inizia il viaggio successivo
    }

    @Test
    fun normalized_movesStaleCurrentTripToCompleted() {
        val trip = TripRecord(
            startMs = now - 3_600_000L, endMs = now - 60_000L, speedDistanceKm = 20.0,
            startSocPct = 80f, endSocPct = 60f, startDischargedKwh = 1000f, endDischargedKwh = 1003f,
            startChargedKwh = 900f, endChargedKwh = 900.5f
        )
        val log = TripLog(current = trip)

        assertEquals(log, log.normalized(now)) // fermo da 1 minuto: ancora in corso

        val later = now + TripRecorder.TRIP_GAP_MS
        assertEquals(TripLog(completed = listOf(trip), counterBase = TripTotals.of(trip)), log.normalized(later))

        // Un viaggio senza movimento né consumi sparisce invece di finire tra i conclusi
        val trivial = TripLog(current = trip.copy(speedDistanceKm = 0.0, endDischargedKwh = 1000f))
        assertEquals(TripLog(), trivial.normalized(later))
    }

    @Test
    fun closedTrip_isArchivedOnlyIfMeaningful() {
        val store = MemoryStore()
        val r = recorder(store)
        r.drive(36) { state(speed = 100) }

        // Quadro acceso da fermo dopo la pausa: chiude il primo viaggio, salvato subito
        now += TripRecorder.TRIP_GAP_MS + 1
        r.drive(10) { state(speed = 0) }
        assertEquals(1, store.log.completed.size)
        assertNotNull(r.snapshot.current)

        // Il viaggio da fermo, senza consumi, alla chiusura non viene archiviato
        now += TripRecorder.TRIP_GAP_MS + 1
        r.onSample(state(speed = 0), null)
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
        val lines = TripRecord.toCsv(listOf(trip), FuelSavings.DEFAULT_HYBRID_L_PER_100KM).trim().lines()
        assertEquals(2, lines.size)
        val cols = lines[1].split(";")
        assertEquals("60", cols[2])
        assertEquals("100,0", cols[3])
        assertEquals("8,0", cols[7])
        assertEquals("8,0", cols[8])
        assertEquals("3,50", cols[9])
        assertEquals("3,5", cols[10])
        assertEquals("1,50", cols[15]) // 100 km a 5,0 L/100 km = 5,00 L, meno 3,50 L usati

        // Con la media in ibrido misurata dal totalizzatore
        assertEquals("0,50", TripRecord.toCsv(listOf(trip), 4.0).trim().lines()[1].split(";")[15])
    }
}
