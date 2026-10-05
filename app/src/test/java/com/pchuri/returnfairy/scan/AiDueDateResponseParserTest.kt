package com.pchuri.returnfairy.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AiDueDateResponseParserTest {

    private val today = LocalDate.of(2026, 8, 2)

    @Test
    fun `accepts one valid future ISO date`() {
        assertEquals(
            LocalDate.of(2026, 8, 7),
            AiDueDateResponseParser.parse("  2026-08-07\n", today),
        )
    }

    @Test
    fun `rejects prose and echoed reference dates`() {
        assertNull(AiDueDateResponseParser.parse("Today is 2026-08-02. Answer: 2026-08-07", today))
        assertNull(AiDueDateResponseParser.parse("UNKNOWN", today))
    }

    @Test
    fun `rejects an answer equal to the reference date`() {
        assertNull(AiDueDateResponseParser.parse("2026-08-02", today))
    }

    @Test
    fun `still accepts tomorrow as the earliest valid answer`() {
        assertEquals(
            LocalDate.of(2026, 8, 3),
            AiDueDateResponseParser.parse("2026-08-03", today),
        )
    }

    @Test
    fun `rejects invalid past and implausibly distant dates`() {
        assertNull(AiDueDateResponseParser.parse("2026-02-30", today))
        assertNull(AiDueDateResponseParser.parse("2026-08-01", today))
        assertNull(AiDueDateResponseParser.parse("2030-08-02", today))
    }
}
