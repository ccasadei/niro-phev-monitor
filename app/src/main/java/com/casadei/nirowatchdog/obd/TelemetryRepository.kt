package com.casadei.nirowatchdog.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Repository centrale condiviso tra l'interfaccia Android Auto (10.25") e l'Activity Smartphone.
 * Gestisce:
 * 1) Simulatore Demo realistico per testare la UI a scrivania o in auto senza dongle.
 * 2) Polling OBD-II / UDS in tempo reale tramite Bluetooth SPP (ELM327 / OBDLink / Android-Vlink),
 *    con riconnessione automatica e watchdog sul dongle e sul BMS.
 * 3) Scanner / Sniffer UDS Attivo per la mappatura completa dei PID della Kia Niro SG2 PHEV 2024.
 *
 * Una sola attività (demo, live o scansione) è attiva alla volta: la nuova parte solo dopo che la
 * precedente ha chiuso il socket, perché il dongle SPP accetta una sola connessione.
 */
object TelemetryRepository {

    /** Chi ha avviato il polling live: quello aperto da Android Auto si chiude a fine sessione auto. */
    enum class LiveOwner { PHONE, CAR }

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val OBD_NAME_KEYWORDS = listOf("vlink", "obd", "elm", "vgate", "konnwei", "viecar", "carista", "ios-vlink")

    /** Periodo target di un ciclo di polling live: la pausa si adatta al tempo speso sul bus. */
    private const val POLL_PERIOD_MS = 800L
    private const val MIN_POLL_GAP_MS = 120L
    /** Cicli consecutivi senza risposta BMS valida oltre i quali si riconnette (quadro spento o bus muto). */
    private const val MAX_EMPTY_BMS_CYCLES = 8
    private const val RECONNECT_BACKOFF_START_MS = 1_000L
    private const val RECONNECT_BACKOFF_MAX_MS = 30_000L
    /** Tentativi consecutivi senza dati dopo i quali il live avviato dal telefono va in pausa (~3-5 minuti) */
    private const val MAX_PHONE_RECONNECT_ATTEMPTS = 6
    /**
     * Come sopra per il live avviato da Android Auto (~6-8 minuti): con l'auto accesa il BMS risponde, quindi
     * tanti fallimenti indicano un dongle scollegato o assente. Si riprova riaprendo Niro Monitor sull'auto.
     */
    private const val MAX_CAR_RECONNECT_ATTEMPTS = 12
    private const val TRIPS_FILE_NAME = "trips.json"
    /** Margine prima di chiudere l'OBD aperto da Android Auto: evita di riconnettere se la sessione si ricrea subito. */
    private const val CAR_SESSION_STOP_GRACE_MS = 20_000L

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        _telemetry.update { it.copy(connectionStatus = "Errore interno OBD: ${e.javaClass.simpleName}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private val _telemetry = MutableStateFlow(NiroTelemetryState())
    val telemetry: StateFlow<NiroTelemetryState> = _telemetry.asStateFlow()

    private val _discoveryDump = MutableStateFlow<List<RawUdsDumpEntry>>(emptyList())
    val discoveryDump: StateFlow<List<RawUdsDumpEntry>> = _discoveryDump.asStateFlow()

    private val _trips = MutableStateFlow(TripLog())
    /** Registro viaggi (conclusi e in corso); vuoto finché non viene chiamato [init]. */
    val trips: StateFlow<TripLog> = _trips.asStateFlow()

    @Volatile
    private var tripRecorder: TripRecorder? = null

    private val jobLock = Any()
    private var activeJob: Job? = null
    private var liveOwner: LiveOwner? = null
    private var carStopJob: Job? = null
    private val activeSocket = AtomicReference<BluetoothSocket?>(null)
    /** Garantisce un solo socket verso il dongle anche se la catena di join tra job viene interrotta */
    private val dongleMutex = Mutex()

    /** true quando la pagina 96 Celle è visibile: le tensioni delle celle vengono lette più spesso. */
    @Volatile
    private var cellDetailWanted = false

    private var simTick = 0.0

    /**
     * Individua il dongle OBD-II tra i dispositivi Bluetooth associati
     * riconoscendolo dal nome (es. "Android-Vlink", ELM327, OBDLink, Vgate, Konnwei).
     * Con [fallbackToFirst] restituisce il primo dispositivo associato se nessuno sembra un dongle OBD.
     */
    @SuppressLint("MissingPermission")
    fun findPreferredObdDevice(
        devices: Collection<BluetoothDevice>,
        fallbackToFirst: Boolean = true
    ): BluetoothDevice? {
        if (devices.isEmpty()) return null
        return devices.firstOrNull { dev ->
            val name = (safeName(dev) ?: "").lowercase(Locale.ROOT)
            OBD_NAME_KEYWORDS.any { kw -> name.contains(kw) }
        } ?: if (fallbackToFirst) devices.firstOrNull() else null
    }

    /**
     * Prepara il registro viaggi leggendo il file salvato; da chiamare con un Context prima di usare il
     * repository (Activity e servizio Android Auto lo fanno in onCreate). Le chiamate successive non fanno nulla.
     */
    fun init(context: Context) {
        EventLog.init(context)
        if (tripRecorder != null) return
        synchronized(jobLock) {
            if (tripRecorder != null) return
            val file = File(context.applicationContext.filesDir, TRIPS_FILE_NAME)
            val recorder = TripRecorder(
                FileTripStore(file),
                onChange = { _trips.value = it },
                onEvent = EventLog::log
            )
            tripRecorder = recorder
            _trips.value = recorder.snapshot
        }
    }

    fun clearTrips() {
        tripRecorder?.clear()
    }

    /** Viaggi (compreso quello in corso) in CSV con separatore ';' e virgola decimale. */
    fun exportTripsCsv(): String {
        val log = _trips.value
        return TripRecord.toCsv(listOfNotNull(log.current) + log.completed)
    }

    /**
     * Richiamato automaticamente da Android Auto (NiroCarAppService) appena si collega, anche senza aprire
     * la schermata di Niro Monitor: se il polling live non è già attivo (connesso, in connessione o in
     * attesa di riconnessione) avvia il collegamento a Android-Vlink.
     */
    @SuppressLint("MissingPermission")
    fun autoConnectFromCarSession(context: Context) {
        synchronized(jobLock) {
            carStopJob?.cancel()
            carStopJob = null
            if (liveOwner != null && activeJob?.isActive == true) {
                // Un live avviato dal telefono passa all'auto: si fermerà con la fine della sessione Android Auto
                if (liveOwner != LiveOwner.CAR) EventLog.log("Android Auto: polling già attivo, ora segue l'auto")
                liveOwner = LiveOwner.CAR
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                EventLog.log("Android Auto: permesso Bluetooth mancante, polling non avviato")
                setStatus("Permesso Bluetooth mancante: apri Niro Monitor sul telefono")
                return
            }
        }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bonded = try {
            btManager?.adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        // In auto niente ripiego sul "primo dispositivo associato": potrebbe essere l'autoradio o un auricolare
        val obdDevice = findPreferredObdDevice(bonded, fallbackToFirst = false)
        if (obdDevice != null) {
            connectAndStartLiveObd(obdDevice, LiveOwner.CAR)
        } else {
            EventLog.log("Android Auto: nessun dongle OBD associato, polling non avviato")
            setStatus("Nessun dongle OBD associato (Android-Vlink)")
        }
    }

    /**
     * Da chiamare quando Android Auto si scollega (distruzione del servizio): salva subito il viaggio in corso,
     * perché poco dopo Android può congelare o chiudere l'app, e se il polling era stato aperto dall'auto lo
     * ferma dopo [CAR_SESSION_STOP_GRACE_MS], così il telefono non interroga il dongle a vettura spenta.
     * Il viaggio non viene chiuso: se Android Auto si ricollega entro 5 minuti prosegue lo stesso viaggio.
     */
    fun onCarSessionEnded() {
        tripRecorder?.persist()
        synchronized(jobLock) {
            if (liveOwner != LiveOwner.CAR) return
            EventLog.log("Android Auto scollegato: viaggio salvato, stop del polling tra ${CAR_SESSION_STOP_GRACE_MS / 1000} s")
            carStopJob?.cancel()
            carStopJob = scope.launch {
                delay(CAR_SESSION_STOP_GRACE_MS)
                val socket = synchronized(jobLock) {
                    if (liveOwner != LiveOwner.CAR) return@launch
                    liveOwner = null
                    // Il job cancellato resta in activeJob: il prossimo launchExclusive ne attende la fine
                    activeJob?.cancel()
                    setStatus("OBD in pausa (Android Auto disconnesso)")
                    activeSocket.getAndSet(null)
                }
                socket?.closeQuietly()
                EventLog.log("OBD: polling fermato (Android Auto scollegato)")
            }
        }
    }

    /** Indica se la pagina 96 Celle è in primo piano su Android Auto (aumenta la frequenza di lettura celle). */
    fun setCellDetailWanted(wanted: Boolean) {
        cellDetailWanted = wanted
    }

    /** Messaggio di stato dall'interfaccia (es. permesso Bluetooth negato), mostrato al posto dello stato OBD. */
    fun reportStatus(status: String) {
        setStatus(status)
    }

    /**
     * Avvia il simulatore realistico della Kia Niro SG2 PHEV 2024.
     */
    fun startDemoSimulator() {
        EventLog.log("Simulatore demo avviato")
        launchExclusive(owner = null) {
            _telemetry.update {
                it.copy(isDemoMode = true, connectionStatus = "Simulatore Demo SG2 PHEV")
            }
            while (isActive) {
                simTick += 0.25
                _telemetry.update { current -> nextDemoState(current) }
                delay(1200L)
            }
        }
    }

    private fun nextDemoState(current: NiroTelemetryState): NiroTelemetryState {
        val waveKw = (sin(simTick) * 24.0 + cos(simTick * 0.45) * 14.0).toFloat()
        val roundedKw = (waveKw * 10f).roundToInt() / 10f
        val hvVolt = (386.0f - roundedKw * 0.18f)
        val hvAmp = ((roundedKw * 1000f) / hvVolt * 10f).roundToInt() / 10f

        val newBmsSoc = (current.socBms - (roundedKw * 0.002f)).coerceIn(15.0f, 94.0f)
        val iceActive = roundedKw > 24f || newBmsSoc <= NiroTelemetryState.HEV_FLOOR_BMS_SOC
        val simSpeed = (52 + (sin(simTick * 0.35) * 36).toInt()).coerceIn(0, 115)
        val simRpm = if (iceActive) (1650 + (simSpeed * 12)).coerceIn(1300, 3400) else 0
        val history = (current.powerHistoryKw + roundedKw).takeLast(NiroTelemetryState.POWER_HISTORY_SIZE)

        val baseState = current.copy(
            socBms = (newBmsSoc * 10f).roundToInt() / 10f,
            socDisplay = ((newBmsSoc + 5.0f).coerceIn(0f, 100f) * 10f).roundToInt() / 10f,
            hvVoltage = (hvVolt * 10f).roundToInt() / 10f,
            hvCurrent = hvAmp,
            hvPowerKw = roundedKw,
            vehicleSpeedKmh = simSpeed,
            iceRpm = simRpm,
            engineLoadPct = if (iceActive) 56 else 0,
            iceCoolantTemp = if (iceActive) 82 else 64,
            powerHistoryKw = history
        )
        return Sg2PidParser.recomputeFuelAndGear(baseState)
    }

    fun resetTripCounters() {
        _telemetry.update { cur ->
            cur.copy(
                powerHistoryKw = List(NiroTelemetryState.POWER_HISTORY_SIZE) { 0f },
                tripSamplesTotal = 0,
                tripSamplesEv = 0,
                evTripRatioPct = if (cur.isEvMode) 100 else 0
            )
        }
    }

    /**
     * Avvia la connessione continua Bluetooth OBD-II per popolare Android Auto in tempo reale.
     * In caso di errore o disconnessione riprova da solo con attesa crescente (1 s → 30 s).
     */
    fun connectAndStartLiveObd(device: BluetoothDevice, owner: LiveOwner = LiveOwner.PHONE) {
        EventLog.log("OBD: avvio polling verso ${safeName(device) ?: "dongle"} (${if (owner == LiveOwner.CAR) "Android Auto" else "telefono"})")
        launchExclusive(owner) {
            runLiveObdWithReconnect(device)
        }
    }

    /**
     * Esegue lo "Scanner / Sniffer UDS Attivo" su tutte le centraline della Kia Niro SG2 PHEV,
     * aggiorna immediatamente la telemetria live con i valori reali letti e poi prosegue in Live OBD.
     */
    fun runFullSg2DiscoveryScan(
        device: BluetoothDevice,
        onComplete: (String) -> Unit
    ) {
        EventLog.log("Scansione UDS avviata")
        launchExclusive(LiveOwner.PHONE) {
            val entries = mutableListOf<RawUdsDumpEntry>()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            try {
                setStatus("Scansione UDS SG2 in corso...")
                withElmLink(device) { link ->
                    for ((header, ecuName, pidCmd) in Sg2PidParser.SG2_DISCOVERY_PROBES) {
                        val (rawResp, payload) = link.queryRaw(header, pidCmd)
                        val payloadHex = payload.joinToString(" ") { "%02X".format(it) }

                        // Aggiorna anche la telemetria live mentre esegue lo sniffer!
                        if (payload.isNotEmpty()) {
                            _telemetry.update { applyDiscoveryProbe(it, header, pidCmd, payload) }
                        }

                        entries.add(
                            RawUdsDumpEntry(
                                timestampIso = sdf.format(Date()),
                                ecuHeaderTx = header,
                                ecuName = ecuName,
                                pidCommand = pidCmd,
                                rawResponseHex = rawResp.trim(),
                                payloadBytesHex = payloadHex,
                                byteCount = payload.size
                            )
                        )
                        _discoveryDump.value = entries.toList()
                    }
                }

                setStatus("Dump completato (${entries.size} PID) • Avvio Live OBD...")
                val report = buildJsonDumpReport(entries)
                withContext(Dispatchers.Main) {
                    onComplete(report)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                setStatus("Errore Scansione: ${e.localizedMessage ?: "Fallita"}")
            }
            // Dopo il dump prosegue automaticamente con il polling Live OBD per Android Auto!
            delay(400L)
            runLiveObdWithReconnect(device)
        }
    }

    private fun applyDiscoveryProbe(
        s: NiroTelemetryState,
        header: String,
        pidCmd: String,
        payload: ByteArray
    ): NiroTelemetryState = when {
        header == "7E4" -> when (pidCmd) {
            "220101" -> Sg2PidParser.applyBms220101(s, payload)
            "220102" -> Sg2PidParser.applyBmsCellBlock(s, 0x02, payload)
            "220103" -> Sg2PidParser.applyBmsCellBlock(s, 0x03, payload)
            "220104" -> Sg2PidParser.applyBmsCellBlock(s, 0x04, payload)
            "220105" -> Sg2PidParser.applyBms220105(s, payload)
            "220106" -> Sg2PidParser.applyBms220106(s, payload)
            else -> s
        }
        header == "7E0" -> Sg2PidParser.applyEnginePid(s, payload)
        header == "7C6" && pidCmd == "22B002" -> Sg2PidParser.applyCluster22B002(s, payload)
        header == "7C4" && pidCmd == "220101" -> Sg2PidParser.applyAdasCamera7C4(s, payload)
        else -> s
    }

    fun buildJsonDumpReport(entries: List<RawUdsDumpEntry>): String {
        val root = JSONObject()
        root.put("vehicle", "Kia Niro SG2 PHEV 2024 (11.1 kWh)")
        root.put("probesCount", entries.size)
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("timestamp", e.timestampIso)
            obj.put("ecuHeaderTx", e.ecuHeaderTx)
            obj.put("ecuName", e.ecuName)
            obj.put("pidCommand", e.pidCommand)
            obj.put("byteCount", e.byteCount)
            obj.put("payloadBytesHex", e.payloadBytesHex)
            obj.put("rawResponse", e.rawResponseHex)
            arr.put(obj)
        }
        root.put("results", arr)
        return root.toString(2)
    }

    /**
     * Sostituisce l'attività corrente (demo / live / scansione) con [block]. Il socket del job precedente
     * viene chiuso subito (sblocca connect()/read() bloccanti) e il nuovo job attende che il precedente
     * sia terminato prima di aprire una nuova connessione verso il dongle.
     */
    private fun launchExclusive(owner: LiveOwner?, block: suspend CoroutineScope.() -> Unit) {
        val previousSocket = synchronized(jobLock) {
            carStopJob?.cancel()
            carStopJob = null
            liveOwner = owner
            val previous = activeJob
            previous?.cancel()
            // Preso prima di lanciare il nuovo job, che altrimenti potrebbe aver già pubblicato il proprio socket
            val socketToClose = activeSocket.getAndSet(null)
            activeJob = scope.launch {
                previous?.join()
                block()
            }
            socketToClose
        }
        // Chiuso subito e non dentro il nuovo job, che potrebbe essere cancellato prima di partire:
        // sblocca connect()/read() del job precedente, che così rilascia il dongle (vedi dongleMutex)
        previousSocket?.closeQuietly()
    }

    private suspend fun runLiveObdWithReconnect(device: BluetoothDevice) {
        // Quando il polling si ferma (pausa, Android Auto scollegato, demo, scansione) il viaggio viene solo
        // salvato: si chiude da solo dopo una pausa senza dati, così una sosta breve non lo spezza in due
        try {
            reconnectLoop(device)
            tripRecorder?.persist()
        } catch (e: CancellationException) {
            tripRecorder?.persist()
            EventLog.log("OBD: polling interrotto, viaggio salvato")
            throw e
        }
    }

    private suspend fun reconnectLoop(device: BluetoothDevice) {
        val name = safeName(device) ?: device.address
        val logName = safeName(device) ?: "dongle"
        val myJob = currentCoroutineContext()[Job]
        var backoffMs = RECONNECT_BACKOFF_START_MS
        var failedAttempts = 0
        while (true) {
            setStatus("Connessione a $name...")
            try {
                withElmLink(device) { link ->
                    setStatus("OBD: $name")
                    EventLog.log("OBD: connesso a $logName")
                    pollLiveData(link, name) {
                        backoffMs = RECONNECT_BACKOFF_START_MS
                        failedAttempts = 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un IOException causato dalla chiusura del socket durante una cancellazione non deve sovrascrivere lo stato
                currentCoroutineContext().ensureActive()
                failedAttempts++
                EventLog.log("OBD: ${e.localizedMessage ?: "disconnesso"} (tentativo $failedAttempts)")
                // Il live non insiste all'infinito (auto spenta, dongle scollegato o fuori portata): quello del
                // telefono si ferma prima, quello di Android Auto concede più tentativi
                synchronized(jobLock) {
                    val owner = liveOwner
                    val maxAttempts = if (owner == LiveOwner.CAR) MAX_CAR_RECONNECT_ATTEMPTS else MAX_PHONE_RECONNECT_ATTEMPTS
                    if (owner != null && activeJob === myJob && failedAttempts >= maxAttempts) {
                        liveOwner = null
                        val hint = if (owner == LiveOwner.CAR) "riapri Niro Monitor sull'auto per riprovare" else "premi Avvia per riprovare"
                        setStatus("OBD in pausa ($name): nessun dato dopo $failedAttempts tentativi • $hint")
                        EventLog.log("OBD: in pausa dopo $failedAttempts tentativi senza dati")
                        return
                    }
                }
                setStatus("OBD in attesa ($name): ${e.localizedMessage ?: "Disconnesso"} • nuovo tentativo tra ${backoffMs / 1000}s")
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
        }
    }

    /**
     * PID letti meno spesso del blocco principale: al massimo uno per ciclo (il più in ritardo rispetto al
     * proprio periodo), così nessun ciclo si allunga accodando cinque risposte multiframe di fila.
     */
    private class SlowPid(
        val header: String,
        val command: String,
        val periodCycles: Int,
        /** Periodo usato quando la pagina 96 Celle è visibile */
        val focusPeriodCycles: Int = periodCycles,
        val apply: (NiroTelemetryState, ByteArray) -> NiroTelemetryState
    ) {
        var lastCycle = -1_000_000L
    }

    private suspend fun pollLiveData(link: ElmLink, deviceName: String, onHealthy: () -> Unit) {
        val slowPids = listOf(
            SlowPid("7E4", "220106", periodCycles = 5) { s, p -> Sg2PidParser.applyBms220106(s, p) },
            SlowPid("7E4", "220102", periodCycles = 40, focusPeriodCycles = 4) { s, p -> Sg2PidParser.applyBmsCellBlock(s, 0x02, p) },
            SlowPid("7E4", "220103", periodCycles = 40, focusPeriodCycles = 4) { s, p -> Sg2PidParser.applyBmsCellBlock(s, 0x03, p) },
            SlowPid("7E4", "220104", periodCycles = 40, focusPeriodCycles = 4) { s, p -> Sg2PidParser.applyBmsCellBlock(s, 0x04, p) },
            SlowPid("7C6", "22B002", periodCycles = 25) { s, p -> Sg2PidParser.applyCluster22B002(s, p) },
            SlowPid("7E4", "220105", periodCycles = 60) { s, p -> Sg2PidParser.applyBms220105(s, p) }
        )

        var cycle = 0L
        var emptyBmsCycles = 0
        while (true) {
            val cycleStartNs = System.nanoTime()

            // Le letture del ciclo vengono raccolte come trasformazioni pure e pubblicate in un'unica
            // update(): una sola emissione (e un solo ridisegno) per ciclo, senza sovrascrivere modifiche
            // concorrenti come l'azzeramento del viaggio. [snapshot] serve alle decisioni intra-ciclo.
            val pending = ArrayList<(NiroTelemetryState) -> NiroTelemetryState>(10)
            var snapshot = _telemetry.value
            fun stage(update: (NiroTelemetryState) -> NiroTelemetryState) {
                snapshot = update(snapshot)
                pending += update
            }

            val focus = cellDetailWanted
            val slow = slowPids
                .filter { cycle - it.lastCycle >= if (focus) it.focusPeriodCycles else it.periodCycles }
                .maxByOrNull { (cycle - it.lastCycle).toFloat() / (if (focus) it.focusPeriodCycles else it.periodCycles) }
            slow?.lastCycle = cycle

            // 1. BMS (7E4): SoC, potenza, temperature, celle min/max, 12V (+ eventuale PID lento sul BMS)
            var bmsOk = false
            suspend fun pollBms() {
                val p01 = link.query("7E4", "220101")
                if (p01.isNotEmpty()) {
                    bmsOk = true
                    stage { Sg2PidParser.applyBms220101(it, p01) }
                }
                if (slow != null && slow.header == "7E4") {
                    val p = link.query(slow.header, slow.command)
                    if (p.isNotEmpty()) stage { slow.apply(it, p) }
                }
            }

            // 2. ECU motore 1.6 GDi (7E0): giri, velocità, temperatura e consumo
            var engineOk = false
            suspend fun pollEngine() {
                val rpm = link.query("7E0", "010C", singleResponse = true)
                if (rpm.isNotEmpty()) {
                    engineOk = true
                    stage { Sg2PidParser.applyEnginePid(it, rpm) }
                }
                val speed = link.query("7E0", "010D", singleResponse = true)
                if (speed.isNotEmpty()) stage { Sg2PidParser.applyEnginePid(it, speed) }

                if (cycle % 2 == 0L) {
                    val temp = link.query("7E0", "0105", singleResponse = true)
                    if (temp.isNotEmpty()) stage { Sg2PidParser.applyEnginePid(it, temp) }
                }

                // Se il termico è acceso (HEV), interroga MAF (0110) o in alternativa il carico (0104)
                if (!snapshot.isEvMode) {
                    val maf = link.query("7E0", "0110", singleResponse = true)
                    if (maf.isNotEmpty()) {
                        stage { Sg2PidParser.applyEnginePid(it, maf) }
                    } else {
                        val load = link.query("7E0", "0104", singleResponse = true)
                        if (load.isNotEmpty()) stage { Sg2PidParser.applyEnginePid(it, load) }
                    }
                }
            }

            // Alternando l'ordine dei due blocchi l'header CAN cambia una sola volta per ciclo (un ATSH in meno)
            if (cycle % 2 == 0L) {
                pollBms()
                pollEngine()
            } else {
                pollEngine()
                pollBms()
            }

            // 3. PID lento su altre centraline (es. odometro dal Cluster 7C6)
            var freshOdometerKm: Int? = null
            if (slow != null && slow.header != "7E4") {
                val p = link.query(slow.header, slow.command)
                if (p.isNotEmpty()) {
                    stage { slow.apply(it, p) }
                    if (slow.command == "22B002") {
                        freshOdometerKm = Sg2PidParser.applyCluster22B002(NiroTelemetryState(), p).odometerKm.takeIf { it > 0 }
                    }
                }
            }

            // Percentuale di guida EV del viaggio, solo con dati motore aggiornati
            if (engineOk) stage(::accumulateTripSample)

            if (bmsOk) {
                emptyBmsCycles = 0
                onHealthy()
                stage { it.copy(isDemoMode = false, connectionStatus = "OBD: $deviceName") }
            } else {
                emptyBmsCycles++
                if (emptyBmsCycles >= MAX_EMPTY_BMS_CYCLES) {
                    throw IOException("nessuna risposta dal BMS (quadro spento?)")
                }
                stage { it.copy(connectionStatus = "OBD: $deviceName • BMS non risponde ($emptyBmsCycles/$MAX_EMPTY_BMS_CYCLES)") }
            }

            _telemetry.update { current -> pending.fold(current) { acc, f -> f(acc) } }

            // Registro viaggi: un campione per ogni ciclo con contatori BMS aggiornati
            if (bmsOk) tripRecorder?.onSample(_telemetry.value, freshOdometerKm)

            cycle++
            val elapsedMs = (System.nanoTime() - cycleStartNs) / 1_000_000L
            delay((POLL_PERIOD_MS - elapsedMs).coerceAtLeast(MIN_POLL_GAP_MS))
        }
    }

    private fun accumulateTripSample(s: NiroTelemetryState): NiroTelemetryState {
        val total = s.tripSamplesTotal + 1
        val ev = s.tripSamplesEv + if (s.isEvMode) 1 else 0
        return s.copy(
            tripSamplesTotal = total,
            tripSamplesEv = ev,
            evTripRatioPct = ((ev * 100f) / total).roundToInt().coerceIn(0, 100)
        )
    }

    /**
     * Apre il socket RFCOMM, inizializza l'ELM327 ed esegue [block]; il socket viene sempre chiuso.
     * Il socket è pubblicato in [activeSocket] così [launchExclusive] può chiuderlo da un altro thread
     * per interrompere un connect() o una lettura bloccati.
     */
    @SuppressLint("MissingPermission")
    private suspend fun <T> withElmLink(device: BluetoothDevice, block: suspend (ElmLink) -> T): T =
        dongleMutex.withLock {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            activeSocket.set(socket)
            try {
                currentCoroutineContext().ensureActive()
                socket.connect()
                val link = ElmLink(socket.inputStream, socket.outputStream)
                link.initialize()
                block(link)
            } finally {
                activeSocket.compareAndSet(socket, null)
                socket.closeQuietly()
            }
        }

    private fun setStatus(status: String) {
        _telemetry.update { it.copy(isDemoMode = false, connectionStatus = status) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }
}
