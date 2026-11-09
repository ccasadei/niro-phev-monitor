package com.casadei.nirowatchdog.car

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.*
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.casadei.nirowatchdog.R
import com.casadei.nirowatchdog.obd.NiroTelemetryState
import com.casadei.nirowatchdog.obd.TelemetryRepository
import com.casadei.nirowatchdog.obd.TripLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Schermata principale Full-Screen su Android Auto basata su NavigationTemplate + SurfaceCallback.
 *
 * - Nessun header superiore: i gauge e i grafici occupano l'intera altezza dello schermo.
 * - Tutte le informazioni di stato (Titolo, EV/HEV, Connessione OBD, Pagina attiva e Firma)
 *   sono raccolte nella barra footer compatta in basso.
 * - Cambio pagina circolare (6 Gauge -> 96 Celle -> Cockpit -> Viaggio -> 6 Gauge) toccando lo schermo,
 *   scorrendo a destra/sinistra (swipe) o toccando l'icona compatta a scomparsa.
 * - I ridisegni sono raggruppati (al massimo uno ogni [MIN_FRAME_INTERVAL_MS]) e Paint, colori e
 *   Path sono allocati una volta sola: nessuna allocazione nativa a ogni frame.
 */
class CockpitSurfaceScreen(
    carContext: CarContext,
    initialMode: Int = MODE_GAUGES_6
) : Screen(carContext) {

    companion object {
        const val MODE_GAUGES_6 = 1
        const val MODE_CELLS_96 = 2
        const val MODE_COCKPIT_LIVE = 3
        const val MODE_TRIP = 4

        /** Preferenze condivise con l'app telefono (interruttore del rendering GPU sperimentale) */
        const val PREFS_NAME = "niro_monitor"
        const val PREF_GPU_RENDERING = "gpu_rendering"

        private const val TAG = "NiroRender"
        /** Ogni quanti frame scrivere nel log il tempo di disegno (medio e massimo) sul main thread */
        private const val FRAME_STATS_WINDOW = 20

        /** Intervallo minimo tra due frame (~15 fps): nuovi dati, tap e scroll ravvicinati diventano un solo disegno */
        private const val MIN_FRAME_INTERVAL_MS = 66L
        /** Nuovo tentativo se un frame non è stato disegnato (surface non ancora valida o lockCanvas fallito) */
        private const val RENDER_RETRY_MS = 250L
        /** Oltre questa età l'ultimo dato BMS reale viene segnalato come fermo nel footer */
        private const val STALE_DATA_MS = 5_000L
        private const val MIN_TEXT_SIZE = 9f

        // Palette (convertita una volta sola invece che a ogni frame)
        private val COLOR_BG = Color.parseColor("#0B0F17")
        private val COLOR_CARD = Color.parseColor("#141B27")
        private val COLOR_CARD_BORDER = Color.parseColor("#243044")
        private val COLOR_CARD_TITLE = Color.parseColor("#B4C6E0")
        private val COLOR_GAUGE_TRACK = Color.parseColor("#283347")
        private val COLOR_CARD_FOOTER = Color.parseColor("#8FA3BF")
        private val COLOR_FOOTER_TEXT = Color.parseColor("#DCE6F7")
        private val COLOR_PLOT_BG = Color.parseColor("#0E131D")
        private val COLOR_AXIS_LABEL = Color.parseColor("#9FB2CE")
        private val COLOR_BAR_TRACK = Color.parseColor("#253042")
        private val COLOR_ZERO_LINE = Color.parseColor("#33425B")
        private val COLOR_HEV_DIM = Color.parseColor("#6E4B0E")
        private val COLOR_STALE = Color.parseColor("#6B7A90")
        private val COLOR_MINT = Color.parseColor("#38E0B2")
        private val COLOR_GREEN = Color.parseColor("#2ED573")
        private val COLOR_AMBER = Color.parseColor("#FFA502")
        private val COLOR_RED = Color.parseColor("#FF5E57")
        private val COLOR_BLUE = Color.parseColor("#36A2EB")
        private val COLOR_PURPLE = Color.parseColor("#A55EEA")

        private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private enum class GaugeIconType {
        HV_BATTERY,
        HV_POWER,
        BATTERY_TEMP,
        ENGINE_ICE,
        FUEL_GEAR,
        AUX_12V
    }

    private var currentMode: Int = initialMode
    private var latestState: NiroTelemetryState = TelemetryRepository.telemetry.value
    private var latestTrips: TripLog = TelemetryRepository.trips.value
    private var collectJob: Job? = null
    private var tripsJob: Job? = null
    private var tickerJob: Job? = null

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleRect: Rect = Rect()
    private val renderLock = Any()

    // Rendering via CPU (lockCanvas) o via GPU (lockHardwareCanvas, sperimentale). Si decide una volta per
    // surface: una surface già disegnata dalla GPU non può più essere bloccata con il Canvas software.
    private var useHardwareCanvas = false
    private var hardwareFramesOnSurface = 0
    private var statFrames = 0
    private var statTotalNs = 0L
    private var statMaxNs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderScheduled = AtomicBoolean(false)
    @Volatile
    private var lastRenderUptimeMs = 0L
    private val retryRenderRunnable = Runnable { requestRender() }
    private val renderRunnable = Runnable {
        renderScheduled.set(false)
        lastRenderUptimeMs = SystemClock.uptimeMillis()
        if (!renderFrame()) {
            // Senza ticker periodico nessuno ridisegnerebbe finché i dati non cambiano: si riprova a breve
            mainHandler.removeCallbacks(retryRenderRunnable)
            mainHandler.postDelayed(retryRenderRunnable, RENDER_RETRY_MS)
        }
    }

    private var scrollAccumX = 0f
    private var lastPageSwitchMs = 0L

    // Area touch e stato di conferma per azzerare il contatore Ripartizione Viaggio EV/HEV nella pagina 96 Celle
    private val evResetHitRect = RectF()
    private var evResetConfirmDeadlineMs = 0L
    private var evResetDoneBannerUntilMs = 0L

    // Area touch (intestazione) e stato di conferma per chiudere il viaggio in corso nella pagina Viaggio
    private val tripResetHitRect = RectF()
    private var tripResetConfirmDeadlineMs = 0L
    private var tripResetDoneBannerUntilMs = 0L

    // Oggetti di disegno riutilizzati a ogni frame (configurati subito prima di ogni draw)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val chartLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR_MINT
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconWhiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val iconWhiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val chartPath = Path()
    private val cardRect = RectF()
    private val arcRect = RectF()
    private val panelRect = RectF()
    private val chartRect = RectF()
    private val footerRect = RectF()
    private val barRect = RectF()
    private val shapeRect = RectF()

    private fun setMode(mode: Int) {
        currentMode = mode
        TelemetryRepository.setCellDetailWanted(mode == MODE_CELLS_96)
    }

    private fun cycleNextMode() {
        val now = System.currentTimeMillis()
        if (now - lastPageSwitchMs < 300L) return
        lastPageSwitchMs = now
        evResetConfirmDeadlineMs = 0L
        tripResetConfirmDeadlineMs = 0L
        setMode(
            when (currentMode) {
                MODE_GAUGES_6 -> MODE_CELLS_96
                MODE_CELLS_96 -> MODE_COCKPIT_LIVE
                MODE_COCKPIT_LIVE -> MODE_TRIP
                else -> MODE_GAUGES_6
            }
        )
        scrollAccumX = 0f
        requestRender()
    }

    private fun cyclePrevMode() {
        val now = System.currentTimeMillis()
        if (now - lastPageSwitchMs < 300L) return
        lastPageSwitchMs = now
        evResetConfirmDeadlineMs = 0L
        tripResetConfirmDeadlineMs = 0L
        setMode(
            when (currentMode) {
                MODE_GAUGES_6 -> MODE_TRIP
                MODE_CELLS_96 -> MODE_GAUGES_6
                MODE_COCKPIT_LIVE -> MODE_CELLS_96
                else -> MODE_COCKPIT_LIVE
            }
        )
        scrollAccumX = 0f
        requestRender()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val gpu = carContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_GPU_RENDERING, false)
            synchronized(renderLock) {
                surfaceContainer = container
                useHardwareCanvas = gpu
                hardwareFramesOnSurface = 0
                statFrames = 0
                statTotalNs = 0L
                statMaxNs = 0L
                if (visibleRect.width() <= 0 || visibleRect.height() <= 0) {
                    visibleRect = Rect(0, 0, container.width, container.height)
                }
            }
            Log.i(TAG, "Surface ${container.width}x${container.height} (${container.dpi} dpi): rendering ${if (gpu) "GPU" else "CPU"}")
            requestRender()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            synchronized(renderLock) {
                visibleRect = Rect(visibleArea)
            }
            requestRender()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            requestRender()
        }

        override fun onClick(x: Float, y: Float) {
            val now = System.currentTimeMillis()
            // Nella vista 96 Celle, toccando la barra Ripartizione Viaggio EV/HEV si attiva la conferma di azzeramento
            if (currentMode == MODE_CELLS_96 && evResetHitRect.contains(x, y)) {
                if (now < evResetConfirmDeadlineMs) {
                    // Secondo tocco entro 5 secondi: conferma ed esegue l'azzeramento
                    TelemetryRepository.resetTripCounters()
                    evResetConfirmDeadlineMs = 0L
                    evResetDoneBannerUntilMs = now + 2600L
                } else {
                    // Primo tocco: chiede conferma per 5 secondi
                    evResetConfirmDeadlineMs = now + 5000L
                }
                requestRender()
                return
            }

            // Nella vista Viaggio, toccando l'intestazione si azzera il totalizzatore (con conferma)
            if (currentMode == MODE_TRIP && tripResetHitRect.contains(x, y)) {
                when {
                    now < tripResetConfirmDeadlineMs -> {
                        // Secondo tocco entro 5 secondi: azzera, il prossimo viaggio riparte da zero
                        TelemetryRepository.resetTripCounter()
                        tripResetConfirmDeadlineMs = 0L
                        tripResetDoneBannerUntilMs = now + 2600L
                        requestRender()
                        return
                    }
                    now < tripResetDoneBannerUntilMs -> return
                    TripPageModel.of(latestTrips, now).canReset -> {
                        // Primo tocco: chiede conferma per 5 secondi
                        tripResetConfirmDeadlineMs = now + 5000L
                        requestRender()
                        return
                    }
                }
                // Totalizzatore già vuoto: il tocco cambia pagina come altrove
            }

            // Toccando altrove ruota in circolo alla pagina successiva
            cycleNextMode()
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            // Strisciando a sinistra o a destra cambia pagina
            if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 180f) {
                if (velocityX < 0f) {
                    cycleNextMode()
                } else {
                    cyclePrevMode()
                }
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (abs(distanceX) > abs(distanceY)) {
                scrollAccumX += distanceX
                if (scrollAccumX > 85f) {
                    cycleNextMode()
                } else if (scrollAccumX < -85f) {
                    cyclePrevMode()
                }
            }
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            synchronized(renderLock) {
                if (surfaceContainer === container) {
                    surfaceContainer = null
                }
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                setMode(currentMode)

                collectJob = lifecycleScope.launch {
                    TelemetryRepository.telemetry.collect { state ->
                        latestState = state
                        requestRender()
                    }
                }
                tripsJob = lifecycleScope.launch {
                    TelemetryRepository.trips.collect { log ->
                        latestTrips = log
                        if (currentMode == MODE_TRIP) requestRender()
                    }
                }

                // Ridisegna a tempo solo se c'è qualcosa che cambia senza nuovi dati (countdown, età dei dati)
                tickerJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(1000L)
                        if (needsTimedRefresh()) requestRender()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                collectJob?.cancel()
                tripsJob?.cancel()
                tickerJob?.cancel()
                mainHandler.removeCallbacks(renderRunnable)
                mainHandler.removeCallbacks(retryRenderRunnable)
                renderScheduled.set(false)
                TelemetryRepository.setCellDetailWanted(false)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val cycleIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_tab_stats)
        ).build()

        // Singola icona compatta a scomparsa (richiesta da NavigationTemplate) per ruotare vista in circolo,
        // oltre al tocco/swipe diretto sullo schermo (abilitato da PanModeListener + SurfaceCallback).
        val compactStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(cycleIcon)
                    .setOnClickListener {
                        cycleNextMode()
                    }
                    .build()
            )
            .build()

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(compactStrip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener { _ ->
                // Quando l'utente attiva/disattiva il pan o tocca il surface, mantiene il full-screen
                requestRender()
            }
            .build()
    }

    /**
     * Richiede un ridisegno. Le richieste ravvicinate (nuovi dati, tap, eventi di scroll) vengono raggruppate
     * in un solo frame, al massimo uno ogni [MIN_FRAME_INTERVAL_MS].
     */
    private fun requestRender() {
        if (!renderScheduled.compareAndSet(false, true)) return
        val waitMs = (lastRenderUptimeMs + MIN_FRAME_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(renderRunnable, waitMs)
    }

    private fun needsTimedRefresh(): Boolean {
        val now = System.currentTimeMillis()
        // Countdown di conferma azzeramento e banner "azzerato" (+1 s per cancellarli alla scadenza)
        if (now < evResetConfirmDeadlineMs + 1000L || now < evResetDoneBannerUntilMs + 1000L) return true
        if (now < tripResetConfirmDeadlineMs + 1000L || now < tripResetDoneBannerUntilMs + 1000L) return true
        // Età dei dati nel footer quando il BMS non aggiorna più
        val s = latestState
        return !s.isDemoMode && s.lastLiveDataMs > 0L && now - s.lastLiveDataMs > STALE_DATA_MS
    }

    /**
     * Disegna un frame. Restituisce false solo se una surface c'è ma non è stato possibile disegnarci
     * (va ritentato); senza surface restituisce true perché ci penserà onSurfaceAvailable.
     */
    private fun renderFrame(): Boolean {
        synchronized(renderLock) {
            val container = surfaceContainer ?: return true
            val surface = container.surface ?: return false
            if (!surface.isValid) return false

            val w = container.width.toFloat()
            val h = container.height.toFloat()
            if (w <= 10f || h <= 10f) return true

            val startNs = System.nanoTime()
            val canvas = lockCanvas(surface) ?: return false

            try {
                drawFullDashboard(canvas, w, h, visibleRect, latestState, currentMode)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
            if (useHardwareCanvas) hardwareFramesOnSurface++
            recordFrameTime(System.nanoTime() - startNs)
            return true
        }
    }

    private fun lockCanvas(surface: Surface): Canvas? {
        if (useHardwareCanvas) {
            try {
                return surface.lockHardwareCanvas()
            } catch (e: Exception) {
                // Dopo il primo frame GPU la surface non accetta più il Canvas software: si ritenta al prossimo frame
                if (hardwareFramesOnSurface > 0) {
                    Log.w(TAG, "lockHardwareCanvas fallito", e)
                    return null
                }
                Log.w(TAG, "lockHardwareCanvas non disponibile su questa surface: rendering CPU", e)
                useHardwareCanvas = false
            }
        }
        return try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            null
        }
    }

    /** Tempo sul main thread di ogni frame (lock + disegno + post), riassunto nel log ogni [FRAME_STATS_WINDOW] frame. */
    private fun recordFrameTime(elapsedNs: Long) {
        statFrames++
        statTotalNs += elapsedNs
        if (elapsedNs > statMaxNs) statMaxNs = elapsedNs
        if (statFrames >= FRAME_STATS_WINDOW) {
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "%s pagina %d: %d frame, media %.2f ms, max %.2f ms",
                    if (useHardwareCanvas) "GPU" else "CPU",
                    currentMode,
                    statFrames,
                    statTotalNs / statFrames / 1_000_000.0,
                    statMaxNs / 1_000_000.0
                )
            )
            statFrames = 0
            statTotalNs = 0L
            statMaxNs = 0L
        }
    }

    private fun fill(color: Int): Paint = fillPaint.apply { this.color = color }

    private fun stroke(color: Int, width: Float): Paint = strokePaint.apply {
        this.color = color
        strokeWidth = width
    }

    private fun line(color: Int, width: Float): Paint = linePaint.apply {
        this.color = color
        strokeWidth = width
    }

    private fun arc(color: Int, width: Float): Paint = arcPaint.apply {
        this.color = color
        strokeWidth = width
    }

    private fun text(
        color: Int,
        size: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): Paint = textPaint.apply {
        this.color = color
        textSize = size
        typeface = if (bold) BOLD else Typeface.DEFAULT
        textAlign = align
    }

    private fun drawFullDashboard(
        canvas: Canvas,
        surfaceW: Float,
        surfaceH: Float,
        vis: Rect,
        s: NiroTelemetryState,
        mode: Int
    ) {
        // 1. Sfondo globale scuro automotive full-bleed
        canvas.drawRect(0f, 0f, surfaceW, surfaceH, fill(COLOR_BG))

        // 2. Nessun header superiore: i contenuti partono direttamente dal bordo superiore (1.5% margine)
        val hasValidVis = vis.width() > surfaceW * 0.25f && vis.height() > surfaceH * 0.25f
        val padOuterX = surfaceW * 0.014f
        val padOuterY = surfaceH * 0.015f

        val safeL = if (hasValidVis) max(vis.left.toFloat() + padOuterX, padOuterX) else padOuterX
        val safeR = if (hasValidVis && vis.right > 0) min(vis.right.toFloat() - padOuterX, surfaceW - padOuterX) else surfaceW - padOuterX
        val safeT = padOuterY
        val safeB = surfaceH - padOuterY

        val safeW = (safeR - safeL).coerceAtLeast(200f)
        val safeH = (safeB - safeT).coerceAtLeast(150f)

        // Riserva in basso solo la barra footer compatta (8.2% dell'altezza)
        val gapY = safeH * 0.016f
        val footerH = (safeH * 0.082f).coerceAtLeast(28f)
        val contentH = (safeH - footerH - gapY).coerceAtLeast(120f)

        // 3. Disegna la pagina attiva sfruttando tutto lo spazio verticale sopra il footer
        when (mode) {
            MODE_GAUGES_6 -> drawMode6Gauges(canvas, safeL, safeT, safeW, contentH, s)
            MODE_CELLS_96 -> drawMode96Cells(canvas, safeL, safeT, safeW, contentH, s)
            MODE_TRIP -> drawModeTrip(
                canvas, safeL, safeT, safeW, contentH, TripPageModel.of(latestTrips, System.currentTimeMillis())
            )
            else -> drawModeCockpitLive(canvas, safeL, safeT, safeW, contentH, s)
        }

        // 4. Barra Footer Inferiore Unificata (Titolo + EV/HEV + Stato OBD + Pagina + Firma)
        val footerTop = safeT + contentH + gapY
        footerRect.set(safeL, footerTop, safeL + safeW, footerTop + footerH)
        drawBottomFooterBar(
            canvas = canvas,
            rect = footerRect,
            s = s,
            mode = mode
        )
    }

    /**
     * VISTA 1: 6 Manometri Circolari a Tutto Schermo (con Consumo Istantaneo & Marcia al posto di Salute Celle).
     */
    private fun drawMode6Gauges(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        s: NiroTelemetryState
    ) {
        val gapX = width * 0.012f
        val gapY = height * 0.020f

        val cols = 3
        val rows = 2
        val cardW = (width - gapX * (cols - 1)) / cols
        val cardH = (height - gapY * (rows - 1)) / rows

        val sign = if (s.hvPowerKw > 0f) "+" else ""
        val socColor = when {
            s.socBms <= NiroTelemetryState.HEV_FLOOR_BMS_SOC + 1.5f -> COLOR_RED
            s.socBms < 35f -> COLOR_AMBER
            else -> COLOR_GREEN
        }
        val kwColor = when {
            s.hvPowerKw < 0f -> COLOR_BLUE
            s.hvPowerKw > 38f -> COLOR_AMBER
            else -> COLOR_GREEN
        }
        val tbColor = when {
            s.batteryTempAvg > 38f -> COLOR_RED
            s.batteryTempAvg > 32f -> COLOR_AMBER
            else -> COLOR_GREEN
        }
        val iceColor = if (s.isEvMode) COLOR_BLUE else COLOR_AMBER

        // Colore e testo per il 5° Gauge: CONSUMO (kWh/100km in EV, L/100km in HEV) & MARCIA
        val fuelColor = when {
            s.isEvMode && s.instantEvKwh100km < 0f -> COLOR_BLUE
            s.isEvMode && s.instantEvKwh100km > 22f -> COLOR_AMBER
            s.isEvMode -> COLOR_GREEN
            s.instantFuelL100km > 8.5f -> COLOR_RED
            s.instantFuelL100km > 5.2f -> COLOR_AMBER
            else -> COLOR_MINT
        }
        val fuelMainText = when {
            s.isEvMode && s.vehicleSpeedKmh < 5 -> "${"%.1f".format(s.hvPowerKw)} kWh/h"
            s.isEvMode -> "${"%.1f".format(s.instantEvKwh100km)} kWh/100"
            s.vehicleSpeedKmh < 5 -> "${"%.1f".format(s.instantFuelLh)} L/h"
            else -> "${"%.1f".format(s.instantFuelL100km)} L/100"
        }
        val fuelProgress = when {
            s.isEvMode && s.vehicleSpeedKmh < 5 -> (abs(s.hvPowerKw) / 10.0f).coerceIn(0.06f, 1f)
            s.isEvMode -> (abs(s.instantEvKwh100km) / 30.0f).coerceIn(0.06f, 1f)
            s.vehicleSpeedKmh < 5 -> (s.instantFuelLh / 6.0f).coerceIn(0.08f, 1f)
            else -> (s.instantFuelL100km / 14.0f).coerceIn(0.08f, 1f)
        }
        val fuelFooterText = when {
            s.isEvMode && s.vehicleSpeedKmh < 5 -> "Vel: 0 km/h  •  Benzina: 0.0 L/h"
            s.isEvMode -> "Vel: ${s.vehicleSpeedKmh} km/h  •  Benzina: 0.0 L/100"
            s.vehicleSpeedKmh < 5 -> "Termico acceso  •  EV: ${"%.1f".format(s.hvPowerKw)} kW"
            else -> "Vel: ${s.vehicleSpeedKmh} km/h  •  EV: ${"%.1f".format(s.instantEvKwh100km)} kWh/100"
        }

        data class GaugeSpec(
            val title: String,
            val iconType: GaugeIconType,
            val progress: Float,
            val mainValue: String,
            val unitLabel: String,
            val footer: String,
            val color: Int
        )

        val specs = listOf(
            GaugeSpec(
                title = "BATTERIA HV (BMS)",
                iconType = GaugeIconType.HV_BATTERY,
                progress = (s.socBms / 100f).coerceIn(0.04f, 1f),
                mainValue = "${s.socBms}%",
                unitLabel = if (s.usableEvSocPct > 0f) "UTILE EV ${s.usableEvSocPct}%" else "RISERVA HEV (15.5%)",
                footer = if (s.estimatedEvRangeKm > 0) {
                    "Autonomia EV: ~${s.estimatedEvRangeKm} km (Soglia 15.5%)"
                } else {
                    "Sotto soglia 15.5% • Ibrido Forzato"
                },
                color = socColor
            ),
            GaugeSpec(
                title = "POTENZA HV LIVE",
                iconType = GaugeIconType.HV_POWER,
                progress = (abs(s.hvPowerKw) / 65f).coerceIn(0.04f, 1f),
                mainValue = "$sign${s.hvPowerKw} kW",
                unitLabel = when {
                    s.hvPowerKw < 0f -> "RIGENERAZIONE"
                    s.vehicleSpeedKmh == 0 && s.hvPowerKw > 0.4f -> "CLIMA / SERVIZI HV"
                    else -> "TRAZIONE EV"
                },
                footer = "${s.hvVoltage} V  •  ${s.hvCurrent} A",
                color = kwColor
            ),
            GaugeSpec(
                title = "TEMP. BATTERIA HV",
                iconType = GaugeIconType.BATTERY_TEMP,
                progress = ((s.batteryTempAvg + 5f) / 55f).coerceIn(0.06f, 1f),
                mainValue = "${s.batteryTempAvg}°C",
                unitLabel = "Circuito ${s.coolantInletTemp.toInt()}°C",
                footer = "Min ${s.batteryTempMin}°C  •  Max ${s.batteryTempMax}°C",
                color = tbColor
            ),
            GaugeSpec(
                title = "MOTORE 1.6 GDi",
                iconType = GaugeIconType.ENGINE_ICE,
                progress = (s.iceCoolantTemp / 110f).coerceIn(0.06f, 1f),
                mainValue = "${s.iceCoolantTemp}°C",
                unitLabel = if (s.isEvMode) "EV PURO (0 RPM)" else "${s.iceRpm} RPM",
                footer = if (s.isEvMode) "Termico Spento" else "Ibrido HEV Attivo",
                color = iceColor
            ),
            GaugeSpec(
                title = "CONSUMO & MARCIA",
                iconType = GaugeIconType.FUEL_GEAR,
                progress = fuelProgress,
                mainValue = fuelMainText,
                unitLabel = "MARCIA: ${s.currentGear}",
                footer = fuelFooterText,
                color = fuelColor
            ),
            GaugeSpec(
                title = "SERVIZI 12V & LDC",
                iconType = GaugeIconType.AUX_12V,
                progress = ((s.aux12vVoltage - 11f) / 4f).coerceIn(0.1f, 1f),
                mainValue = "${s.aux12vVoltage} V",
                unitLabel = if (s.ldcActive) "RICARICA LDC ON" else "BATTERIA AUX",
                footer = "Odo: ${s.odometerKm} km  •  Guida EV: ${s.evTripRatioPct}%",
                color = COLOR_PURPLE
            )
        )

        for (i in specs.indices) {
            val r = i / cols
            val c = i % cols
            val cx1 = left + c * (cardW + gapX)
            val cy1 = top + r * (cardH + gapY)
            cardRect.set(cx1, cy1, cx1 + cardW, cy1 + cardH)
            drawGaugeCard(
                canvas = canvas,
                rect = cardRect,
                title = specs[i].title,
                iconType = specs[i].iconType,
                progress = specs[i].progress,
                mainValue = specs[i].mainValue,
                unitLabel = specs[i].unitLabel,
                footer = specs[i].footer,
                activeColor = specs[i].color
            )
        }
    }

    private fun drawGaugeCard(
        canvas: Canvas,
        rect: RectF,
        title: String,
        iconType: GaugeIconType,
        progress: Float,
        mainValue: String,
        unitLabel: String,
        footer: String,
        activeColor: Int
    ) {
        val w = rect.width()
        val h = rect.height()
        val corner = min(w, h) * 0.075f

        canvas.drawRoundRect(rect, corner, corner, fill(COLOR_CARD))
        canvas.drawRoundRect(rect, corner, corner, stroke(COLOR_CARD_BORDER, max(1.5f, min(w, h) * 0.012f)))

        // Titolo superiore
        drawAutoFitText(
            canvas, title, rect.centerX(), rect.top + h * 0.135f, w * 0.90f,
            text(COLOR_CARD_TITLE, min(w * 0.072f, h * 0.110f), bold = true, align = Paint.Align.CENTER)
        )

        // Icona indicativa a sinistra del Gauge
        val iconCx = rect.left + w * 0.205f
        val iconCy = rect.top + h * 0.525f
        val iconR = min(w * 0.148f, h * 0.258f)
        drawGaugeVectorIcon(canvas, iconCx, iconCy, iconR, iconType, activeColor)

        // Cerchio Gauge ingrandito a destra dell'icona
        val gaugeRadius = min(w * 0.310f, h * 0.345f)
        val gCx = rect.left + w * 0.625f
        val gCy = rect.top + h * 0.530f
        val strokeW = max(6.5f, gaugeRadius * 0.21f)
        arcRect.set(gCx - gaugeRadius, gCy - gaugeRadius, gCx + gaugeRadius, gCy + gaugeRadius)

        canvas.drawArc(arcRect, 135f, 270f, false, arc(COLOR_GAUGE_TRACK, strokeW))
        canvas.drawArc(arcRect, 135f, 270f * progress.coerceIn(0.03f, 1f), false, arc(activeColor, strokeW))

        // Valore principale dentro il gauge
        drawAutoFitText(
            canvas, mainValue, gCx, gCy + gaugeRadius * 0.08f, gaugeRadius * 1.52f,
            text(Color.WHITE, gaugeRadius * 0.53f, bold = true, align = Paint.Align.CENTER)
        )

        // Sotto-etichetta interna (es. MARCIA: 4ª)
        drawAutoFitText(
            canvas, unitLabel, gCx, gCy + gaugeRadius * 0.52f, gaugeRadius * 1.60f,
            text(activeColor, gaugeRadius * 0.285f, bold = true, align = Paint.Align.CENTER)
        )

        // Footer in basso nella card
        drawAutoFitText(
            canvas, footer, rect.centerX(), rect.bottom - h * 0.055f, w * 0.92f,
            text(COLOR_CARD_FOOTER, min(w * 0.064f, h * 0.094f), align = Paint.Align.CENTER)
        )
    }

    /**
     * Disegna l'icona vettoriale automotive dentro un badge circolare luminoso a fianco del Gauge.
     */
    private fun drawGaugeVectorIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        iconType: GaugeIconType,
        accentColor: Int
    ) {
        val badgeBg = Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        val badgeRing = Color.argb(140, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(cx, cy, r, fill(badgeBg))
        canvas.drawCircle(cx, cy, r, stroke(badgeRing, max(2f, r * 0.075f)))

        val strokePaint = iconStrokePaint.apply {
            color = accentColor
            strokeWidth = max(2.2f, r * 0.105f)
        }
        val fillPaint = iconFillPaint.apply { color = accentColor }
        val whiteFill = iconWhiteFill
        val whiteStroke = iconWhiteStroke.apply { strokeWidth = max(2f, r * 0.09f) }

        when (iconType) {
            GaugeIconType.HV_BATTERY -> {
                val bw = r * 0.78f
                val bh = r * 0.96f
                val bodyRect = RectF(cx - bw / 2f, cy - bh * 0.42f, cx + bw / 2f, cy + bh * 0.54f)
                shapeRect.set(cx - bw * 0.24f, bodyRect.top - r * 0.16f, cx + bw * 0.24f, bodyRect.top)
                canvas.drawRoundRect(shapeRect, 3f, 3f, fillPaint)
                canvas.drawRoundRect(bodyRect, r * 0.12f, r * 0.12f, strokePaint)

                val barMargin = bw * 0.18f
                val barH = bh * 0.15f
                val gap = bh * 0.07f
                for (b in 0..2) {
                    val by2 = bodyRect.bottom - bh * 0.12f - b * (barH + gap)
                    val by1 = by2 - barH
                    shapeRect.set(bodyRect.left + barMargin, by1, bodyRect.right - barMargin, by2)
                    canvas.drawRoundRect(shapeRect, 3f, 3f, if (b == 2) whiteFill else fillPaint)
                }
            }

            GaugeIconType.HV_POWER -> {
                val bolt = iconPath.apply {
                    reset()
                    moveTo(cx + r * 0.10f, cy - r * 0.62f)
                    lineTo(cx - r * 0.36f, cy + r * 0.04f)
                    lineTo(cx - r * 0.02f, cy + r * 0.04f)
                    lineTo(cx - r * 0.14f, cy + r * 0.62f)
                    lineTo(cx + r * 0.38f, cy - r * 0.06f)
                    lineTo(cx + r * 0.04f, cy - r * 0.06f)
                    close()
                }
                canvas.drawPath(bolt, fillPaint)
                canvas.drawPath(bolt, whiteStroke.apply { strokeWidth = max(1.5f, r * 0.055f) })
            }

            GaugeIconType.BATTERY_TEMP -> {
                val tx = cx - r * 0.18f
                val bulbR = r * 0.22f
                val bulbCy = cy + r * 0.34f
                val stemW = r * 0.18f
                val stemTop = cy - r * 0.52f

                shapeRect.set(tx - stemW / 2f, stemTop, tx + stemW / 2f, bulbCy)
                canvas.drawRoundRect(shapeRect, stemW / 2f, stemW / 2f, strokePaint)
                canvas.drawCircle(tx, bulbCy, bulbR, fillPaint)
                canvas.drawLine(tx, bulbCy - bulbR * 0.3f, tx, cy - r * 0.25f, whiteStroke)

                for (i in 0..2) {
                    val wy = cy - r * 0.36f + i * r * 0.28f
                    val wave = iconPath.apply {
                        reset()
                        moveTo(cx + r * 0.12f, wy)
                        quadTo(cx + r * 0.28f, wy - r * 0.09f, cx + r * 0.42f, wy)
                        quadTo(cx + r * 0.54f, wy + r * 0.09f, cx + r * 0.64f, wy)
                    }
                    canvas.drawPath(wave, strokePaint)
                }
            }

            GaugeIconType.ENGINE_ICE -> {
                val blockRect = shapeRect.apply { set(cx - r * 0.42f, cy - r * 0.26f, cx + r * 0.38f, cy + r * 0.36f) }
                canvas.drawRoundRect(blockRect, r * 0.10f, r * 0.10f, strokePaint)
                canvas.drawLine(cx - r * 0.24f, cy - r * 0.46f, cx + r * 0.20f, cy - r * 0.46f, strokePaint)
                canvas.drawLine(cx - r * 0.02f, cy - r * 0.46f, cx - r * 0.02f, blockRect.top, strokePaint)
                canvas.drawLine(cx - r * 0.60f, cy - r * 0.12f, cx - r * 0.60f, cy + r * 0.22f, strokePaint)
                canvas.drawLine(cx - r * 0.60f, cy + r * 0.05f, blockRect.left, cy + r * 0.05f, strokePaint)
                canvas.drawLine(blockRect.right, cy - r * 0.05f, cx + r * 0.58f, cy - r * 0.05f, strokePaint)
                canvas.drawLine(cx + r * 0.58f, cy - r * 0.20f, cx + r * 0.58f, cy + r * 0.12f, strokePaint)
                canvas.drawCircle(cx - r * 0.02f, cy + r * 0.05f, r * 0.13f, whiteStroke)
            }

            GaugeIconType.FUEL_GEAR -> {
                // Pompa carburante stilizzata a sinistra + selettore innesto marcia a destra
                val pumpRect = RectF(cx - r * 0.48f, cy - r * 0.42f, cx + r * 0.10f, cy + r * 0.46f)
                canvas.drawRoundRect(pumpRect, r * 0.08f, r * 0.08f, strokePaint)
                // Finestrella display pompa
                shapeRect.set(pumpRect.left + r * 0.10f, pumpRect.top + r * 0.10f, pumpRect.right - r * 0.10f, cy - r * 0.04f)
                canvas.drawRoundRect(shapeRect, r * 0.04f, r * 0.04f, fillPaint)
                // Tubo / pistola erogatore a destra
                val hose = iconPath.apply {
                    reset()
                    moveTo(pumpRect.right, cy - r * 0.18f)
                    lineTo(cx + r * 0.34f, cy - r * 0.18f)
                    lineTo(cx + r * 0.34f, cy + r * 0.22f)
                    lineTo(cx + r * 0.20f, cy + r * 0.32f)
                }
                canvas.drawPath(hose, whiteStroke)
                // Base pompa
                canvas.drawLine(pumpRect.left - r * 0.08f, pumpRect.bottom, pumpRect.right + r * 0.08f, pumpRect.bottom, strokePaint)
                // Goccia/indicatore bianco al centro
                canvas.drawCircle(pumpRect.centerX(), cy + r * 0.22f, r * 0.09f, whiteFill)
            }

            GaugeIconType.AUX_12V -> {
                shapeRect.set(cx - r * 0.56f, cy - r * 0.28f, cx + r * 0.56f, cy + r * 0.46f)
                canvas.drawRoundRect(shapeRect, r * 0.10f, r * 0.10f, strokePaint)
                canvas.drawRect(cx - r * 0.40f, shapeRect.top - r * 0.16f, cx - r * 0.18f, shapeRect.top, fillPaint)
                canvas.drawRect(cx + r * 0.18f, shapeRect.top - r * 0.16f, cx + r * 0.40f, shapeRect.top, fillPaint)

                val symY = cy + r * 0.09f
                val arm = r * 0.13f
                canvas.drawLine(cx - r * 0.29f - arm, symY, cx - r * 0.29f + arm, symY, whiteStroke)
                canvas.drawLine(cx + r * 0.29f - arm, symY, cx + r * 0.29f + arm, symY, whiteStroke)
                canvas.drawLine(cx + r * 0.29f, symY - arm, cx + r * 0.29f, symY + arm, whiteStroke)
            }
        }
    }

    /**
     * Barra Footer Inferiore Unificata e Compatta:
     * Contiene il pallino di stato, il titolo app, la modalità di guida (EV Puro / Ibrido HEV),
     * lo stato della connessione OBD, l'indicatore della pagina (1/4..4/4) e la firma "Made with ❤️ by Cristiano Casadei".
     * Se i dati non sono live (mai ricevuti o fermi da oltre [STALE_DATA_MS]) lo segnala e spegne il pallino EV/HEV.
     */
    private fun drawBottomFooterBar(
        canvas: Canvas,
        rect: RectF,
        s: NiroTelemetryState,
        mode: Int
    ) {
        val h = rect.height()
        val w = rect.width()
        canvas.drawRoundRect(rect, h * 0.35f, h * 0.35f, fill(COLOR_CARD))
        canvas.drawRoundRect(rect, h * 0.35f, h * 0.35f, stroke(COLOR_CARD_BORDER, max(1.4f, h * 0.035f)))

        val now = System.currentTimeMillis()
        val dataWarning = when {
            s.isDemoMode -> null
            s.lastLiveDataMs == 0L -> "⚠ DATI NON LIVE"
            now - s.lastLiveDataMs > STALE_DATA_MS -> "⚠ DATI FERMI DA ${(now - s.lastLiveDataMs) / 1000L}s"
            else -> null
        }

        // Pallino luminoso stato EV / HEV (grigio se i dati non sono aggiornati)
        val dotRadius = h * 0.18f
        val dotCx = rect.left + h * 0.48f
        val dotCy = rect.centerY()
        val dotColor = when {
            dataWarning != null -> COLOR_STALE
            s.isEvMode -> COLOR_GREEN
            else -> COLOR_AMBER
        }
        canvas.drawCircle(dotCx, dotCy, dotRadius, fill(dotColor))

        val pageLabel = when (mode) {
            MODE_GAUGES_6 -> "1/4 Gauge"
            MODE_CELLS_96 -> "2/4 96 Celle"
            MODE_COCKPIT_LIVE -> "3/4 Cockpit"
            else -> "4/4 Viaggio"
        }
        val driveMode = if (s.isEvMode) "EV PURO" else "IBRIDO HEV (${s.iceRpm} RPM)"
        val leftSummary = if (dataWarning != null) {
            "NIRO MONITOR  •  $dataWarning  •  ${s.connectionStatus}  •  [$pageLabel]"
        } else {
            "NIRO MONITOR  •  $driveMode  •  ${s.connectionStatus}  •  [$pageLabel]"
        }

        val textX = dotCx + dotRadius + h * 0.28f
        drawAutoFitText(
            canvas = canvas,
            text = leftSummary,
            x = textX,
            y = rect.centerY() + h * 0.13f,
            maxWidth = w * 0.68f - (textX - rect.left),
            paint = text(COLOR_FOOTER_TEXT, h * 0.38f, bold = true)
        )

        drawAutoFitText(
            canvas = canvas,
            text = "Made with ❤️ by Cristiano Casadei",
            x = rect.right - h * 0.42f,
            y = rect.centerY() + h * 0.12f,
            maxWidth = w * 0.29f,
            paint = text(COLOR_MINT, h * 0.35f, bold = true, align = Paint.Align.RIGHT)
        )
    }

    /**
     * VISTA 2: 4 Card Dati a sinistra + Grande Istogramma 12 Gruppi (96 Celle) & Ripartizione EV/HEV a destra.
     */
    private fun drawMode96Cells(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        s: NiroTelemetryState
    ) {
        val gapX = width * 0.016f
        val gapY = height * 0.025f
        val leftColW = width * 0.43f
        val rightColW = width - leftColW - gapX

        val rows = listOf(
            "TENSIONE CELLE MIN / MAX (96 CELLE)" to "Min: %.3f V (#%d)  •  Max: %.3f V (#%d)".format(
                s.cellVoltageMin, s.cellVoltageMinIndex, s.cellVoltageMax, s.cellVoltageMaxIndex
            ),
            "SBILANCIAMENTO CELLE (ΔV) & SALUTE SOH" to "ΔV: ${s.cellDeltaMv} mV (${if (s.cellDeltaMv <= 20) "Bilanciato" else "Attenzione"})  •  SOH: ${s.soh}%",
            "RAFFREDDAMENTO & 4 MODULI BATTERIA HV" to "Circuito: ${s.coolantInletTemp}°C  |  M1:${s.moduleTemps.getOrElse(0) { 0 }}° M2:${s.moduleTemps.getOrElse(1) { 0 }}° M3:${s.moduleTemps.getOrElse(2) { 0 }}° M4:${s.moduleTemps.getOrElse(3) { 0 }}°",
            "STORICO TOTALE ENERGIA BATTERIA (DA KM ZERO)" to "Tot. Caricata: ${s.cumulativeChargedKwh} kWh  •  Tot. Erogata: ${s.cumulativeDischargedKwh} kWh"
        )

        val rowH = (height - gapY * 3f) / 4f
        val borderW = max(1.5f, rowH * 0.02f)

        for (i in rows.indices) {
            val ry = top + i * (rowH + gapY)
            cardRect.set(left, ry, left + leftColW, ry + rowH)
            canvas.drawRoundRect(cardRect, rowH * 0.14f, rowH * 0.14f, fill(COLOR_CARD))
            canvas.drawRoundRect(cardRect, rowH * 0.14f, rowH * 0.14f, stroke(COLOR_CARD_BORDER, borderW))

            drawAutoFitText(
                canvas, rows[i].first, cardRect.left + rowH * 0.22f, cardRect.top + rowH * 0.38f, leftColW - rowH * 0.44f,
                text(COLOR_MINT, rowH * 0.22f, bold = true)
            )
            drawAutoFitText(
                canvas, rows[i].second, cardRect.left + rowH * 0.22f, cardRect.top + rowH * 0.76f, leftColW - rowH * 0.44f,
                text(Color.WHITE, rowH * 0.25f, bold = true)
            )
        }

        // Pannello destro: Istogramma 12 Gruppi + Barre EV/HEV e Termica
        val rLeft = left + leftColW + gapX
        panelRect.set(rLeft, top, rLeft + rightColW, top + height)
        canvas.drawRoundRect(panelRect, height * 0.04f, height * 0.04f, fill(COLOR_CARD))
        canvas.drawRoundRect(panelRect, height * 0.04f, height * 0.04f, stroke(COLOR_CARD_BORDER, borderW))

        val pad = rightColW * 0.04f
        drawAutoFitText(
            canvas,
            "ISTOGRAMMA 12 GRUPPI CELLE (96 CELLE HV - MEDIA ${"%.3f".format((s.cellVoltageMin + s.cellVoltageMax) / 2f)}V)",
            rLeft + pad,
            top + height * 0.085f,
            rightColW - pad * 2f,
            text(COLOR_MINT, height * 0.046f, bold = true)
        )

        val chartTop = top + height * 0.125f
        val chartBottom = top + height * 0.60f
        chartRect.set(rLeft + pad, chartTop, panelRect.right - pad, chartBottom)
        canvas.drawRoundRect(chartRect, 14f, 14f, fill(COLOR_PLOT_BG))

        val groups = 12
        val slotW = (chartRect.width() - pad) / groups
        val minV = s.cellVoltageMin - 0.01f
        val maxSpan = (s.cellVoltageMax - minV + 0.01f).coerceAtLeast(0.02f)
        val labelSize = min(slotW * 0.36f, chartRect.height() * 0.09f)
        val cells = s.cellVoltages

        for (g in 0 until groups) {
            // Media del gruppo di 8 celle senza liste intermedie
            var sum = 0.0
            var count = 0
            for (k in g * 8 until min(g * 8 + 8, cells.size)) {
                sum += cells[k]
                count++
            }
            val avgV = if (count > 0) (sum / count).toFloat() else s.cellVoltageMin
            val norm = ((avgV - minV) / maxSpan).coerceIn(0.28f, 0.94f)
            val maxBarH = chartRect.height() * 0.74f
            val barH = maxBarH * norm

            val bx1 = chartRect.left + pad * 0.5f + g * slotW + slotW * 0.12f
            val bx2 = bx1 + slotW * 0.76f
            val by2 = chartRect.bottom - chartRect.height() * 0.16f
            val by1 = by2 - barH

            barRect.set(bx1, by1, bx2, by2)
            canvas.drawRoundRect(barRect, 6f, 6f, fill(if (norm > 0.82f) COLOR_AMBER else COLOR_GREEN))
            canvas.drawText(
                "G${g + 1}",
                (bx1 + bx2) / 2f,
                chartRect.bottom - chartRect.height() * 0.04f,
                text(COLOR_AXIS_LABEL, labelSize, align = Paint.Align.CENTER)
            )
        }

        val evPct = s.evTripRatioPct.coerceIn(0, 100)
        val now = System.currentTimeMillis()
        val isConfirmingReset = now < evResetConfirmDeadlineMs
        val isResetJustDone = now < evResetDoneBannerUntilMs

        val tripTitle = when {
            isConfirmingReset -> {
                val secLeft = ((evResetConfirmDeadlineMs - now) / 1000L + 1L).coerceIn(1L, 5L)
                "⚠️ CONFERMI AZZERAMENTO VIAGGIO? TOCCA DI NUOVO QUI (${secLeft}s)"
            }
            isResetJustDone -> "✓ CONTATORE VIAGGIO AZZERATO (RIPARTITO DA ZERO)!"
            else -> "Tragitto Viaggio: $evPct% EV Puro / ${100 - evPct}% Termico   [↻ TOCCA QUI PER AZZERARE]"
        }
        val tripTitleColor = when {
            isConfirmingReset -> COLOR_AMBER
            isResetJustDone -> COLOR_GREEN
            else -> Color.WHITE
        }
        drawAutoFitText(
            canvas,
            tripTitle,
            chartRect.left,
            top + height * 0.68f,
            chartRect.width(),
            text(tripTitleColor, height * 0.043f, bold = true)
        )

        val evBarLeft = chartRect.left
        val evBarRight = chartRect.right
        barRect.set(evBarLeft, top + height * 0.715f, evBarRight, top + height * 0.785f)
        val evBarRadius = barRect.height() / 2f
        // Salva l'area interattiva (titolo + barra) per catturare il tocco di azzeramento con conferma
        evResetHitRect.set(
            chartRect.left - 12f,
            top + height * 0.63f,
            chartRect.right + 12f,
            top + height * 0.805f
        )

        canvas.drawRoundRect(barRect, evBarRadius, evBarRadius, fill(if (isConfirmingReset) COLOR_HEV_DIM else COLOR_RED))
        val evBarTop = barRect.top
        val evBarBottom = barRect.bottom
        barRect.set(evBarLeft, evBarTop, evBarLeft + (evBarRight - evBarLeft) * (evPct / 100f), evBarBottom)
        canvas.drawRoundRect(barRect, evBarRadius, evBarRadius, fill(if (isConfirmingReset) COLOR_AMBER else COLOR_GREEN))
        if (isConfirmingReset) {
            barRect.set(evBarLeft, evBarTop, evBarRight, evBarBottom)
            canvas.drawRoundRect(barRect, evBarRadius, evBarRadius, stroke(Color.WHITE, 2.5f))
        }

        // Seconda Barra (Azzurra + Riserva Ambra 15.5%): Carica Batteria BMS & Quota Utile EV sopra la soglia 15.5%
        drawAutoFitText(
            canvas,
            "Carica Batteria BMS: ${s.socBms}%  →  Quota EV Utile: ${s.usableEvSocPct}% (~${s.estimatedEvRangeKm} km)  |  Riserva Termico: 15.5%",
            chartRect.left,
            top + height * 0.865f,
            chartRect.width(),
            text(Color.WHITE, height * 0.043f, bold = true)
        )
        val coolLeft = chartRect.left
        val coolRight = chartRect.right
        val coolTop = top + height * 0.90f
        val coolBottom = top + height * 0.968f
        val coolRadius = (coolBottom - coolTop) / 2f
        val coolWidth = coolRight - coolLeft

        // Sfondo 0..100%
        barRect.set(coolLeft, coolTop, coolRight, coolBottom)
        canvas.drawRoundRect(barRect, coolRadius, coolRadius, fill(COLOR_BAR_TRACK))

        // Barra Azzurra carica totale BMS attuale (0..socBms%)
        val socRatio = (s.socBms / 100f).coerceIn(0.02f, 1f)
        barRect.set(coolLeft, coolTop, coolLeft + coolWidth * socRatio, coolBottom)
        canvas.drawRoundRect(barRect, coolRadius, coolRadius, fill(COLOR_BLUE))

        // Fascia Ambra 0..15.5% (Riserva Ibrido HEV sotto cui si accende il motore termico)
        val floorRatio = NiroTelemetryState.HEV_FLOOR_BMS_SOC / 100f
        barRect.set(coolLeft, coolTop, coolLeft + coolWidth * min(socRatio, floorRatio), coolBottom)
        canvas.drawRoundRect(barRect, coolRadius, coolRadius, fill(COLOR_AMBER))

        // Tacca bianca di demarcazione sulla soglia 15.5%
        val tickX = coolLeft + coolWidth * floorRatio
        canvas.drawLine(tickX, coolTop - 3f, tickX, coolBottom + 3f, line(Color.WHITE, max(2f, (coolBottom - coolTop) * 0.12f)))
    }

    /**
     * VISTA 3: Full Cockpit Live (Manometro Gigante Potenza/Regen + Curva Potenza 60s + 4 Moduli Batteria).
     */
    private fun drawModeCockpitLive(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        s: NiroTelemetryState
    ) {
        val gapX = width * 0.016f
        val gapY = height * 0.026f
        val topH = height * 0.64f
        val botH = height - topH - gapY

        val leftW = width * 0.37f
        val borderW = max(1.5f, height * 0.004f)

        // 1. Box Sinistro: Manometro Gigante Potenza / Rigenerazione
        cardRect.set(left, top, left + leftW, top + topH)
        val sign = if (s.hvPowerKw > 0f) "+" else ""
        val kwColor = if (s.hvPowerKw < 0f) COLOR_BLUE else COLOR_GREEN
        drawGaugeCard(
            canvas = canvas,
            rect = cardRect,
            title = "POTENZA ISTANTANEA HV",
            iconType = GaugeIconType.HV_POWER,
            progress = (abs(s.hvPowerKw) / 65f).coerceIn(0.03f, 1f),
            mainValue = "$sign${s.hvPowerKw} kW",
            unitLabel = "${"%.1f".format(s.instantEvKwh100km)} kWh/100km",
            footer = "BMS ${s.socBms}%  •  Autonomia EV ~${s.estimatedEvRangeKm} km",
            activeColor = kwColor
        )

        // 2. Box Destro: Grafico Temporale Potenza & Rigenerazione Live (60s)
        panelRect.set(left + leftW + gapX, top, left + width, top + topH)
        val corner = min(panelRect.width(), panelRect.height()) * 0.06f
        canvas.drawRoundRect(panelRect, corner, corner, fill(COLOR_CARD))
        canvas.drawRoundRect(panelRect, corner, corner, stroke(COLOR_CARD_BORDER, borderW))

        val pad = panelRect.width() * 0.04f
        drawAutoFitText(
            canvas,
            "CURVA POTENZA (60s)  •  MARCIA: ${s.currentGear} (${s.vehicleSpeedKmh} km/h)  •  GDi: ${"%.1f".format(s.instantFuelL100km)} L/100km (${s.iceCoolantTemp}°C)",
            panelRect.left + pad,
            panelRect.top + topH * 0.14f,
            panelRect.width() - pad * 2f,
            text(COLOR_MINT, topH * 0.075f, bold = true)
        )

        chartRect.set(
            panelRect.left + pad,
            panelRect.top + topH * 0.21f,
            panelRect.right - pad,
            panelRect.bottom - topH * 0.08f
        )
        canvas.drawRoundRect(chartRect, 14f, 14f, fill(COLOR_PLOT_BG))

        val zeroY = chartRect.centerY()
        canvas.drawLine(chartRect.left + 8f, zeroY, chartRect.right - 8f, zeroY, line(COLOR_ZERO_LINE, 2f))

        val history = s.powerHistoryKw
        if (history.size >= 2) {
            chartPath.reset()
            val stepX = (chartRect.width() - 24f) / (history.size - 1)
            val maxPlotAmp = chartRect.height() * 0.42f
            history.forEachIndexed { idx, kw ->
                val px = chartRect.left + 12f + idx * stepX
                val py = (zeroY - (kw / 50f).coerceIn(-1f, 1f) * maxPlotAmp)
                if (idx == 0) chartPath.moveTo(px, py) else chartPath.lineTo(px, py)
            }
            chartLinePaint.strokeWidth = max(3.5f, chartRect.height() * 0.025f)
            canvas.drawPath(chartPath, chartLinePaint)
        }

        // 3. Box Inferiore a tutta larghezza: 4 Moduli Batteria + Aux 12V + Consumo
        panelRect.set(left, top + topH + gapY, left + width, top + height)
        canvas.drawRoundRect(panelRect, botH * 0.12f, botH * 0.12f, fill(COLOR_CARD))
        canvas.drawRoundRect(panelRect, botH * 0.12f, botH * 0.12f, stroke(COLOR_CARD_BORDER, borderW))

        drawAutoFitText(
            canvas,
            "TEMPERATURE 4 MODULI BATTERIA HV (${s.batteryTempAvg}°C)  •  CIRCUITO: ${s.coolantInletTemp}°C  •  AUX 12V: ${s.aux12vVoltage}V  •  ΔV CELLE: ${s.cellDeltaMv} mV",
            panelRect.left + pad,
            panelRect.top + botH * 0.26f,
            panelRect.width() - pad * 2f,
            text(COLOR_MINT, botH * 0.16f, bold = true)
        )

        val modSlotW = (panelRect.width() - pad * 2f) / 4f

        for (i in 0 until 4) {
            val temp = s.moduleTemps.getOrElse(i) { 19 }
            val mx1 = panelRect.left + pad + i * modSlotW
            val mx2 = mx1 + modSlotW - pad * 0.6f
            drawAutoFitText(
                canvas, "Modulo ${i + 1}: ${temp}°C", mx1, panelRect.top + botH * 0.56f, mx2 - mx1,
                text(Color.WHITE, botH * 0.17f, bold = true)
            )

            val barTop = panelRect.top + botH * 0.66f
            val barBottom = panelRect.top + botH * 0.86f
            val barRadius = (barBottom - barTop) / 2f
            barRect.set(mx1, barTop, mx2, barBottom)
            canvas.drawRoundRect(barRect, barRadius, barRadius, fill(COLOR_PLOT_BG))

            val ratio = ((temp + 5f) / 55f).coerceIn(0.15f, 1f)
            barRect.set(mx1, barTop, mx1 + (mx2 - mx1) * ratio, barBottom)
            canvas.drawRoundRect(barRect, barRadius, barRadius, fill(if (temp > 34) COLOR_AMBER else COLOR_GREEN))
        }
    }

    /**
     * VISTA 4: totalizzatore dei viaggi dall'ultimo azzeramento, in quattro card: distanza con ripartizione
     * elettrico/ibrido, energia netta della batteria HV, benzina stimata e benzina risparmiata grazie all'EV.
     */
    private fun drawModeTrip(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        m: TripPageModel
    ) {
        val headerH = height * 0.075f
        val headerGap = height * 0.022f
        val gapX = width * 0.009f
        val gapY = height * 0.026f
        val gridTop = top + headerH + headerGap
        val cardW = (width - gapX) / 2f
        val cardH = (height - headerH - headerGap - gapY) / 2f

        // Intestazione: stato, inizio del totalizzatore, viaggi e tempo di guida a sinistra, suggerimento a destra.
        // Due tocchi sull'intestazione azzerano il totalizzatore.
        val now = System.currentTimeMillis()
        val confirming = now < tripResetConfirmDeadlineMs
        val justReset = now < tripResetDoneBannerUntilMs
        val chip = when {
            confirming -> "CONFERMI?"
            justReset -> "AZZERATO"
            else -> m.chip
        }
        val chipColor = when {
            confirming -> COLOR_AMBER
            justReset || m.status == TripPageModel.Status.DRIVING -> COLOR_GREEN
            m.status == TripPageModel.Status.PAUSED -> COLOR_CARD_FOOTER
            else -> COLOR_STALE
        }
        val headerCy = top + headerH / 2f
        val headerText = headerH * 0.50f
        val baseline = headerCy + headerText * 0.36f
        val chipPadX = headerH * 0.40f
        val dotR = headerH * 0.14f
        val chipTextX = left + chipPadX + dotR * 2f + headerH * 0.24f
        val chipTextW = text(chipColor, headerText * 0.92f, bold = true).measureText(chip)
        shapeRect.set(left, top + headerH * 0.04f, chipTextX + chipTextW + chipPadX, top + headerH * 0.96f)
        val chipRadius = shapeRect.height() / 2f
        canvas.drawRoundRect(shapeRect, chipRadius, chipRadius, stroke(chipColor, max(1.5f, headerH * 0.055f)))
        canvas.drawCircle(left + chipPadX + dotR, headerCy, dotR, fill(chipColor))
        canvas.drawText(chip, chipTextX, baseline, text(chipColor, headerText * 0.92f, bold = true))

        // Suggerimento per azzerare a destra, più tenue, solo se c'è qualcosa da azzerare
        val hint = if (m.canReset && !confirming && !justReset) "↻ tocca qui per azzerare" else ""
        val hintPaint = text(COLOR_STALE, headerText, bold = true, align = Paint.Align.RIGHT)
        val hintW = min(hintPaint.measureText(hint), width * 0.30f)
        if (hint.isNotEmpty()) drawAutoFitText(canvas, hint, left + width, baseline, width * 0.30f, hintPaint)

        val periodX = shapeRect.right + headerH * 0.45f
        val periodMaxW = left + width - hintW - headerH * 0.6f - periodX
        val (periodText, periodColor) = when {
            confirming -> {
                val secLeft = ((tripResetConfirmDeadlineMs - now) / 1000L + 1L).coerceIn(1L, 5L)
                "Tocca di nuovo qui entro $secLeft s per azzerare il totalizzatore" to COLOR_AMBER
            }
            justReset -> "Totalizzatore azzerato: riparte con il prossimo viaggio" to COLOR_GREEN
            else -> m.period to COLOR_FOOTER_TEXT
        }
        drawAutoFitText(canvas, periodText, periodX, baseline, periodMaxW, text(periodColor, headerText, bold = true))

        // Area del tocco di azzeramento: l'intestazione allargata in altezza (~48 dp) per centrarla con il dito
        if (m.canReset || confirming || justReset) {
            tripResetHitRect.set(left, top - headerH * 0.4f, left + width, gridTop + headerGap * 0.5f)
        } else {
            tripResetHitRect.setEmpty()
        }

        val hasData = m.status != TripPageModel.Status.EMPTY

        // 1. Distanza con barra elettrico / ibrido
        cardRect.set(left, gridTop, left + cardW, gridTop + cardH)
        drawTripCard(canvas, cardRect, COLOR_GREEN, "DISTANZA", m.distance, "km", Color.WHITE)
        if (hasData) {
            val padX = cardH * 0.11f
            val barTop = cardRect.top + cardH * 0.69f
            val barH = cardH * 0.06f
            barRect.set(cardRect.left + padX, barTop, cardRect.right - padX, barTop + barH)
            canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, fill(COLOR_BAR_TRACK))
            // Verde per i km coperti con l'energia della batteria, ambra per quelli a benzina (ibrido);
            // a totalizzatore appena azzerato resta il binario vuoto
            val electricShare = m.electricShare.coerceIn(0f, 1f)
            val electricRight = barRect.left + barRect.width() * electricShare
            val hybridRight = electricRight + barRect.width() * m.hybridShare.coerceIn(0f, 1f - electricShare)
            canvas.save()
            canvas.clipRect(barRect.left, barRect.top, electricRight, barRect.bottom)
            canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, fill(COLOR_GREEN))
            canvas.restore()
            canvas.save()
            canvas.clipRect(electricRight, barRect.top, hybridRight, barRect.bottom)
            canvas.drawRoundRect(barRect, barH / 2f, barH / 2f, fill(COLOR_AMBER))
            canvas.restore()
            drawTripDetail(canvas, cardRect, m.electricKm, COLOR_GREEN, m.hybridKm, COLOR_AMBER)
        }

        // 2. Energia netta della batteria HV
        cardRect.set(left + cardW + gapX, gridTop, left + width, gridTop + cardH)
        drawTripCard(canvas, cardRect, COLOR_BLUE, "BATTERIA HV", m.batteryNet, "kWh netti", Color.WHITE)
        drawTripDetail(canvas, cardRect, m.batteryDetail, COLOR_CARD_FOOTER)

        // 3. Benzina stimata dal MAF
        val row2Top = gridTop + cardH + gapY
        cardRect.set(left, row2Top, left + cardW, row2Top + cardH)
        drawTripCard(canvas, cardRect, COLOR_AMBER, "BENZINA", m.fuel, "L", Color.WHITE)
        drawTripDetail(canvas, cardRect, m.fuelDetail, COLOR_CARD_FOOTER)

        // 4. Benzina risparmiata rispetto alla guida solo in ibrido
        cardRect.set(left + cardW + gapX, row2Top, left + width, row2Top + cardH)
        drawTripCard(
            canvas, cardRect, COLOR_MINT, "RISPARMIATI CON L'EV", m.saved, "L",
            if (hasData) COLOR_GREEN else Color.WHITE
        )
        drawTripDetail(canvas, cardRect, m.savedDetail, COLOR_CARD_FOOTER)
    }

    /** Card della pagina Viaggio: pallino colorato e titolo in alto, valore grande con unità al centro. */
    private fun drawTripCard(
        canvas: Canvas,
        rect: RectF,
        badgeColor: Int,
        title: String,
        value: String,
        unit: String,
        valueColor: Int
    ) {
        val w = rect.width()
        val h = rect.height()
        val corner = min(w, h) * 0.08f
        canvas.drawRoundRect(rect, corner, corner, fill(COLOR_CARD))
        canvas.drawRoundRect(rect, corner, corner, stroke(COLOR_CARD_BORDER, max(1.5f, h * 0.011f)))

        val padX = h * 0.11f
        val titleSize = h * 0.078f
        val titleBaseline = rect.top + h * 0.20f
        val badgeR = titleSize * 0.42f
        canvas.drawCircle(rect.left + padX + badgeR, titleBaseline - titleSize * 0.36f, badgeR, fill(badgeColor))
        drawAutoFitText(
            canvas, title, rect.left + padX + badgeR * 2f + titleSize * 0.6f, titleBaseline,
            w - padX * 2f - badgeR * 2f - titleSize * 0.6f, text(COLOR_MINT, titleSize, bold = true)
        )

        // Valore e unità ridotti insieme se non stanno nella larghezza della card
        val gap = h * 0.04f
        val valueW = text(valueColor, h * 0.27f, bold = true).measureText(value)
        val unitW = text(COLOR_CARD_TITLE, h * 0.115f, bold = true).measureText(unit)
        val available = w - padX * 2f
        val total = valueW + gap + unitW
        val scale = if (total > available && total > 0f) available / total else 1f
        val valueBaseline = rect.top + h * 0.60f
        val x = rect.left + padX
        canvas.drawText(value, x, valueBaseline, text(valueColor, h * 0.27f * scale, bold = true))
        canvas.drawText(
            unit, x + (valueW + gap) * scale, valueBaseline,
            text(COLOR_CARD_TITLE, h * 0.115f * scale, bold = true)
        )
    }

    /**
     * Riga di dettaglio in basso nella card: [first] e, se presente, [second] separati da un punto, ciascuno nel
     * proprio colore; tutta la riga viene ridotta se non sta nella card.
     */
    private fun drawTripDetail(
        canvas: Canvas,
        rect: RectF,
        first: String,
        firstColor: Int,
        second: String = "",
        secondColor: Int = COLOR_CARD_FOOTER
    ) {
        if (first.isEmpty()) return
        val h = rect.height()
        val padX = h * 0.11f
        val separator = if (second.isEmpty()) "" else "  •  "
        val paint = text(firstColor, h * 0.088f)
        val firstW = paint.measureText(first)
        val sepW = paint.measureText(separator)
        val secondW = paint.measureText(second)
        val available = rect.width() - padX * 2f
        val total = firstW + sepW + secondW
        val scale = if (total > available && total > 0f) available / total else 1f
        paint.textSize *= scale
        val y = rect.top + h * 0.89f
        var x = rect.left + padX
        canvas.drawText(first, x, y, paint)
        if (second.isEmpty()) return
        x += firstW * scale
        paint.color = COLOR_CARD_FOOTER
        canvas.drawText(separator, x, y, paint)
        x += sepW * scale
        paint.color = secondColor
        canvas.drawText(second, x, y, paint)
    }

    /**
     * Disegna [text] riducendo la dimensione di [paint] quanto basta per stare in [maxWidth].
     * La larghezza del testo è proporzionale alla dimensione del font, quindi basta una sola misura
     * (prima: un loop di riduzioni del 8% con una measureText per passo). L'allineamento è quello del paint.
     */
    private fun drawAutoFitText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint
    ) {
        val width = paint.measureText(text)
        if (width > maxWidth && maxWidth > 0f && paint.textSize > MIN_TEXT_SIZE) {
            paint.textSize = max(MIN_TEXT_SIZE, paint.textSize * (maxWidth / width) * 0.99f)
        }
        canvas.drawText(text, x, y, paint)
    }
}
