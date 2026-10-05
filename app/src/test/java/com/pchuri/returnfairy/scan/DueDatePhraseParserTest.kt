package com.pchuri.returnfairy.scan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DueDatePhraseParserTest {

    private val today = LocalDate.of(2026, 8, 2)

    @Test
    fun `parses Korean relative dates`() {
        assertResolved(LocalDate.of(2026, 8, 7), "이 책은 5일 후 반납이야")
        assertResolved(LocalDate.of(2026, 8, 3), "내일 반납")
        assertResolved(LocalDate.of(2026, 8, 4), "모레까지")
        assertResolved(LocalDate.of(2026, 8, 16), "2주 뒤")
    }

    @Test
    fun `parses English relative dates`() {
        assertResolved(LocalDate.of(2026, 8, 7), "due in 5 days")
        assertResolved(LocalDate.of(2026, 8, 16), "return in 2 weeks")
        assertResolved(LocalDate.of(2026, 8, 3), "tomorrow")
        assertResolved(LocalDate.of(2026, 8, 3), "notebook due tomorrow")
    }

    @Test
    fun `parses weekdays using calendar weeks`() {
        assertResolved(LocalDate.of(2026, 8, 7), "다음 주 금요일")
        assertResolved(LocalDate.of(2026, 8, 7), "다음주 금요일")
        assertResolved(LocalDate.of(2026, 8, 14), "다다음 금요일")
        assertResolved(LocalDate.of(2026, 8, 14), "다다음주 금요일")
        assertRejected("이번주 금요일")
        assertResolved(LocalDate.of(2026, 8, 7), "next Friday")
    }

    @Test
    fun `parses spaceless next week from a weekday`() {
        assertEquals(
            DueDateParseResult.Resolved(LocalDate.of(2026, 8, 14)),
            DueDatePhraseParser.parse("다음주 금요일", LocalDate.of(2026, 8, 5)),
        )
    }

    @Test
    fun `parses current month boundaries without AI`() {
        assertResolved(LocalDate.of(2026, 8, 31), "이번 달 마지막 평일")
        assertResolved(LocalDate.of(2026, 8, 31), "이번달의 마지막 평일")
        assertResolved(LocalDate.of(2026, 8, 31), "the final day of this month")
        assertResolved(LocalDate.of(2026, 8, 31), "last weekday of this month")
    }

    @Test
    fun `moves a weekend month end to the previous Friday`() {
        assertEquals(
            DueDateParseResult.Resolved(LocalDate.of(2026, 1, 30)),
            DueDatePhraseParser.parse("이번 달 마지막 평일", LocalDate.of(2026, 1, 10)),
        )
    }

    @Test
    fun `rejects a past last weekday during a month ending weekend`() {
        assertEquals(
            DueDateParseResult.Rejected,
            DueDatePhraseParser.parse("이번 달 마지막 평일", LocalDate.of(2026, 1, 31)),
        )
        assertEquals(
            DueDateParseResult.Rejected,
            DueDatePhraseParser.parse("last weekday of this month", LocalDate.of(2026, 5, 31)),
        )
    }

    @Test
    fun `parses explicit dates`() {
        assertResolved(LocalDate.of(2026, 9, 4), "2026-9-4 반납")
        assertResolved(LocalDate.of(2026, 9, 4), "2026.9.4")
        assertResolved(LocalDate.of(2026, 9, 4), "9월 4일")
        assertResolved(LocalDate.of(2027, 1, 3), "1월 3일")
        assertResolved(LocalDate.of(2028, 1, 3), "2028년 1월 3일")
        assertResolved(LocalDate.of(2026, 8, 3), "tomorrow or tomorrow")
    }

    @Test
    fun `distinguishes unsupported phrases from rejected phrases`() {
        assertEquals(DueDateParseResult.Unsupported, DueDatePhraseParser.parse("나중에 반납", today))
        assertRejected("5 days ago")
        assertRejected("not tomorrow")
        assertRejected("isn't due tomorrow")
        assertRejected("내일 아니야")
        assertRejected("내일은 안 돼")
        assertRejected("2026-02-30")
        assertRejected("2026-99-99")
        assertRejected("borrowed 2026-08-02, due 2026-08-07")
        assertRejected("2026-08-07 or tomorrow")
        assertRejected("in 5 days or in 7 days")
        assertRejected("5000일 후")
        assertRejected("999999999999999999999999 days later")
    }

    @Test
    fun `rejects dates outside the supported range`() {
        assertResolved(today.plusYears(3), "36 months later")
        assertRejected("1095 weeks later")
        assertRejected("1095 months later")
        assertRejected("2026-08-01")
        assertRejected("2030-08-02")
    }

    private fun assertResolved(expected: LocalDate, phrase: String) {
        assertEquals(DueDateParseResult.Resolved(expected), DueDatePhraseParser.parse(phrase, today))
    }

    private fun assertRejected(phrase: String) {
        assertEquals(DueDateParseResult.Rejected, DueDatePhraseParser.parse(phrase, today))
    }
}
