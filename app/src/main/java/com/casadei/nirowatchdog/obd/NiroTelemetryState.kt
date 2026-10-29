package com.casadei.nirowatchdog.obd

import kotlin.math.roundToInt

/**
 * Stato telemetrico completo della Kia Niro SG2 PHEV (2024 - 11.1 kWh, 96 celle, Cambio 6-DCT).
 * Usa il SoC BMS (7E4 -> 220101 d[4]/2) come riferimento primario; sotto il ~18% BMS l'auto
 * entra in ibrido forzato (HEV).
 */
data class NiroTelemetryState(
    val socBms: Float = 67.5f,
    val socDisplay: Float = 72.0f,
    val soh: Float = 100.0f,
    val hvVoltage: Float = 371.4f,
    val hvCurrent: Float = 1.5f,
    val hvPowerKw: Float = 0.6f,
    val batteryTempAvg: Float = 20.5f,
    val batteryTempMin: Int = 20,
    val batteryTempMax: Int = 21,
    val coolantInletTemp: Float = 15.0f,
    val moduleTemps: List<Int> = listOf(20, 20, 21, 20),
    val cellVoltageMin: Float = 3.860f,
    val cellVoltageMinIndex: Int = 1,
    val cellVoltageMax: Float = 3.860f,
    val cellVoltageMaxIndex: Int = 5,
    val cellVoltages: List<Float> = List(96) { 3.860f },
    val iceCoolantTemp: Int = 38,
    val iceRpm: Int = 0,
    val vehicleSpeedKmh: Int = 0,
    val engineLoadPct: Int = 0,
    val instantFuelLh: Float = 0.0f,
    val instantFuelL100km: Float = 0.0f,
    val lastMeasuredFuelLh: Float? = null,
    val smoothedFuelLh: Float = 0.0f,
    val smoothedFuelL100km: Float = 0.0f,
    val lastFuelDisplayUpdateMs: Long = 0L,
    val instantEvKwh100km: Float = 0.0f,
    val currentGear: String = "P / N",
    val aux12vVoltage: Float = 14.3f,
    val ldcActive: Boolean = true,
    val odometerKm: Int = 0,
    val adasCameraStatusHex: String = "0E",
    val cumulativeChargedKwh: Float = 0.0f,
    val cumulativeDischargedKwh: Float = 0.0f,
    val evTripRatioPct: Int = 100,
    /** Campioni del viaggio corrente (tutti / in EV puro) da cui deriva [evTripRatioPct] */
    val tripSamplesTotal: Int = 0,
    val tripSamplesEv: Int = 0,
    val powerHistoryKw: List<Float> = List(POWER_HISTORY_SIZE) { 0f },
    val connectionStatus: String = "Pronto",
    val isDemoMode: Boolean = false,
    /** Istante (System.currentTimeMillis) dell'ultima risposta BMS 220101 valida; 0 = nessun dato live ricevuto */
    val lastLiveDataMs: Long = 0L
) {
    companion object {
        /** Soglia reale SoC BMS (15.5%) verificata su strada in cui la Niro SG2 PHEV avvia il termico (HEV) */
        const val HEV_FLOOR_BMS_SOC = 15.5f

        /** Campioni della curva di potenza: 75 cicli live da ~800 ms = ~60 s */
        const val POWER_HISTORY_SIZE = 75
    }

    val cellDeltaMv: Int
        get() = ((cellVoltageMax - cellVoltageMin) * 1000f).roundToInt().coerceAtLeast(0)

    val isEvMode: Boolean
        get() = iceRpm < 300

    /** Percentuale di batteria realmente utilizzabile in EV puro sopra la soglia HEV (15.5%) */
    val usableEvSocPct: Float
        get() = ((socBms - HEV_FLOOR_BMS_SOC).coerceAtLeast(0f) * 10f).roundToInt() / 10f

    /**
     * Stima autonomia EV residua basata sul buffer utile sopra il 15.5% BMS
     * (~80% di escursione utile BMS da 95.5% a 15.5% = ~62 km WLTP -> ~0.78 km per ogni 1% sopra il 15.5%).
     */
    val estimatedEvRangeKm: Int
        get() = (usableEvSocPct * 0.78f).roundToInt().coerceAtLeast(0)
}

/**
 * Singola risposta grezza catturata dallo Scanner/Sniffer UDS attivo per mappare i PID della Niro SG2.
 */
data class RawUdsDumpEntry(
    val timestampIso: String,
    val ecuHeaderTx: String,
    val ecuName: String,
    val pidCommand: String,
    val rawResponseHex: String,
    val payloadBytesHex: String,
    val byteCount: Int
)
