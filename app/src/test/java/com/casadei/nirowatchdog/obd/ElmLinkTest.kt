package com.casadei.nirowatchdog.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ElmLinkTest {

    /** ELM327 simulato: a ogni comando terminato da '\r' accoda la risposta di [responder] (null = silenzio). */
    private class FakeElm(private val responder: (String) -> String?) {
        val sent = mutableListOf<String>()
        private val pending = ArrayDeque<Byte>()

        fun inject(text: String) {
            text.toByteArray(Charsets.US_ASCII).forEach { pending.addLast(it) }
        }

        val input = object : InputStream() {
            override fun available(): Int = pending.size
            override fun read(): Int = pending.removeFirstOrNull()?.toInt()?.and(0xFF) ?: -1
        }

        val output = object : OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                val c = b.toChar()
                if (c == '\r') {
                    val cmd = line.toString()
                    line.clear()
                    sent += cmd
                    responder(cmd)?.let { inject(it) }
                } else {
                    line.append(c)
                }
            }
        }

        fun link(timeoutMs: Long = 200L, maxSilent: Int = 3) = ElmLink(input, output, timeoutMs, maxSilent)
    }

    private val bms220101 = Sg2PidParserTest.BMS_220101

    private fun standardResponder(cmd: String): String? = when {
        cmd.startsWith("AT") -> "OK\r\r>"
        cmd == "220101" -> bms220101
        cmd == "010C1" || cmd == "010C" -> "7E804410C0A1C\r\r>"
        cmd == "010D1" || cmd == "010D" -> "7E803410D32\r\r>"
        else -> "NO DATA\r\r>"
    }

    @Test
    fun atshIsSentOnlyWhenHeaderChanges() = runBlocking {
        val elm = FakeElm(::standardResponder)
        val link = elm.link()

        assertEquals(62, link.query("7E4", "220101").size)
        assertEquals(62, link.query("7E4", "220101").size)
        assertEquals(4, link.query("7E0", "010C", singleResponse = true).size)

        assertEquals(listOf("ATSH7E4", "220101", "220101", "ATSH7E0", "010C1"), elm.sent)
    }

    @Test
    fun responseCountSuffix_isDisabledWhenAdapterRejectsIt() = runBlocking {
        val elm = FakeElm { cmd ->
            when {
                cmd.startsWith("AT") -> "OK\r\r>"
                cmd.length == 5 -> "?\r\r>" // ELM327 < v1.3: comando sconosciuto
                else -> standardResponder(cmd)
            }
        }
        val link = elm.link()

        assertEquals(4, link.query("7E0", "010C", singleResponse = true).size)
        assertFalse(link.responseCountSuffix)
        assertEquals(3, link.query("7E0", "010D", singleResponse = true).size)

        assertEquals(listOf("ATSH7E0", "010C1", "010C", "010D"), elm.sent)
    }

    @Test
    fun confirmedSuffix_isNotRetriedWhenEcuIsSilent() = runBlocking {
        val elm = FakeElm { cmd ->
            when {
                cmd.startsWith("AT") -> "OK\r\r>"
                cmd == "010C1" -> "7E804410C0A1C\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val link = elm.link()
        assertEquals(4, link.query("7E0", "010C", singleResponse = true).size)
        assertEquals(0, link.query("7E0", "010D", singleResponse = true).size)

        // Suffisso già confermato: nessun secondo round-trip "010D" senza suffisso
        assertEquals(listOf("ATSH7E0", "010C1", "010D1"), elm.sent)
        assertTrue(link.responseCountSuffix)
    }

    @Test
    fun cloneAnsweringNoDataToSuffix_fallsBackAndDisablesIt() = runBlocking {
        val elm = FakeElm { cmd ->
            when {
                cmd.startsWith("AT") -> "OK\r\r>"
                cmd.length == 5 -> "NO DATA\r\r>"
                else -> standardResponder(cmd)
            }
        }
        val link = elm.link()
        assertEquals(4, link.query("7E0", "010C", singleResponse = true).size)
        assertFalse(link.responseCountSuffix)
    }

    @Test
    fun silentEcu_limitsFallbacksWhileSuffixIsUnconfirmed() = runBlocking {
        val elm = FakeElm { cmd -> if (cmd.startsWith("AT")) "OK\r\r>" else "NO DATA\r\r>" }
        val link = elm.link()
        repeat(6) { link.query("7E0", "010C", singleResponse = true) }

        assertEquals(6, elm.sent.count { it == "010C1" })
        assertEquals(3, elm.sent.count { it == "010C" })
    }

    @Test
    fun spontaneousAdapterReset_throwsToForceReinit() {
        val elm = FakeElm { cmd -> if (cmd.startsWith("AT")) "OK\r\r>" else "\r\rELM327 v1.5\r\r>" }
        val link = elm.link()
        assertThrows(IOException::class.java) {
            runBlocking { link.query("7E4", "220101") }
        }
    }

    @Test
    fun lateResponseFromPreviousCommand_isDrained() = runBlocking {
        val elm = FakeElm(::standardResponder)
        val link = elm.link()
        link.query("7E0", "010D", singleResponse = true)

        // Risposta tardiva (es. arrivata dopo un timeout) ancora nel buffer quando parte il comando successivo
        elm.inject("7E803410D32\r\r>")
        val rpm = link.query("7E0", "010C", singleResponse = true)

        assertEquals(0x0C, rpm[1].toInt())
        assertTrue(link.responseCountSuffix)
    }

    @Test
    fun responseToAnotherPid_isRejected() = runBlocking {
        val elm = FakeElm { cmd -> if (cmd.startsWith("AT")) "OK\r\r>" else "7E803410D32\r\r>" }
        assertEquals(0, elm.link().query("7E0", "010C", singleResponse = true).size)
    }

    @Test
    fun responseFromWrongEcu_isRejected() = runBlocking {
        val elm = FakeElm { cmd -> if (cmd.startsWith("AT")) "OK\r\r>" else bms220101.replace("7EC", "7E8") }
        assertEquals(0, elm.link().query("7E4", "220101").size)
    }

    @Test
    fun silentAdapter_throwsAfterConsecutiveTimeouts() {
        val elm = FakeElm { null }
        val link = elm.link(timeoutMs = 20L, maxSilent = 3)
        runBlocking {
            assertEquals("", link.send("ATI"))
            assertEquals("", link.send("ATI"))
        }
        assertThrows(IOException::class.java) {
            runBlocking { link.send("ATI") }
        }
    }

    @Test
    fun responseHeader_isRequestPlus8() {
        assertEquals("7EC", ElmLink.responseHeaderFor("7E4"))
        assertEquals("7E8", ElmLink.responseHeaderFor("7E0"))
        assertEquals("7CE", ElmLink.responseHeaderFor("7C6"))
        assertEquals("7BB", ElmLink.responseHeaderFor("7B3"))
    }
}
