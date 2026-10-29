package com.casadei.nirowatchdog.obd

import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Canale di comunicazione con l'adattatore ELM327 (Android-Vlink / OBDLink) su Bluetooth SPP.
 *
 * - Ogni comando attende il prompt '>' entro un timeout; l'attesa usa delay() ed è quindi cancellabile.
 * - Prima di ogni comando scarta i byte residui di risposte arrivate in ritardo, che altrimenti
 *   verrebbero attribuiti al comando successivo.
 * - Ricorda l'header CAN corrente e invia ATSH solo quando cambia centralina.
 * - Se l'adattatore resta muto (nessun prompt) per [maxSilentCommands] comandi consecutivi lancia
 *   IOException, così il chiamante chiude il socket e riconnette.
 */
internal class ElmLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val maxSilentCommands: Int = DEFAULT_MAX_SILENT_COMMANDS
) {

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 1800L
        const val DEFAULT_MAX_SILENT_COMMANDS = 3
        private const val RESET_TIMEOUT_MS = 3000L
        private const val READ_POLL_INTERVAL_MS = 10L
        /** Ritentativi senza suffisso concessi finché il suffisso "1" non ha mai dato una risposta valida */
        private const val MAX_UNCONFIRMED_SUFFIX_FALLBACKS = 3

        private val INIT_COMMANDS = listOf(
            "ATE0",  // Echo Off
            "ATL0",  // Linefeeds Off
            "ATS0",  // Spaces Off
            "ATH1",  // Headers On (mostra 7EC, 7E8, 7E9)
            "ATSP6", // ISO 15765-4 CAN (11 bit ID, 500 kbaud)
            "ATST64" // Timeout ottimizzato per multiframe UDS
        )

        /** Header di risposta con indirizzamento fisico 11 bit Hyundai/Kia: richiesta + 8 (7E4 -> 7EC, 7C6 -> 7CE). */
        fun responseHeaderFor(requestHeader: String): String? =
            requestHeader.toIntOrNull(16)?.let { Integer.toHexString(it + 8).uppercase().padStart(3, '0') }
    }

    private val readBuffer = ByteArray(512)
    private var currentHeader: String? = null
    private var silentCommands = 0

    /**
     * Suffisso "1" sui PID Mode 01 (ELM327 v1.3+): l'adattatore restituisce la risposta appena arriva
     * invece di attendere il timeout per altre eventuali centraline. Si disattiva da solo se il dongle
     * non lo gestisce.
     */
    var responseCountSuffix = true
        private set
    private var suffixConfirmed = false
    private var unconfirmedSuffixFallbacks = 0

    suspend fun initialize() {
        send("ATZ", RESET_TIMEOUT_MS) // Reset
        for (cmd in INIT_COMMANDS) {
            send(cmd)
        }
        currentHeader = null
    }

    /** Invia [command] e restituisce la risposta grezza fino al prompt '>' (o quanto ricevuto entro il timeout). */
    suspend fun send(command: String, timeoutMs: Long = commandTimeoutMs): String {
        drainStaleInput()
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder(128)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (input.available() > 0) {
                val read = input.read(readBuffer)
                if (read > 0) {
                    sb.append(String(readBuffer, 0, read, Charsets.US_ASCII))
                    if (sb.indexOf(">") >= 0) {
                        silentCommands = 0
                        // Banner di avvio fuori da un ATZ: il dongle si è riavviato da solo (es. calo di tensione)
                        // e ha perso ATH1/ATSP6/ATSH, quindi va reinizializzato con una nuova connessione
                        if (!command.startsWith("ATZ") && !command.startsWith("ATI") && sb.indexOf("ELM327") >= 0) {
                            throw IOException("L'adattatore OBD si è riavviato")
                        }
                        return sb.toString()
                    }
                }
            } else {
                delay(READ_POLL_INTERVAL_MS)
            }
        }
        if (++silentCommands >= maxSilentCommands) {
            throw IOException("L'adattatore OBD non risponde")
        }
        return sb.toString()
    }

    /**
     * Interroga [command] sulla centralina [header] e restituisce il payload ISO-TP solo se è la risposta
     * positiva a quella richiesta (altrimenti array vuoto). [singleResponse] abilita il suffisso "1"
     * per i PID Mode 01 a frame singolo.
     */
    suspend fun query(header: String, command: String, singleResponse: Boolean = false): ByteArray {
        if (!selectHeader(header)) return ByteArray(0)
        val rxHeader = responseHeaderFor(header)
        if (singleResponse && responseCountSuffix) {
            val raw = send(command + "1")
            val fast = parseValidated(raw, rxHeader, command)
            if (fast.isNotEmpty()) {
                suffixConfirmed = true
                return fast
            }
            // "?" = comando sconosciuto (ELM327 < v1.3): suffisso non supportato
            if (raw.contains('?')) {
                responseCountSuffix = false
                return parseValidated(send(command), rxHeader, command)
            }
            // NO DATA col suffisso mai confermato: alcuni cloni lo gestiscono male, quindi si riprova senza
            // (poche volte, per non raddoppiare i round-trip a ogni ciclo se è la centralina a non rispondere)
            if (suffixConfirmed || unconfirmedSuffixFallbacks >= MAX_UNCONFIRMED_SUFFIX_FALLBACKS) return fast
            unconfirmedSuffixFallbacks++
            val plain = parseValidated(send(command), rxHeader, command)
            if (plain.isNotEmpty()) responseCountSuffix = false
            return plain
        }
        return parseValidated(send(command), rxHeader, command)
    }

    /** Come [query] ma senza validazione e con la risposta grezza (Scanner / Sniffer UDS). */
    suspend fun queryRaw(header: String, command: String): Pair<String, ByteArray> {
        selectHeader(header)
        val raw = send(command)
        return raw to Sg2PidParser.extractIsoTpPayload(raw, responseHeaderFor(header))
    }

    private suspend fun selectHeader(header: String): Boolean {
        if (header == currentHeader) return true
        val ok = send("ATSH$header").contains("OK")
        currentHeader = if (ok) header else null
        return ok
    }

    private fun parseValidated(raw: String, rxHeader: String?, command: String): ByteArray {
        val payload = Sg2PidParser.extractIsoTpPayload(raw, rxHeader)
        return if (Sg2PidParser.isPositiveResponseTo(command, payload)) payload else ByteArray(0)
    }

    private fun drainStaleInput() {
        while (input.available() > 0) {
            if (input.read(readBuffer) <= 0) break
        }
    }
}
