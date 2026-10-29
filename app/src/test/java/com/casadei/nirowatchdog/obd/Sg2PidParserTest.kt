package com.casadei.nirowatchdog.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il parser su risposte ELM327 (ATH1, ATS0) ricavate da catture reali della Kia Niro SG2 PHEV.
 * Contatori cumulativi e odometro sono sostituiti da valori sintetici.
 */
class Sg2PidParserTest {

    companion object {
        /** BMS 7E4 -> 22 01 01 all'85.5%: 62 byte in 9 frame ISO-TP */
        const val BMS_220101 = "7EC103E620101EFF1E7\r7EC21EFAB0000000000\r7EC2200340F23141313\r" +
            "7EC23141313000034CA\r7EC2414C96000008600\r7EC250186A0000186A0\r7EC2600002710000023\r" +
            "7EC2728001000000001\r7EC2881000000000BB8\r\r>"

        /** BMS 7E4 -> 22 01 02 (celle 1..32): First Frame da 0x027 = 39 byte, poi padding AA */
        const val BMS_220102 = "7EC1027620102FFFFFF\r7EC21FFC9C9C9C9CACA\r7EC22C9C9C9CACACAC9\r" +
            "7EC23C9C9CACAC9C9CA\r7EC24C9CACAC9C9C9C9\r7EC25CAC9CACAC9AAAA\r\r>"

        /** BMS 7E4 -> 22 01 05 con d[17] = F1 e con d[17] = C8: in entrambe il SOH è d[25..26] = 03 7A */
        const val BMS_220105_F1 = "7EC102E620105E03A04\r7EC2100006400006400\r7EC2200000C34870017\r" +
            "7EC23F1206C00005000\r7EC2400037A250D5D00\r7EC25B5000000000000\r7EC260082010000AAAA\r\r>"
        const val BMS_220105_C8 = "7EC102E620105E03A04\r7EC2100007201006300\r7EC2200000F388F0019\r" +
            "7EC23C8206C00005000\r7EC2400037A250A2000\r7EC2590000000000000\r7EC260072010000AAAA\r\r>"

        /** Cluster 7C6 -> 22 B0 02 con odometro sintetico 01 86 A0 = 100.000 km */
        const val CLUSTER_22B002 = "7CE100F62B002E00000\r7CE210000000186A000\r7CE220000AAAAAAAAAA\r\r>"

        /** Clima 7B3 -> 22 01 00 catturata con il frame 24 mancante (21, 22, 23, 25) */
        const val HVAC_220100_MISSING_FRAME = "7BB10276201007FD027\r7BB21E8FF71675E00E3\r" +
            "7BB221900E319FF1BFF\r7BB23FFFFFFFFFF1CFF\r7BB25FF00FFFFFF0000\r\r>"
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun bms220101_decodesRealFrameAt85pct() {
        val payload = Sg2PidParser.extractIsoTpPayload(BMS_220101, "7EC")
        assertEquals(62, payload.size)

        val s = Sg2PidParser.applyBms220101(NiroTelemetryState(), payload)
        assertEquals(85.5f, s.socBms, 0.001f)
        assertEquals(387.5f, s.hvVoltage, 0.001f)
        assertEquals(5.2f, s.hvCurrent, 0.001f)
        assertEquals(20, s.batteryTempMax)
        assertEquals(19, s.batteryTempMin)
        assertEquals(4.04f, s.cellVoltageMax, 0.001f)
        assertEquals(20, s.cellVoltageMaxIndex)
        assertEquals(4.02f, s.cellVoltageMin, 0.001f)
        assertEquals(96, s.cellVoltageMinIndex)
        assertEquals(13.4f, s.aux12vVoltage, 0.001f)
        assertEquals(1000.0f, s.cumulativeChargedKwh, 0.01f)
        assertEquals(900.0f, s.cumulativeDischargedKwh, 0.01f)
        assertTrue(s.lastLiveDataMs > 0L)
    }

    @Test
    fun cellBlock_dropsIsoTpPadding() {
        val payload = Sg2PidParser.extractIsoTpPayload(BMS_220102, "7EC")
        // First Frame dichiara 0x027 = 39 byte: i due AA di padding finali non fanno parte della risposta
        assertEquals(39, payload.size)

        val s = Sg2PidParser.applyBmsCellBlock(NiroTelemetryState(), 0x02, payload)
        assertEquals(4.02f, s.cellVoltages[0], 0.001f)
        assertEquals(4.04f, s.cellVoltages[4], 0.001f)
        assertEquals(4.02f, s.cellVoltages[31], 0.001f)
        assertEquals(3.86f, s.cellVoltages[32], 0.001f) // blocco 220103 non ancora letto
    }

    @Test
    fun soh_isReadFromBytes25and26() {
        for (raw in listOf(BMS_220105_F1, BMS_220105_C8)) {
            val payload = Sg2PidParser.extractIsoTpPayload(raw, "7EC")
            val s = Sg2PidParser.applyBms220105(NiroTelemetryState(soh = 0f), payload)
            assertEquals(89.0f, s.soh, 0.001f)
        }
    }

    @Test
    fun odometer_fromCluster() {
        val payload = Sg2PidParser.extractIsoTpPayload(CLUSTER_22B002, "7CE")
        assertEquals(15, payload.size)
        assertEquals(100000, Sg2PidParser.applyCluster22B002(NiroTelemetryState(), payload).odometerKm)
    }

    @Test
    fun missingConsecutiveFrame_inRealCapture_isRejected() {
        val payload = Sg2PidParser.extractIsoTpPayload(HVAC_220100_MISSING_FRAME, "7BB")
        assertEquals(0, payload.size)
    }

    @Test
    fun droppedBmsFrame_isRejectedInsteadOfShiftingBytes() {
        val withoutFrame4 = BMS_220101.split('\r').filterNot { it.startsWith("7EC24") }.joinToString("\r")
        assertEquals(0, Sg2PidParser.extractIsoTpPayload(withoutFrame4, "7EC").size)

        // Con la risposta scartata lo stato resta quello precedente (prima: 12V e celle letti da byte sbagliati)
        val before = NiroTelemetryState()
        val after = Sg2PidParser.applyBms220101(before, Sg2PidParser.extractIsoTpPayload(withoutFrame4, "7EC"))
        assertTrue(after === before)
    }

    @Test
    fun duplicatedFrame_withCorrectTotalLength_isRejected() {
        // Il frame 23 ripetuto al posto del 24: la lunghezza torna, ma i byte successivi sarebbero sfalsati
        val lines = BMS_220101.split('\r')
        val frame23 = lines.first { it.startsWith("7EC23") }
        val corrupted = lines.map { if (it.startsWith("7EC24")) frame23 else it }.joinToString("\r")
        assertEquals(0, Sg2PidParser.extractIsoTpPayload(corrupted, "7EC").size)
    }

    @Test
    fun responsePendingNrc_isSkipped() {
        val raw = "7EC037F2278\r$BMS_220101"
        assertEquals(62, Sg2PidParser.extractIsoTpPayload(raw, "7EC").size)
    }

    @Test
    fun elmTextMessages_neverProduceBytes() {
        val messages = listOf(
            "UNABLE TO CONNECT\r\r>",
            "BUFFER FULL\r\r>",
            "CAN ERROR\r\r>",
            "NO DATA\r\r>",
            "STOPPED\r\r>",
            "SEARCHING...\r\r>",
            "?\r\r>"
        )
        for (msg in messages) {
            assertEquals("Byte spuri da '$msg'", 0, Sg2PidParser.extractIsoTpPayload(msg).size)
        }
    }

    @Test
    fun expectedHeader_filtersOtherEcus() {
        val raw = "7E803410539\r\r>"
        assertEquals(0, Sg2PidParser.extractIsoTpPayload(raw, "7EC").size)
        assertArrayEquals(bytes(0x41, 0x05, 0x39), Sg2PidParser.extractIsoTpPayload(raw, "7E8"))
    }

    @Test
    fun indexedFormatWithoutHeaders_isTruncatedToDeclaredLength() {
        val raw = "008\r0: 62 01 01 01 02 03\r1: 04 05 AA AA AA AA AA\r\r>"
        assertArrayEquals(
            bytes(0x62, 0x01, 0x01, 0x01, 0x02, 0x03, 0x04, 0x05),
            Sg2PidParser.extractIsoTpPayload(raw)
        )
    }

    @Test
    fun applyEnginePid_requiresMode01ResponseAtStart() {
        // Un 0x41 in mezzo a un'altra risposta non deve diventare velocità o giri
        val stray = Sg2PidParser.applyEnginePid(NiroTelemetryState(), bytes(0x62, 0x01, 0x01, 0x41, 0x0D, 0x50))
        assertEquals(0, stray.vehicleSpeedKmh)

        val rpm = Sg2PidParser.applyEnginePid(NiroTelemetryState(), bytes(0x41, 0x0C, 0x0A, 0x1C))
        assertEquals(647, rpm.iceRpm)

        val multi = Sg2PidParser.applyEnginePid(NiroTelemetryState(), bytes(0x41, 0x0C, 0x0A, 0x1C, 0x0D, 0x32))
        assertEquals(647, multi.iceRpm)
        assertEquals(50, multi.vehicleSpeedKmh)
    }

    @Test
    fun isPositiveResponseTo_matchesServiceAndPid() {
        assertTrue(Sg2PidParser.isPositiveResponseTo("220101", bytes(0x62, 0x01, 0x01, 0xEF)))
        assertFalse(Sg2PidParser.isPositiveResponseTo("220101", bytes(0x62, 0x01, 0x05, 0xEF)))
        assertTrue(Sg2PidParser.isPositiveResponseTo("010C", bytes(0x41, 0x0C, 0x00, 0x00)))
        assertFalse(Sg2PidParser.isPositiveResponseTo("010C", bytes(0x41, 0x0D, 0x00)))
        assertFalse(Sg2PidParser.isPositiveResponseTo("010C", bytes(0x7F, 0x01, 0x31)))
        assertFalse(Sg2PidParser.isPositiveResponseTo("010C", ByteArray(0)))
    }
}
