package com.casadei.nirowatchdog.obd

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Parser ISO-TP multiframe e decodificatore UDS per Kia Niro SG2 PHEV (2024).
 */
object Sg2PidParser {

    /** Intervallo minimo di refresh visivo (ms) per il solo consumo termico L/100km (evita oscillazioni rapide) */
    private const val FUEL_DISPLAY_UPDATE_INTERVAL_MS = 2500L

    /**
     * Elenco completo delle query per lo "Scanner / Sniffer UDS Attivo" sulla Kia Niro SG2 PHEV.
     * Include BMS, Motore 1.6 GDi (consumo/MAF/velocità/marcia), Clima, Cluster e Telecamera ADAS 7C4.
     */
    val SG2_DISCOVERY_PROBES: List<Triple<String, String, String>> = listOf(
        Triple("7E4", "BMS (Batteria HV)", "220101"),
        Triple("7E4", "BMS (Celle 1-32)", "220102"),
        Triple("7E4", "BMS (Celle 33-64)", "220103"),
        Triple("7E4", "BMS (Celle 65-96)", "220104"),
        Triple("7E4", "BMS (SOH & Soglia HEV)", "220105"),
        Triple("7E4", "BMS (Blocco Esteso 06)", "220106"),
        Triple("7E0", "ECU Motore 1.6 GDi (Temp. Liquido)", "0105"),
        Triple("7E0", "ECU Motore 1.6 GDi (Giri RPM)", "010C"),
        Triple("7E0", "ECU Motore 1.6 GDi (Velocità km/h)", "010D"),
        Triple("7E0", "ECU Motore 1.6 GDi (Carico %)", "0104"),
        Triple("7E0", "ECU Motore 1.6 GDi (MAF Aria g/s)", "0110"),
        Triple("7B3", "DATC / HVAC (Clima)", "220100"),
        Triple("7C6", "Cluster (Odometro 10.25\")", "22B002"),
        Triple("7C4", "MFC / ADAS Camera (Blocco 0100)", "220100"),
        Triple("7C4", "MFC / ADAS Camera (Stato 0101)", "220101"),
        Triple("7C4", "MFC / ADAS Camera (Calibrazione 0102)", "220102"),
        Triple("7C4", "MFC / ADAS Camera (Diagnostica 0103)", "220103")
    )

    private val EMPTY = ByteArray(0)

    /**
     * Riassembla una risposta ISO-TP (single o multiframe) proveniente da ELM327 / OBDLink:
     * con header CAN (ATH1, es. "7EC103E620101...", "7EC21...", anche con spazi) oppure
     * senza header nel formato indicizzato (ATH0, es. "03E" + "0: 62 01 01 ...").
     *
     * - Accetta solo righe esadecimali pure: i messaggi dell'ELM ("NO DATA", "BUFFER FULL",
     *   "CAN ERROR", "UNABLE TO CONNECT", "?") non possono più iniettare byte spuri.
     * - Verifica la sequenza dei Consecutive Frame: se un frame va perso la risposta viene scartata
     *   (array vuoto) invece di restituire byte disallineati e quindi valori sbagliati.
     * - Tronca al numero di byte dichiarato nel First Frame, eliminando il padding finale (AA AA).
     * - Con [expectedRxHeader] (es. "7EC") ignora i frame provenienti da altre centraline.
     */
    fun extractIsoTpPayload(rawResponse: String, expectedRxHeader: String? = null): ByteArray {
        val data = ByteArrayOutputStream(64)
        var declaredLength = -1
        var inMultiFrame = false
        var nextSeq = 1

        for (rawLine in rawResponse.split('\r', '\n')) {
            val line = rawLine.trim().removePrefix(">").trim()
            if (line.isEmpty()) continue

            // Formato indicizzato senza header (ATH0): "0: 62 01 01 AA BB..."
            val colon = line.indexOf(':')
            if (colon in 1..2) {
                val compact = line.substring(colon + 1).filterNot { it == ' ' }
                if (compact.length % 2 != 0 || !compact.all(::isHexChar)) return EMPTY
                data.write(hexToBytes(compact, 0))
                continue
            }

            val compact = line.filterNot { it == ' ' }
            if (!compact.all(::isHexChar)) continue

            when {
                // Riga di lunghezza totale del formato ATH0 multiframe (es. "03E")
                compact.length == 3 -> declaredLength = compact.toInt(16)

                // Frame con header CAN 11-bit: 3 cifre di header + PCI + dati => lunghezza dispari
                compact.length % 2 == 1 && compact.length >= 5 -> {
                    if (expectedRxHeader != null && !compact.startsWith(expectedRxHeader, ignoreCase = true)) continue
                    val frame = hexToBytes(compact, 3)
                    val pci = frame[0].toInt() and 0xFF
                    when (pci ushr 4) {
                        0x0 -> { // Single Frame: la lunghezza è nel nibble basso del PCI
                            val len = pci and 0x0F
                            if (len == 0 || frame.size < 1 + len) return EMPTY
                            // NRC 0x78 "response pending": la centralina risponderà subito dopo, si prosegue
                            if (len == 3 && (frame[1].toInt() and 0xFF) == 0x7F && (frame[3].toInt() and 0xFF) == 0x78) continue
                            return frame.copyOfRange(1, 1 + len)
                        }
                        0x1 -> { // First Frame: lunghezza a 12 bit
                            if (frame.size < 2) return EMPTY
                            declaredLength = ((pci and 0x0F) shl 8) or (frame[1].toInt() and 0xFF)
                            data.reset()
                            data.write(frame, 2, frame.size - 2)
                            inMultiFrame = true
                            nextSeq = 1
                        }
                        0x2 -> { // Consecutive Frame: il numero di sequenza deve essere quello atteso
                            if (!inMultiFrame || (pci and 0x0F) != nextSeq) return EMPTY
                            data.write(frame, 1, frame.size - 1)
                            nextSeq = (nextSeq + 1) and 0x0F
                        }
                        // Flow Control o PCI sconosciuto: ignorato
                    }
                }

                // Byte puri senza header (ATH0 single frame, es. "410C0A1C")
                compact.length % 2 == 0 -> data.write(hexToBytes(compact, 0))
            }
        }

        val bytes = data.toByteArray()
        return when {
            declaredLength < 0 -> bytes
            bytes.size < declaredLength -> EMPTY // multiframe incompleto
            else -> bytes.copyOf(declaredLength)
        }
    }

    /**
     * Verifica che [payload] sia la risposta positiva alla richiesta [command] (es. "220101" -> 62 01 01,
     * "010C" -> 41 0C). Evita di attribuire a un PID la risposta tardiva di un comando precedente.
     */
    fun isPositiveResponseTo(command: String, payload: ByteArray): Boolean {
        if (command.length < 4 || command.length % 2 != 0 || !command.all(::isHexChar)) return false
        val request = hexToBytes(command, 0)
        if (payload.size < request.size) return false
        if ((payload[0].toInt() and 0xFF) != ((request[0].toInt() and 0xFF) + 0x40)) return false
        for (i in 1 until request.size) {
            if (payload[i] != request[i]) return false
        }
        return true
    }

    private fun isHexChar(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

    private fun hexToBytes(hex: String, start: Int): ByteArray {
        val out = ByteArray((hex.length - start) / 2)
        for (i in out.indices) {
            val p = start + i * 2
            out[i] = ((Character.digit(hex[p], 16) shl 4) or Character.digit(hex[p + 1], 16)).toByte()
        }
        return out
    }

    /**
     * Decodifica il blocco UDS 22 01 01 della centralina BMS (7E4 -> 7EC).
     * Calibrato su catture reali della Kia Niro SG2 PHEV 2024 (29.0% e 85.5% BMS):
     * d[0..3] = Bitmap UDS (EF F1 E7 EF)
     * d[4]    = SoC BMS * 2 (es. 0x3A = 58 -> 29.0%, 0xAB = 171 -> 85.5%)
     * d[10..11] = Corrente HV signed16 / 10 (es. 00 08 -> +0.8 A, 00 34 -> +5.2 A)
     * d[12..13] = Tensione HV unsigned16 / 10 (es. 0D 72 -> 344.2 V, 0F 23 -> 387.5 V)
     * d[14]   = Temp Max Batteria (es. 0x14 -> 20°C)
     * d[15]   = Temp Min Batteria (es. 0x13 -> 19°C)
     * d[16..19] = Temp Moduli 1..4 (es. 13 14 13 13 -> 19, 20, 19, 19°C)
     * d[23]   = Tensione Cella Max * 50 (es. 0xCA = 202 -> 4.04 V)
     * d[24]   = Indice Cella Max (es. 0x14 = 20)
     * d[25]   = Tensione Cella Min * 50 (es. 0xC9 = 201 -> 4.02 V)
     * d[26]   = Indice Cella Min (es. 0x60 = 96)
     * d[29]   = Tensione Aux 12V * 10 (es. 0x86 = 134 -> 13.4 V)
     * d[38..41] = Energia Cumulativa Caricata uint32 / 10 kWh
     * d[42..45] = Energia Cumulativa Scaricata uint32 / 10 kWh
     */
    fun applyBms220101(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0x01, 0x01)
        if (offset < 0 || payload.size < offset + 35) return current

        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.size < 30) return current

        val socBms = (d[4] / 2.0f).coerceIn(0f, 100f)
        val rawCurrent = signed16(d[10], d[11]) / 10.0f
        val hvVolt = unsigned16(d[12], d[13]) / 10.0f
        val powerKw = ((hvVolt * rawCurrent) / 1000.0f * 10f).roundToInt() / 10f

        val tempMax = signed8(d[14])
        val tempMin = signed8(d[15])
        val m1 = signed8(d[16])
        val m2 = signed8(d[17])
        val m3 = signed8(d[18])
        val m4 = signed8(d[19])
        val avgTemp = ((m1 + m2 + m3 + m4) / 4.0f * 10f).roundToInt() / 10f

        val cellMaxV = d.getOrElse(23) { 202 } / 50.0f
        val cellMaxIdx = d.getOrElse(24) { 20 }.coerceIn(1, 96)
        val cellMinV = d.getOrElse(25) { 201 } / 50.0f
        val cellMinIdx = d.getOrElse(26) { 96 }.coerceIn(1, 96)
        val aux12v = (d.getOrElse(29) { 134 } / 10.0f).coerceIn(9.0f, 16.0f)

        val cumCharged = if (d.size >= 42) {
            (((d[38].toLong() shl 24) or (d[39].toLong() shl 16) or (d[40].toLong() shl 8) or d[41].toLong()) / 10.0f)
        } else current.cumulativeChargedKwh

        val cumDischarged = if (d.size >= 46) {
            (((d[42].toLong() shl 24) or (d[43].toLong() shl 16) or (d[44].toLong() shl 8) or d[45].toLong()) / 10.0f)
        } else current.cumulativeDischargedKwh

        val updatedHistory = (current.powerHistoryKw + powerKw).takeLast(NiroTelemetryState.POWER_HISTORY_SIZE)

        val updated = current.copy(
            socBms = socBms,
            hvVoltage = hvVolt,
            hvCurrent = rawCurrent,
            hvPowerKw = powerKw,
            batteryTempAvg = avgTemp,
            batteryTempMin = tempMin,
            batteryTempMax = tempMax,
            moduleTemps = listOf(m1, m2, m3, m4),
            cellVoltageMax = cellMaxV,
            cellVoltageMaxIndex = cellMaxIdx,
            cellVoltageMin = cellMinV,
            cellVoltageMinIndex = cellMinIdx,
            aux12vVoltage = aux12v,
            ldcActive = aux12v >= 13.2f,
            cumulativeChargedKwh = cumCharged,
            cumulativeDischargedKwh = cumDischarged,
            powerHistoryKw = updatedHistory,
            lastLiveDataMs = System.currentTimeMillis()
        )
        return recomputeFuelAndGear(updated)
    }

    /**
     * Decodifica il blocco UDS 22 01 05 (SOH, Temp. Circuito e SoC Display in d[31]).
     * Nota dalle catture reali (29.0% vs 85.5% BMS):
     * - d[27] è fisso a 0x25 (37 -> 18.5%), che rappresenta la soglia fissa di riserva HEV!
     * - d[31] varia da 0x43 (33.5%) a 0xB5 (90.5%) seguendo il SoC reale con l'offset del display.
     * - d[25..26] = SOH u16 / 10 (es. 03 7A -> 89.0%), costante tra le catture. d[17] NON è il SOH
     *   (varia tra C8 e F1): la vecchia lettura d[17]/2 mostrava sempre 100%.
     */
    fun applyBms220105(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0x01, 0x05)
        if (offset < 0 || payload.size < offset + 35) return current

        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.size < 32) return current

        val liquidTemp = signed8(d.getOrElse(12) { 12 }).toFloat()
        val rawSoh = unsigned16(d[25], d[26]) / 10.0f
        val soh = if (rawSoh in 50.0f..110.0f) rawSoh else current.soh
        val socDisp = (d.getOrElse(31) { (current.socBms * 2).toInt() } / 2.0f).coerceIn(0f, 100f)

        return current.copy(
            soh = soh,
            socDisplay = socDisp,
            coolantInletTemp = liquidTemp
        )
    }

    /**
     * Decodifica il blocco UDS 22 01 06 (Temperatura Circuito Raffreddamento Batteria SG2).
     * d[0..3] = Bitmap UDS (10 F0 1F 00)
     * d[4]    = Temperatura Circuito Raffreddamento Batteria HV (es. 0x15 = 21°C, 0x0C = 12°C)
     */
    fun applyBms220106(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0x01, 0x06)
        if (offset < 0 || payload.size < offset + 8) return current
        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.size < 5) return current
        val coolantTemp = signed8(d[4]).toFloat()
        return current.copy(coolantInletTemp = coolantTemp)
    }

    /**
     * Decodifica un blocco di 32 celle (220102 = celle 1..32, 220103 = 33..64, 220104 = 65..96).
     * I primi 4 byte d[0..3] sono la maschera UDS (FF FF FF FF), seguiti dai 32 byte delle celle d[4..35].
     */
    fun applyBmsCellBlock(
        current: NiroTelemetryState,
        subPid: Int,
        payload: ByteArray
    ): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0x01, subPid)
        if (offset < 0) return current
        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.size < 36) return current

        val startIndex = when (subPid) {
            0x02 -> 0
            0x03 -> 32
            0x04 -> 64
            else -> return current
        }

        val cells = current.cellVoltages.toMutableList()
        for (i in 0 until 32) {
            val targetIdx = startIndex + i
            val rawByte = d[4 + i]
            if (targetIdx < cells.size && rawByte in 100..230) {
                cells[targetIdx] = rawByte / 50.0f
            }
        }
        return current.copy(cellVoltages = cells)
    }

    /**
     * Decodifica i PID standard OBD-II Mode 01 del motore termico 1.6 GDi Smartstream (7E0 -> 7E8):
     * - 01 04: Carico motore calcolato (%) -> A * 100 / 255
     * - 01 05: Temperatura liquido raffreddamento (ECT) -> A - 40 °C
     * - 01 0C: Giri motore (RPM) -> ((A * 256) + B) / 4
     * - 01 0D: Velocità veicolo (km/h) -> A
     * - 01 10: MAF Flusso aria aspirata (g/s) -> ((A * 256) + B) / 100.0
     * - 01 5E: Consumo carburante diretto (L/h) -> ((A * 256) + B) / 20.0
     * - 01 A4: Marcia attuale cambio (SAE J1979) -> B >> 4
     */
    fun applyEnginePid(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val bytes = payload.map { it.toInt() and 0xFF }
        // La risposta deve iniziare con 0x41: prima si cercava 0x41 in qualunque posizione,
        // e un byte dati qualsiasi (es. di una risposta UDS fuori sequenza) poteva diventare RPM o velocità.
        if (bytes.size < 3 || bytes[0] != 0x41) return current

        var updated = current
        var directFuelLh: Float? = null
        var directGear: String? = null

        // Formato Mode 01 (anche multi-PID): 41 PID A [B..] PID A [B..] ...
        var i = 1
        while (i < bytes.size) {
            val pid = bytes[i]
            val dataLen = MODE01_DATA_LENGTH[pid] ?: break
            if (i + dataLen >= bytes.size) break
            val a = bytes[i + 1]
            val b = if (dataLen > 1) bytes[i + 2] else 0
            when (pid) {
                0x04 -> {
                    val loadPct = (a * 100) / 255
                    updated = updated.copy(engineLoadPct = loadPct.coerceIn(0, 100))
                }
                0x05 -> {
                    val tempC = a - 40
                    updated = updated.copy(iceCoolantTemp = tempC)
                }
                0x0C -> {
                    val rpm = ((a * 256) + b) / 4
                    updated = updated.copy(iceRpm = rpm)
                }
                0x0D -> {
                    val speedKmh = a.coerceIn(0, 250)
                    updated = updated.copy(vehicleSpeedKmh = speedKmh)
                }
                0x10 -> {
                    val mafGramsSec = ((a * 256) + b) / 100.0f
                    // Rapporto stechiometrico benzina 14.7:1, densità 737 g/L -> L/h = MAF * 0.3322
                    directFuelLh = (mafGramsSec * 0.3322f).coerceIn(0f, 35f)
                }
                0x5E -> {
                    val rawRate = ((a * 256) + b) / 20.0f
                    if (rawRate in 0.1f..40.0f) {
                        directFuelLh = rawRate
                    }
                }
                0xA4 -> {
                    val gearNum = (b ushr 4) and 0x0F
                    if (gearNum in 1..6) {
                        directGear = "${gearNum}ª"
                    }
                }
            }
            i += 1 + dataLen
        }

        return recomputeFuelAndGear(updated, directFuelLh, directGear)
    }

    /** Byte di dati restituiti da ciascun PID Mode 01 gestito (SAE J1979). */
    private val MODE01_DATA_LENGTH = mapOf(
        0x04 to 1,
        0x05 to 1,
        0x0C to 2,
        0x0D to 1,
        0x10 to 2,
        0x5E to 2,
        0xA4 to 4
    )

    /**
     * Decodifica l'odometro reale dal Cluster 10.25" (7C6 -> 22 B0 02).
     * L'odometro è in d[6..8], intero a 24 bit (verificato su catture reali), es.:
     * 62 B0 02 E0 00 00 00 00 00 01 86 A0 -> d[6..8] = 01 86 A0 = 100.000 km
     */
    fun applyCluster22B002(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0xB0, 0x02)
        if (offset < 0 || payload.size < offset + 12) return current
        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.size < 9) return current

        val odoKm = (d[6] shl 16) or (d[7] shl 8) or d[8]
        return if (odoKm in 1..999999) {
            current.copy(odometerKm = odoKm)
        } else {
            current
        }
    }

    /**
     * Decodifica lo stato della Telecamera Frontale ADAS MFC (7C4 -> 7CC).
     */
    fun applyAdasCamera7C4(current: NiroTelemetryState, payload: ByteArray): NiroTelemetryState {
        val offset = findServiceHeaderOffset(payload, 0x62, 0x01, 0x01)
        if (offset < 0 || payload.size < offset + 4) return current
        val d = payload.drop(offset + 3).map { it.toInt() and 0xFF }
        if (d.isEmpty()) return current
        val statusHex = d.take(6).joinToString(" ") { "%02X".format(it) }
        return current.copy(adasCameraStatusHex = statusHex)
    }

    /**
     * Calcola il consumo istantaneo del termico (L/h e L/100km), il consumo elettrico istantaneo
     * (kWh/100km) e la marcia innestata (P/D1..D6) combinando i PID diretti con la cinematica 6-DCT.
     */
    fun recomputeFuelAndGear(
        state: NiroTelemetryState,
        measuredFuelLh: Float? = null,
        measuredGear: String? = null
    ): NiroTelemetryState {
        val rpm = state.iceRpm
        val speed = state.vehicleSpeedKmh

        // 1. Calcolo Consumo Istantaneo Motore Termico 1.6 GDi (L/h e L/100km)
        //    Persiste l'ultima lettura MAF reale (0110) per evitare salti tra cicli PID diversi,
        //    applica una media mobile esponenziale (EMA) e aggiorna il valore visivo ogni 2.5 secondi.
        val displayedFuelLh: Float
        val displayedFuelL100km: Float
        val nextMeasuredLh: Float?
        val nextSmoothedLh: Float
        val nextSmoothedL100km: Float
        val nextFuelUpdateMs: Long

        if (rpm < 300) {
            // Motore termico spento (EV Puro) -> azzera immediatamente senza ritardo
            displayedFuelLh = 0.0f
            displayedFuelL100km = 0.0f
            nextMeasuredLh = null
            nextSmoothedLh = 0.0f
            nextSmoothedL100km = 0.0f
            nextFuelUpdateMs = 0L
        } else {
            // Motore acceso: mantiene l'ultima lettura MAF valida (0110) anche quando arrivano altri PID (010C/010D/220101)
            nextMeasuredLh = measuredFuelLh ?: state.lastMeasuredFuelLh
            val loadFactor = if (state.engineLoadPct > 0) (state.engineLoadPct / 100f) else 0.48f
            val fallbackLh = ((rpm / 120.0f) * 1.58f * 1.184f * loadFactor * 0.80f * 0.3322f).coerceIn(0.8f, 22.0f)
            val rawLh = nextMeasuredLh ?: fallbackLh

            nextSmoothedLh = if (state.smoothedFuelLh <= 0.1f) {
                rawLh
            } else {
                (state.smoothedFuelLh * 0.68f) + (rawLh * 0.32f)
            }

            val rawL100km = if (speed >= 5) {
                ((nextSmoothedLh * 100.0f) / speed).coerceIn(0.5f, 29.9f)
            } else {
                0.0f
            }

            nextSmoothedL100km = if (speed < 5) {
                0.0f
            } else if (state.smoothedFuelL100km <= 0.1f) {
                rawL100km
            } else {
                (state.smoothedFuelL100km * 0.68f) + (rawL100km * 0.32f)
            }

            val now = System.currentTimeMillis()
            val shouldRefreshDisplay = state.lastFuelDisplayUpdateMs == 0L ||
                (now - state.lastFuelDisplayUpdateMs) >= FUEL_DISPLAY_UPDATE_INTERVAL_MS ||
                (speed < 5 && state.instantFuelL100km > 0f) ||
                (speed >= 5 && state.instantFuelL100km == 0f)

            if (shouldRefreshDisplay) {
                displayedFuelLh = (nextSmoothedLh * 10f).roundToInt() / 10f
                displayedFuelL100km = (nextSmoothedL100km * 10f).roundToInt() / 10f
                nextFuelUpdateMs = now
            } else {
                displayedFuelLh = state.instantFuelLh
                displayedFuelL100km = state.instantFuelL100km
                nextFuelUpdateMs = state.lastFuelDisplayUpdateMs
            }
        }

        // 2. Calcolo Consumo Elettrico Istantaneo Batteria HV (kWh/100km)
        val evKwh100km: Float = if (speed >= 5) {
            (((state.hvPowerKw * 100.0f) / speed).coerceIn(-50.0f, 60.0f) * 10f).roundToInt() / 10f
        } else {
            0.0f
        }

        // 3. Determinazione Marcia Innestata (Cambio 6-DCT Kia Niro SG2)
        val gear: String = when {
            measuredGear != null -> measuredGear
            speed == 0 && rpm < 300 && kotlin.math.abs(state.hvPowerKw) < 3.0f -> "P / N"
            rpm >= 600 && speed >= 5 -> {
                // Con motore termico innestato sul 6-DCT, il rapporto RPM / km/h identifica esattamente la marcia
                val ratio = rpm.toFloat() / speed.toFloat()
                when {
                    ratio > 95f -> "1ª"
                    ratio > 62f -> "2ª"
                    ratio > 43f -> "3ª"
                    ratio > 31f -> "4ª"
                    ratio > 23.5f -> "5ª"
                    else -> "6ª"
                }
            }
            else -> {
                // In modalità EV puro sulla Niro SG2 anche il motore elettrico passa attraverso il 6-DCT
                when {
                    speed == 0 -> "P / D"
                    speed < 20 -> "1ª"
                    speed < 38 -> "2ª"
                    speed < 56 -> "3ª"
                    speed < 76 -> "4ª"
                    speed < 98 -> "5ª"
                    else -> "6ª"
                }
            }
        }

        return state.copy(
            instantFuelLh = displayedFuelLh,
            instantFuelL100km = displayedFuelL100km,
            lastMeasuredFuelLh = nextMeasuredLh,
            smoothedFuelLh = nextSmoothedLh,
            smoothedFuelL100km = nextSmoothedL100km,
            lastFuelDisplayUpdateMs = nextFuelUpdateMs,
            instantEvKwh100km = evKwh100km,
            currentGear = gear
        )
    }

    private fun findServiceHeaderOffset(payload: ByteArray, b0: Int, b1: Int, b2: Int): Int {
        for (i in 0..payload.size - 3) {
            if ((payload[i].toInt() and 0xFF) == b0 &&
                (payload[i + 1].toInt() and 0xFF) == b1 &&
                (payload[i + 2].toInt() and 0xFF) == b2
            ) {
                return i
            }
        }
        return -1
    }

    private fun unsigned16(hi: Int, lo: Int): Int = ((hi and 0xFF) shl 8) or (lo and 0xFF)

    private fun signed16(hi: Int, lo: Int): Int {
        val u = unsigned16(hi, lo)
        return if (u >= 0x8000) u - 0x10000 else u
    }

    private fun signed8(b: Int): Int {
        val u = b and 0xFF
        return if (u >= 0x80) u - 0x100 else u
    }
}
