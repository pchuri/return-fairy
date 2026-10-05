package com.pchuri.returnfairy.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScanParserTest {

    @Test
    fun findsCommonKoreanDateFormats() {
        assertEquals(LocalDate.of(2026, 8, 15), ScanParser.findDate("반납예정일 2026.08.15"))
        assertEquals(LocalDate.of(2026, 8, 15), ScanParser.findDate("2026-08-15"))
        assertEquals(LocalDate.of(2026, 8, 5), ScanParser.findDate("2026년 8월 5일까지"))
        assertNull(ScanParser.findDate("반납일 없음"))
    }

    @Test
    fun parsesReceiptWithTitleAndDateOnSameLine() {
        val lines = listOf(
            "송파도서관 도서대출확인증",
            "회원명 홍길동",
            "1. 어린 왕자   2026.08.15",
            "2. 모모   2026.08.15",
            "총 2권",
        )
        val books = ScanParser.parseReceipt(lines)

        assertEquals(2, books.size)
        assertEquals("어린 왕자", books[0].title)
        assertEquals(LocalDate.of(2026, 8, 15), books[0].dueDate)
        assertEquals("모모", books[1].title)
    }

    @Test
    fun attachesStandaloneDateToPrecedingTitles() {
        val lines = listOf(
            "도서 대출 확인증",
            "어린 왕자",
            "모모",
            "반납예정일 2026.08.20",
        )
        val books = ScanParser.parseReceipt(lines)

        assertEquals(2, books.size)
        assertTrue(books.all { it.dueDate == LocalDate.of(2026, 8, 20) })
    }

    @Test
    fun dropsReceiptChromeAndBareNumbers() {
        val lines = listOf(
            "송파도서관",
            "대출일자 2026.08.01",
            "회원번호 12345678",
            "-----------------",
            "9788934972464",
            "14:32",
            "영원한 제국   2026.08.15",
        )
        val books = ScanParser.parseReceipt(lines)

        assertEquals(1, books.size)
        assertEquals("영원한 제국", books.single().title)
        assertEquals(LocalDate.of(2026, 8, 15), books.single().dueDate)
    }

    @Test
    fun keepsTitlesEvenWhenNoDateFound() {
        val books = ScanParser.parseReceipt(listOf("어린 왕자", "모모"))

        assertEquals(2, books.size)
        assertTrue(books.all { it.dueDate == null })
    }

    /**
     * Layout of a 송파 스마트도서관 self-checkout slip: labelled fields,
     * titles wrapped mid-word onto a second line, a 대출일자 (loan date) that
     * must not be mistaken for the due date, and 등록번호 barcode lines.
     * All values are made up; only the layout follows the printed slip.
     */
    @Test
    fun parsesSongpaSelfCheckoutSlip() {
        val lines = listOf(
            "대출 확인증",
            "대출자 : 홍*동(000*******000)",
            "대출일자 : 2026-01-28",
            "대출권수 : 2",
            "(1)",
            "도 서 명 : 작은 아씨들 : 루이자 메이 올",
            "컷 장편소설. 2, 좋은 아내들",
            "등록번호 : EMW000000001",
            "반납예정일자 : 2026/02/11",
            "(2)",
            "도 서 명 : 어린 왕자 : 생텍쥐페리 장",
            "편소설",
            "등록번호 : EMW000000002",
            "반납예정일자 : 2026/02/11",
            "송파 스마트도서관",
        )

        val books = ScanParser.parseReceipt(lines)

        assertEquals(2, books.size)
        assertEquals("작은 아씨들 : 루이자 메이 올컷 장편소설. 2, 좋은 아내들", books[0].title)
        assertEquals("어린 왕자 : 생텍쥐페리 장편소설", books[1].title)
        assertTrue(
            "due date must come from 반납예정일자, not 대출일자",
            books.all { it.dueDate == LocalDate.of(2026, 2, 11) },
        )
    }

    /**
     * A second slip layout, from 송파 글마루도서관 — the same district prints a
     * different variant: "서명" instead of "도서명", the item number on the
     * title line, header fields without colons, and an extra 청구기호 field.
     */
    @Test
    fun parsesGeulmaruSlipVariant() {
        val lines = listOf(
            "대출 확인증",
            "대출자명",
            "대출일자",
            "대출권수",
            "1. 서명 : 월든 : 헨리 데이비드 소로",
            "에세이",
            "반납예정일자 : 2026/07/11",
            "등록번호 : OM0000000001",
            "청구기호 : 848-소28월",
            "- 송파 글마루 도서관 -",
        )

        val books = ScanParser.parseReceipt(lines)

        assertEquals(1, books.size)
        assertTrue(
            "title should start with the book name, got '${books.single().title}'",
            books.single().title.startsWith("월든"),
        )
        assertEquals(LocalDate.of(2026, 7, 11), books.single().dueDate)
    }

    /** 대출일자 carries a date too; only 반납예정일자 may become the due date. */
    @Test
    fun loanDateIsNeverUsedAsDueDate() {
        val lines = listOf(
            "대출 확인증",
            "대출일자 : 2026-08-01",
            "1. 서명 : 어린 왕자",
            "반납예정일자 : 2026/08/15",
        )
        val books = ScanParser.parseReceipt(lines)

        assertEquals(1, books.size)
        assertEquals(LocalDate.of(2026, 8, 15), books.single().dueDate)
    }

    /**
     * A third slip layout (잠실나루역 스마트도서관): no space after the title
     * colon, 등록번호 printed before 반납예정일자, two books with no item
     * marker before the second, and a wrapped title whose continuation line
     * itself starts with something that looks like a label ("라이프 : …").
     */
    @Test
    fun parsesJamsilnaruSlipWithTwoBooks() {
        val lines = listOf(
            "대출 확인증",
            "대출자",
            "대출일자 2026-08-21",
            "대출권수 : 2",
            "(1)",
            "도 서 명 :부자 아빠 가난한 아빠 : 0원부",
            "터 시작하는 처음 돈 공부",
            "등록번호 : QA0000000001",
            "반납예정일자 : 2026/09/04",
            "도 서 명 :조용한 숲속의 작고 단정한 미니멀",
            "라이프 : 어느 정원사의 사계절 좌충우",
            "돌 일상",
            "등록번호 : QA0000000002",
            "반납예정일자 : 2026/09/04",
            "잠실나루역 스마트도서관",
            "회원용",
        )

        val books = ScanParser.parseReceipt(lines)

        assertEquals(2, books.size)
        assertEquals("부자 아빠 가난한 아빠 : 0원부터 시작하는 처음 돈 공부", books[0].title)
        assertEquals(
            "조용한 숲속의 작고 단정한 미니멀라이프 : 어느 정원사의 사계절 좌충우돌 일상",
            books[1].title,
        )
        assertTrue(books.all { it.dueDate == LocalDate.of(2026, 9, 4) })
    }

    /**
     * A shelf-location slip (자료위치안내) rather than a checkout slip: it has
     * no due date and carries 저자 / 발행자 / 소장처 / 자료실 fields that must
     * not be folded into the title.
     */
    @Test
    fun parsesShelfLocationSlipWithoutMergingOtherFields() {
        val lines = listOf(
            "[자료위치안내]",
            "청구기호 : 650-1234 ㄱ",
            "서    명 : 그림 그리는 법  : 기초부터 ...",
            "저    자 : 홍길동, 예시기획 지음",
            "발 행 자 : 예시출판",
            "등록번호 : EMW000000003",
            "소장처 : 송파도서관",
            "자료실 : [송파]3층 인사자실",
        )

        val books = ScanParser.parseReceipt(lines)

        assertEquals(1, books.size)
        val title = books.single().title
        assertTrue("title should start with the book name, got '$title'",
            title.startsWith("그림 그리는 법"))
        listOf("홍길동", "예시출판", "송파도서관", "인사자실").forEach { field ->
            assertTrue("'$field' must not leak into the title: '$title'", !title.contains(field))
        }
        assertNull("a location slip has no due date", books.single().dueDate)
    }

    @Test
    fun labelledSlipDropsBarcodesAndBorrowerLines() {
        val lines = listOf(
            "대출 확인증",
            "대출자 : 홍*동(000*******000)",
            "대출일자 : 2026-08-01",
            "등록번호 : EMW000000004",
            "도 서 명 : 모모",
            "반납예정일자 : 2026/08/15",
        )
        val books = ScanParser.parseReceipt(lines)

        assertEquals(1, books.size)
        assertEquals("모모", books.single().title)
        assertEquals(LocalDate.of(2026, 8, 15), books.single().dueDate)
    }

    @Test
    fun labelledTitleWithoutDueDateIsStillOffered() {
        val books = ScanParser.parseReceipt(
            listOf("대출 확인증", "도 서 명 : 어린 왕자", "송파 스마트도서관")
        )
        assertEquals(1, books.size)
        assertEquals("어린 왕자", books.single().title)
        assertNull(books.single().dueDate)
    }

    @Test
    fun coverPicksLargestText() {
        val lines = listOf(
            ScanLine("이인화 장편소설", height = 20, top = 200),
            ScanLine("영원한 제국", height = 80, top = 100),
            ScanLine("세계사", height = 15, top = 400),
        )
        assertEquals("영원한 제국", ScanParser.parseCover(lines)?.title)
    }

    @Test
    fun coverFallsBackToTopLineWithoutHeights() {
        val lines = listOf(
            ScanLine("모모", top = 50),
            ScanLine("미하엘 엔데", top = 300),
        )
        assertEquals("모모", ScanParser.parseCover(lines)?.title)
    }

    @Test
    fun coverReturnsNullWhenOnlyNoise() {
        val lines = listOf(ScanLine("9788934972464", height = 40), ScanLine("-----", height = 10))
        assertNull(ScanParser.parseCover(lines))
    }
}
