package com.pchuri.returnfairy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SplibParsersTest {

    private fun row(title: String, infoHtml: String, status: String) = """
        <div class="myArticle-list">
          <div class="infoBox"><div class="title">$title</div>$infoHtml</div>
          <div class="statusBox">$status</div>
        </div>"""

    private fun loanRow(title: String, library: String, due: String, status: String = "대출중") = row(
        title,
        """<div class="info"><span><strong>$library</strong></span></div>
           <div class="info"><span>대출일 : 2026.09.20</span><span>반납예정일 : $due</span></div>""",
        status,
    )

    private fun dooraeRow(title: String, receiving: String, providing: String, status: String) = row(
        title,
        """<div class="info"><span>수령도서관 : $receiving</span></div>
           <div class="info"><span>제공도서관 : $providing</span></div>""",
        status,
    )

    @Test
    fun indexGivesNameAndCounts() {
        val html = """
            <div class="barcodeInfo">홍길동<span>12345</span></div>
            <a href="${SplibConfig.LOAN_PATH}"><span>3</span></a>
            <a href="${SplibConfig.INTERLIBRARY_PATH}"><span>2</span></a>"""
        assertEquals(IndexInfo("홍길동", 3, 2), SplibParsers.parseIndexContent(html))
    }

    @Test
    fun indexWithoutUserInfoMeansSignedOut() {
        assertThrows(AuthException::class.java) { SplibParsers.parseIndexContent("<html></html>") }
    }

    @Test
    fun loansKeepTitleLibraryDueAndBooksoleBadge() {
        val loans = SplibParsers.parseLoanStatus(
            loanRow("어린 왕자", "송파글마루도서관", "2026.10.07") +
                loanRow("모모", "송파스마트도서관(잠실나루역)", "2026.10.11", "책솔이 대출중")
        )
        assertEquals(listOf("어린 왕자", "모모"), loans.map { it.title })
        assertEquals(listOf("글마루", "스마트"), loans.map { it.library })
        assertEquals(listOf("2026.10.07", "2026.10.11"), loans.map { it.dueDate })
        assertEquals(listOf(false, true), loans.map { it.isBooksole })
    }

    @Test
    fun libraryNamesAreShortenedTheSameEverywhere() {
        assertEquals("영어", SplibParsers.abbreviateLibraryName("송파어린이영어도서관"))
        assertEquals("거마", SplibParsers.abbreviateLibraryName("송파거마도서관"))
        assertEquals("송파도서관", SplibParsers.abbreviateLibraryName("송파도서관"))
        assertEquals("", SplibParsers.abbreviateLibraryName(null))
    }

    @Test
    fun dooraeKeepsActiveStatusesAndCountsReturning() {
        val (entries, returning) = SplibParsers.parseDooraeStatus(
            dooraeRow("가는 책", "송파거마도서관", "송파위례도서관", "복귀중") +
                dooraeRow("오는 책", "송파돌마리도서관", "송파글마루도서관", "입수") +
                dooraeRow("신청한 책", "잠실본동", "송파거마도서관", "요청중신청취소") +
                dooraeRow("버튼 붙은 책", "잠실본동", "송파거마도서관", "요청중 <a>신청취소</a>") +
                dooraeRow("접수 전 책", "잠실본동", "송파거마도서관", "신청중") +
                dooraeRow("다 본 책", "잠실본동", "송파거마도서관", "완료") +
                dooraeRow("취소한 책", "잠실본동", "송파거마도서관", "신청취소") +
                dooraeRow("모르는 상태", "잠실본동", "송파거마도서관", "입수취소")
        )
        assertEquals(listOf("오는 책", "신청한 책", "버튼 붙은 책", "접수 전 책"), entries.map { it.title })
        assertEquals(listOf("입수", "요청중", "요청중", "신청중"), entries.map { it.status })
        assertEquals("돌마리", entries[0].receivingLibrary)
        assertEquals("글마루", entries[0].providingLibrary)
        assertEquals(1, returning)
    }

    @Test
    fun sameTitleFromTwoLibrariesStaysTwoEntries() {
        val (entries, _) = SplibParsers.parseDooraeStatus(
            dooraeRow("타오르는 강", "송파거마도서관", "송파위례도서관", "입수") +
                dooraeRow("타오르는 강", "송파돌마리도서관", "송파글마루도서관", "발송")
        )
        assertEquals(listOf("거마" to "입수", "돌마리" to "발송"), entries.map { it.receivingLibrary to it.status })
    }

    @Test
    fun reservationsReadRankWaitingAndPickupDeadline() {
        val waiting = row(
            "이방인",
            """<div class="info"><strong>송파거마도서관</strong><span>종합자료실</span></div>
               <div class="info"><span>예약순번 : 3 (5명 예약)</span></div>""",
            """<a onclick="fnLoanReservationCancelProc(1)">취소</a>""",
        )
        val ready = row(
            "1984",
            """<div class="info"><strong>송파글마루도서관</strong></div>
               <div class="info"><span>예약순번 : 1 (1명 예약)</span></div>""",
            """<span>예약일 2026.09.25</span><span>예약만기일 : 2026.10.8</span>""",
        )
        val reservations = SplibParsers.parseReservationStatus(waiting + ready)
        assertEquals(Reservation("이방인", "거마", 3, 5, null), reservations[0])
        assertEquals(LocalDate.of(2026, 10, 8), reservations[1].pickupDeadline)
        assertTrue(reservations[1].readyForPickup)
    }

    @Test
    fun maxPageIsCapped() {
        assertEquals(1, SplibParsers.parseMaxPage("<div></div>"))
        assertEquals(3, SplibParsers.parseMaxPage("""<div class="paging"><a>1</a><a>2</a><a>3</a><a>다음</a></div>"""))
        assertEquals(SplibParsers.MAX_PAGES, SplibParsers.parseMaxPage("""<div class="paging"><a>1</a><a>999</a></div>"""))
    }

    @Test
    fun rowsWithoutWrappersArePairedInOrder() {
        val html = """
            <div class="infoBox"><div class="title">A</div></div><div class="statusBox">입수</div>
            <div class="infoBox"><div class="title">B</div></div><div class="statusBox">발송</div>"""
        assertEquals(listOf("입수", "발송"), SplibParsers.parseDooraeStatus(html).first.map { it.status })
    }

    @Test
    fun datesWithoutZeroPaddingAreRead() {
        assertEquals(LocalDate.of(2026, 8, 5), SplibParsers.findDate("반납예정일 : 2026.8.5"))
        assertNull(SplibParsers.findDate("입수"))
    }

    @Test
    fun statusWordSplitsOnAnyWhitespace() {
        val (entries, _) = SplibParsers.parseDooraeStatus(
            dooraeRow("줄바꿈", "송파거마도서관", "송파위례도서관", "<span>발송\n      2026.10.01</span>") +
                dooraeRow("nbsp", "송파거마도서관", "송파위례도서관", "입수&nbsp;2026.10.05")
        )
        assertEquals(listOf("발송", "입수"), entries.map { it.status })
    }

    @Test
    fun indexWithoutPlainNameIsNotASignOut() {
        val html = """
            <div class="barcodeInfo">
              <strong>홍길동</strong> 님</div>
            <a href="${SplibConfig.LOAN_PATH}"><span>1</span></a>
            <a href="${SplibConfig.INTERLIBRARY_PATH}"><span>0</span></a>"""
        assertEquals("", SplibParsers.parseIndexContent(html).name)
    }

    @Test
    fun hugePageNumberIsCappedNotACrash() {
        assertEquals(SplibParsers.MAX_PAGES, SplibParsers.parseMaxPage("""<p class="paging"><span>12345678901</span></p>"""))
    }

    @Test
    fun reservationTitleJoinsTextLikeBeautifulSoup() {
        val html = row("어린<br>왕자", """<div class="info"><strong>송파거마도서관</strong></div>""", "")
        assertEquals("어린왕자", SplibParsers.parseReservationStatus(html).single().title)
    }
}
