package com.pchuri.returnfairy.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiResponseParserTest {

    @Test
    fun parsesCleanJsonArray() {
        val books = AiResponseParser.parse(
            """[{"title": "어린 왕자", "due": "2026-08-15"}, {"title": "모모", "due": null}]"""
        )
        assertEquals(2, books.size)
        assertEquals("어린 왕자", books[0].title)
        assertEquals(LocalDate.of(2026, 8, 15), books[0].dueDate)
        assertEquals("모모", books[1].title)
        assertNull(books[1].dueDate)
    }

    @Test
    fun stripsMarkdownFencesAndProse() {
        val books = AiResponseParser.parse(
            """
            Here are the books I can see in the photo:
            ```json
            [{"title": "영원한 제국", "due": "2026-08-15"}]
            ```
            Let me know if you need anything else!
            """.trimIndent()
        )
        assertEquals(1, books.size)
        assertEquals("영원한 제국", books.single().title)
    }

    @Test
    fun acceptsBareObjectForSingleBook() {
        val books = AiResponseParser.parse("""{"title": "The Little Prince", "due": null}""")
        assertEquals(1, books.size)
        assertEquals("The Little Prince", books.single().title)
    }

    @Test
    fun acceptsAlternateKeyNames() {
        val books = AiResponseParser.parse(
            """[{"book_title": "지구에서 한아뿐", "due_date": "2026/08/20"}]"""
        )
        assertEquals(1, books.size)
        assertEquals("지구에서 한아뿐", books.single().title)
        assertEquals(LocalDate.of(2026, 8, 20), books.single().dueDate)
    }

    @Test
    fun dropsEntriesWithoutTitlesAndDeduplicates() {
        val books = AiResponseParser.parse(
            """[{"title": "모모"}, {"due": "2026-08-15"}, {"title": ""}, {"title": "모모"}]"""
        )
        assertEquals(1, books.size)
        assertEquals("모모", books.single().title)
    }

    @Test
    fun handlesBracketsInsideTitleStrings() {
        val books = AiResponseParser.parse(
            """[{"title": "수학의 정석 [기본편]", "due": null}]"""
        )
        assertEquals("수학의 정석 [기본편]", books.single().title)
    }

    @Test
    fun emptyForRefusalsOrNoJson() {
        assertTrue(AiResponseParser.parse("I cannot see any books in this photo.").isEmpty())
        assertTrue(AiResponseParser.parse("[]").isEmpty())
        assertTrue(AiResponseParser.parse("").isEmpty())
    }
}
