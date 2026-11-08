package com.casadei.nirowatchdog.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class EventLogFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun appendsTimestampedSingleLines() {
        val file = File(tmp.root, "events.log")
        val clock = { 1_793_519_340_000L } // 1 novembre 2026, 07:49 UTC
        val expectedTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ITALY)
            .apply { timeZone = TimeZone.getDefault() }
            .format(java.util.Date(clock()))

        val log = EventLogFile(file, maxBytes = 10_000L, clock = clock)
        log.append("Android Auto collegato")
        log.append("riga\nsu due righe")

        assertEquals(
            listOf("$expectedTime Android Auto collegato", "$expectedTime riga su due righe"),
            file.readLines()
        )
    }

    @Test
    fun rotatesWhenFull_keepingOnePreviousFile() {
        val file = File(tmp.root, "events.log")
        val previous = File(tmp.root, "events.log.1")
        val log = EventLogFile(file, maxBytes = 100L, clock = { 0L })

        repeat(4) { log.append("evento numero $it con un po' di testo") } // ~55 byte a riga
        assertTrue(previous.exists())
        assertTrue(file.length() <= 200L)

        val beforeSecondRotation = file.readText()
        repeat(2) { log.append("altro evento") }
        assertEquals(beforeSecondRotation, previous.readText())
        assertFalse(file.readText().contains("evento numero"))
    }
}
