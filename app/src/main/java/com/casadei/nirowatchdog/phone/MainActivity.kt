package com.casadei.nirowatchdog.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.casadei.nirowatchdog.car.CockpitSurfaceScreen
import com.casadei.nirowatchdog.obd.TelemetryRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity per Smartphone:
 * 1) Seleziona l'adattatore Bluetooth OBD-II (ELM327 / OBDLink)
 * 2) Avvia il polling live per Android Auto oppure il Simulatore Demo
 * 3) Esegue lo "Scanner / Sniffer UDS Attivo" su tutte le centraline della Kia Niro SG2 PHEV 2024
 *    ed esporta il report JSON con i byte esadecimali grezzi.
 */
class MainActivity : AppCompatActivity() {

    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private lateinit var statusView: TextView
    private lateinit var liveSummaryView: TextView
    private lateinit var dumpOutputView: TextView
    private lateinit var tripsView: TextView
    private lateinit var deviceSpinner: Spinner
    private var lastJsonDump: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TelemetryRepository.init(this)

        // Contenuto in una pagina scorrevole: su schermi bassi registro viaggi e log restano leggibili
        // invece di essere schiacciati sotto i pulsanti
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Niro Monitor — Kia Niro SG2 PHEV"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38E0B2"))
        }
        root.addView(title)

        val creditView = TextView(this).apply {
            text = "Made with ❤️ by Cristiano Casadei"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8B9BB4"))
            setPadding(0, 4, 0, 8)
        }
        root.addView(creditView)

        statusView = TextView(this).apply {
            text = "Stato: Inizializzazione..."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 16)
        }
        root.addView(statusView)

        liveSummaryView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9AA7BD"))
            setBackgroundColor(Color.parseColor("#141923"))
            setPadding(28, 24, 28, 24)
        }
        root.addView(liveSummaryView)

        val spinnerLabel = TextView(this).apply {
            text = "Dispositivo Bluetooth OBD-II Associato:"
            textSize = 14f
            setTextColor(Color.parseColor("#DCE5F5"))
            setPadding(0, 28, 0, 8)
        }
        root.addView(spinnerLabel)

        deviceSpinner = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#1D2330"))
            setPadding(20, 20, 20, 20)
        }
        root.addView(deviceSpinner)

        // Pulsanti azione
        val btnLiveObd = Button(this).apply {
            text = "▶️ Avvia Telemetria Live OBD-II (Android Auto)"
            setBackgroundColor(Color.parseColor("#2D73D2"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val dev = selectedBluetoothDevice()
                if (dev != null) {
                    TelemetryRepository.connectAndStartLiveObd(dev)
                } else {
                    Toast.makeText(this@MainActivity, "Seleziona prima un dongle OBD Bluetooth", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnLiveObd, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        val btnScanUds = Button(this).apply {
            text = "🔍 Scanner / Sniffer PID UDS (Niro SG2 Dump)"
            setBackgroundColor(Color.parseColor("#16A085"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val dev = selectedBluetoothDevice()
                if (dev != null) {
                    TelemetryRepository.runFullSg2DiscoveryScan(dev) { jsonReport ->
                        lastJsonDump = jsonReport
                        saveDumpToFile(jsonReport)
                        Toast.makeText(this@MainActivity, "Dump SG2 completato e salvato!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Associa e seleziona l'adattatore OBD-II", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnScanUds, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        val rowButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnDemo = Button(this).apply {
            text = "🧪 Modalità Demo"
            setBackgroundColor(Color.parseColor("#2F384B"))
            setTextColor(Color.WHITE)
            setOnClickListener { TelemetryRepository.startDemoSimulator() }
        }
        rowButtons.addView(btnDemo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = 8
        })

        val btnShareDump = Button(this).apply {
            text = "📤 Condividi / Copia Dump"
            setBackgroundColor(Color.parseColor("#2F384B"))
            setTextColor(Color.WHITE)
            setOnClickListener { shareOrCopyDump() }
        }
        rowButtons.addView(btnShareDump, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = 8
        })

        root.addView(rowButtons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        // Rendering GPU sperimentale della schermata Android Auto: vale dalla prossima surface
        val prefs = getSharedPreferences(CockpitSurfaceScreen.PREFS_NAME, Context.MODE_PRIVATE)
        val gpuSwitch = SwitchCompat(this).apply {
            text = "Rendering GPU su Android Auto (sperimentale)"
            textSize = 13f
            setTextColor(Color.parseColor("#DCE5F5"))
            isChecked = prefs.getBoolean(CockpitSurfaceScreen.PREF_GPU_RENDERING, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(CockpitSurfaceScreen.PREF_GPU_RENDERING, checked).apply()
                Toast.makeText(
                    this@MainActivity,
                    "Attivo alla prossima apertura di Niro Monitor su Android Auto",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(gpuSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })

        // Registro viaggi: si compila da solo durante le sessioni Android Auto
        val tripsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 8)
        }
        tripsHeader.addView(TextView(this).apply {
            text = "Registro viaggi:"
            textSize = 13f
            setTextColor(Color.parseColor("#38E0B2"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tripsHeader.addView(Button(this).apply {
            text = "Esporta CSV"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2F384B"))
            setTextColor(Color.WHITE)
            setOnClickListener { shareTripsCsv() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = 8
        })
        tripsHeader.addView(Button(this).apply {
            text = "Azzera"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2F384B"))
            setTextColor(Color.WHITE)
            setOnClickListener { confirmClearTrips() }
        })
        root.addView(tripsHeader)

        tripsView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#DCE5F5"))
            setBackgroundColor(Color.parseColor("#12161F"))
            setPadding(24, 24, 24, 24)
        }
        root.addView(tripsView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dumpTitle = TextView(this).apply {
            text = "Log Esadecimale Grezzo Scanner UDS SG2:"
            textSize = 13f
            setTextColor(Color.parseColor("#38E0B2"))
            setPadding(0, 24, 0, 8)
        }
        root.addView(dumpTitle)

        dumpOutputView = TextView(this).apply {
            text = "Premi 'Scanner / Sniffer PID UDS' in auto (quadro acceso) per catturare tutte le risposte esadecimali delle centraline 7E4, 7E2, 7E1, 7B3, 7E0, 7C6, 7D1, 7C4."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#DCE5F5"))
            setBackgroundColor(Color.parseColor("#12161F"))
            setPadding(24, 24, 24, 24)
        }
        root.addView(dumpOutputView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E13"))
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        checkBluetoothPermissionsAndLoadDevices()
        observeRepository()
    }

    private fun observeRepository() {
        // Aggiorna le view solo mentre l'Activity è visibile: in auto il telefono resta in background
        // e non deve ridisegnare testi a ogni ciclo di polling.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TelemetryRepository.telemetry.collectLatest { s ->
                        statusView.text = "Stato: ${s.connectionStatus}"
                        liveSummaryView.text = buildString {
                            appendLine("BMS: ${"%.1f".format(s.socBms)}% (Utile EV: ${"%.1f".format(s.usableEvSocPct)}% • ~${s.estimatedEvRangeKm} km)  |  Odo: ${s.odometerKm} km")
                            appendLine("Potenza HV: ${s.hvPowerKw} kW (${"%.1f".format(s.instantEvKwh100km)} kWh/100km)  |  Marcia: ${s.currentGear}")
                            appendLine("Termico: ${s.iceRpm} rpm • ${"%.1f".format(s.instantFuelL100km)} L/100km (${"%.1f".format(s.instantFuelLh)} L/h) • ${s.vehicleSpeedKmh} km/h")
                            append("Celle (96): Min ${"%.2f".format(s.cellVoltageMin)}V / Max ${"%.2f".format(s.cellVoltageMax)}V (ΔV ${s.cellDeltaMv} mV)  |  ECT: ${s.iceCoolantTemp}°C")
                        }
                    }
                }

                launch {
                    TelemetryRepository.trips.collectLatest { log ->
                        tripsView.text = TripLogText.format(log, System.currentTimeMillis())
                    }
                }

                launch {
                    TelemetryRepository.discoveryDump.collectLatest { entries ->
                        if (entries.isNotEmpty()) {
                            dumpOutputView.text = entries.joinToString("\n\n") { e ->
                                "[${e.ecuHeaderTx}] ${e.ecuName} -> CMD ${e.pidCommand} (${e.byteCount} byte):\n${e.payloadBytesHex.ifEmpty { "NO DATA / risposta incompleta" }}"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun checkBluetoothPermissionsAndLoadDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
                return
            }
        }
        loadPairedDevices()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (hasBluetoothPermissions()) {
                loadPairedDevices()
            } else {
                // Senza BLUETOOTH_CONNECT bondedDevices lancia SecurityException: non si prova nemmeno.
                // Il messaggio passa dal repository, altrimenti il collector dello stato lo sovrascriverebbe.
                TelemetryRepository.reportStatus("Permesso Bluetooth negato — abilitalo nelle impostazioni dell'app")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        pairedDevices = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        val labels = if (pairedDevices.isEmpty()) {
            listOf("Nessun dispositivo OBD Bluetooth associato")
        } else {
            pairedDevices.map { "${it.name ?: "OBD"} (${it.address})" }
        }

        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        val preferred = TelemetryRepository.findPreferredObdDevice(pairedDevices)
        val prefIndex = pairedDevices.indexOf(preferred)
        if (prefIndex >= 0) {
            deviceSpinner.setSelection(prefIndex)
        }
    }

    private fun selectedBluetoothDevice(): BluetoothDevice? {
        val idx = deviceSpinner.selectedItemPosition
        return pairedDevices.getOrNull(idx)
    }

    private fun saveDumpToFile(json: String) {
        try {
            File(filesDir, "niro_sg2_uds_dump.json").writeText(json)
        } catch (_: Exception) {
        }
    }

    private fun shareOrCopyDump() {
        val report = lastJsonDump.ifEmpty {
            TelemetryRepository.buildJsonDumpReport(TelemetryRepository.discoveryDump.value)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Niro SG2 UDS Dump", report))

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kia Niro SG2 PHEV 2024 - UDS PID Dump")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(sendIntent, "Condividi Dump UDS Niro SG2"))
    }

    private fun shareTripsCsv() {
        val csv = TelemetryRepository.exportTripsCsv()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Niro Monitor - registro viaggi", csv))

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Niro Monitor - registro viaggi (CSV)")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(sendIntent, "Esporta registro viaggi"))
    }

    private fun confirmClearTrips() {
        AlertDialog.Builder(this)
            .setTitle("Azzerare il registro viaggi?")
            .setMessage("Tutti i viaggi registrati sul telefono verranno cancellati.")
            .setPositiveButton("Azzera") { _, _ -> TelemetryRepository.clearTrips() }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
