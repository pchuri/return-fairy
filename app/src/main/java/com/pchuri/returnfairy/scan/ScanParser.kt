package com.pchuri.returnfairy.scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One OCR text line with the height of its bounding box (used to find titles). */
data class ScanLine(val text: String, val height: Int = 0, val top: Int = 0)

/** A book candidate extracted from a scan; the user confirms/edits before saving. */
data class ScannedBook(val title: String, val dueDate: LocalDate? = null)

/**
 * Result of reading a photo.
 *
 * [books] is what the parser is confident about. [candidateLines] is the
 * readable text it could not confidently interpret — the UI offers those so the
 * user can tap the title and the due date. That keeps the feature usable for
 * any receipt in any language, not only the layouts we happen to recognise.
 */
data class ScanReading(
    val books: List<ScannedBook>,
    val candidateLines: List<String> = emptyList(),
    val suggestedDate: LocalDate? = null,
)

/**
 * Heuristic parsers over OCR output. Kept pure so they can be unit tested
 * without a device or ML Kit.
 *
 * Receipt mode is the high-value path: a library checkout slip lists several
 * titles together with their due dates, so one photo registers everything.
 * Cover mode only yields a title (the largest text on the cover).
 */
object ScanParser {

    /** Lines at least this tall relative to the tallest count as title text. */
    private const val TITLE_HEIGHT_RATIO = 0.6

    private val YEAR_FIRST = listOf(
        // 2026.08.15 / 2026-08-15 / 2026/08/15
        Regex("""(20\d{2})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(\d{1,2})"""),
        // 2026년 8월 15일 · 2026年8月15日
        Regex("""(20\d{2})\s*[년年]\s*(\d{1,2})\s*[월月]\s*(\d{1,2})\s*[일日]?"""),
    )

    /** Day/month pair with a trailing year — order depends on locale. */
    private val DAY_MONTH_YEAR = Regex("""(\d{1,2})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(20\d{2}|\d{2})""")

    private val MONTH_NAMES = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )
    // 15 Aug 2026 · Aug 15, 2026 · August 15 2026
    private val DAY_MONTH_NAME = Regex("""(\d{1,2})\s+([A-Za-z]{3,9})\.?,?\s+(20\d{2})""")
    private val MONTH_NAME_DAY = Regex("""([A-Za-z]{3,9})\.?\s+(\d{1,2})\s*,?\s+(20\d{2})""")

    /**
     * Words that mark a due date, across the languages the app ships in plus a
     * few common ones. Used to pick the right date when a slip prints several.
     */
    private val DUE_KEYWORDS = listOf(
        "반납", "返却", "返還",
        "due", "return by", "return date", "date due", "expires",
        "vencimiento", "devolución", "rückgabe", "retour", "scadenza",
    )

    fun looksLikeDueDateLine(text: String): Boolean {
        val lower = text.lowercase()
        return DUE_KEYWORDS.any { lower.contains(it) }
    }

    /** Lines that are receipt chrome rather than book titles. */
    private val NOISE_PATTERNS = listOf(
        Regex("""도서\s*대출\s*(확인|증|현황|영수)"""),
        Regex("""반납\s*(예정)?\s*일"""),
        Regex("""대출\s*일(자)?"""),
        Regex("""(회원|이용자)\s*(번호|명)"""),
        Regex("""도서관|출력|발행|전화|TEL|http|www""", RegexOption.IGNORE_CASE),
        Regex("""^[\s\-=*_·.]+$"""),
        Regex("""^\d[\d\s\-.:]*$"""),          // bare numbers / barcodes / times
        Regex("""^\d{1,2}\s*[:시]\s*\d{2}"""),  // times
        Regex("""총\s*\d+\s*권"""),
    )

    /**
     * Find a date in a line, accepting the formats used around the world.
     * `dayFirst` resolves the ambiguity of 05/08/2026 — pass true for locales
     * that write day before month (most of the world outside the US).
     */
    @JvmOverloads
    fun findDate(text: String, dayFirst: Boolean = true): LocalDate? {
        for (pattern in YEAR_FIRST) {
            val m = pattern.find(text) ?: continue
            val (y, mo, d) = m.destructured
            date(y.toInt(), mo.toInt(), d.toInt())?.let { return it }
        }

        DAY_MONTH_NAME.find(text)?.let { m ->
            val (d, name, y) = m.destructured
            monthOf(name)?.let { mo -> date(y.toInt(), mo, d.toInt())?.let { return it } }
        }
        MONTH_NAME_DAY.find(text)?.let { m ->
            val (name, d, y) = m.destructured
            monthOf(name)?.let { mo -> date(y.toInt(), mo, d.toInt())?.let { return it } }
        }

        DAY_MONTH_YEAR.find(text)?.let { m ->
            val (a, b, rawYear) = m.destructured
            val year = rawYear.toInt().let { if (it < 100) 2000 + it else it }
            val first = a.toInt()
            val second = b.toInt()
            // A value above 12 can only be the day, whatever the locale says.
            val (day, month) = when {
                first > 12 -> first to second
                second > 12 -> second to first
                dayFirst -> first to second
                else -> second to first
            }
            date(year, month, day)?.let { return it }
        }
        return null
    }

    private fun monthOf(name: String): Int? {
        val key = name.lowercase().take(3)
        val index = MONTH_NAMES.indexOf(key)
        return if (index >= 0) index + 1 else null
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun isNoise(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2) return true
        return NOISE_PATTERNS.any { it.containsMatchIn(t) }
    }

    /** Strip a trailing/leading date and list numbering from a title line. */
    private fun cleanTitle(line: String): String {
        var t = line
        (YEAR_FIRST + listOf(DAY_MONTH_YEAR, DAY_MONTH_NAME, MONTH_NAME_DAY))
            .forEach { t = it.replace(t, " ") }
        t = t.replace(Regex("""^\s*\d{1,2}\s*[.)]\s*"""), "")   // "1. ", "2) "
        t = t.replace(Regex("""\s*[\[(]\s*\d{5,}\s*[\])]\s*"""), " ") // barcode in brackets
        return t.trim().trim('-', '·', ':', '|').trim()
    }

    // Labels on a Korean library checkout slip. Branch libraries print
    // variants — "도 서 명 :" or "서명 :", with or without a leading item
    // number, and sometimes without a colon on the header fields. OCR also
    // inserts spaces between label characters, so every gap allows whitespace.
    private val ITEM_PREFIX = """(?:[(（\[]?\s*\d{1,2}\s*[)）\].]?\s*)?"""
    private val LABEL_TITLE = Regex("""^\s*$ITEM_PREFIX(?:도\s*)?서\s*명\s*[:：]""")
    private val LABEL_DUE = Regex("""반\s*납\s*예\s*정\s*일\s*자?\s*[:：]?""")
    /**
     * Bibliographic/administrative fields that must never be folded into a
     * title. Enumerated rather than matched generically, because a wrapped
     * title line can itself begin with something that looks like a label
     * (e.g. "라이프 : 시시한 미니멀리스트의…").
     */
    private val LABEL_IGNORED = listOf(
        Regex("""^\s*대\s*출\s*자\s*명?\s*[:：]?"""),
        Regex("""^\s*대\s*출\s*일\s*자?\s*[:：]?"""),
        Regex("""^\s*대\s*출\s*권\s*수\s*[:：]?"""),
        Regex("""^\s*등\s*록\s*번\s*호\s*[:：]?"""),
        Regex("""^\s*청\s*구\s*기\s*호\s*[:：]?"""),
        Regex("""^\s*반\s*납\s*자\s*[:：]?"""),
        Regex("""^\s*저\s*자\s*[:：]"""),
        Regex("""^\s*발\s*행\s*(자|처|년)\s*[:：]"""),
        Regex("""^\s*출\s*판\s*(사|년)\s*[:：]"""),
        Regex("""^\s*소\s*장\s*처\s*[:：]"""),
        Regex("""^\s*자\s*료\s*실\s*[:：]"""),
        Regex("""^\s*자\s*료\s*위\s*치\s*[:：]"""),
        Regex("""^\s*ISBN\s*[:：]""", RegexOption.IGNORE_CASE),
    )
    private val ITEM_MARKER = Regex("""^\s*[(（\[]?\s*\d{1,2}\s*[)）\]]\s*$""")
    private val RECEIPT_HEADING = Regex(
        """대\s*출\s*확\s*인\s*증|대\s*출\s*증|영\s*수\s*증|자\s*료\s*위\s*치\s*안\s*내|회\s*원\s*용"""
    )

    /**
     * Parse a library checkout receipt.
     *
     * Uses the labelled layout when the slip has "도서명" markers (the format
     * printed by Korean library self-checkout machines): a title may wrap onto
     * the next line without a label, and the book's due date follows on its own
     * "반납예정일자" line. Falls back to loose heuristics for other layouts.
     */
    fun parseReceipt(lines: List<String>): List<ScannedBook> =
        if (lines.any { LABEL_TITLE.containsMatchIn(it) }) {
            parseLabelledReceipt(lines)
        } else {
            parseLooseReceipt(lines)
        }

    /**
     * Read a receipt of unknown origin. Recognised layouts return books
     * directly; anything else comes back as candidate lines plus the most
     * likely due date, for the user to pick from.
     */
    fun readReceipt(lines: List<String>, dayFirst: Boolean = true): ScanReading {
        if (lines.any { LABEL_TITLE.containsMatchIn(it) }) {
            val books = parseLabelledReceipt(lines)
            if (books.isNotEmpty()) return ScanReading(books)
        }

        val loose = parseLooseReceipt(lines)
        val dated = loose.filter { it.dueDate != null }
        // Confident only when the loose pass actually found dates to attach.
        if (dated.isNotEmpty()) return ScanReading(dated)

        return ScanReading(
            books = emptyList(),
            candidateLines = pickCandidateLines(lines),
            suggestedDate = suggestDueDate(lines, dayFirst),
        )
    }

    /** Lines a title could plausibly be, longest-looking first. */
    fun pickCandidateLines(lines: List<String>): List<String> = lines
        .map { cleanTitle(it) }
        .filter { it.length >= 2 && !isNoise(it) && findDate(it) == null }
        .distinct()
        .take(20)

    /**
     * Best guess at the due date: prefer a date on a line that mentions
     * returning/due, otherwise the latest future date on the slip (a checkout
     * slip's due date is later than its loan date).
     */
    fun suggestDueDate(lines: List<String>, dayFirst: Boolean = true): LocalDate? {
        val labelled = lines.filter { looksLikeDueDateLine(it) }
            .mapNotNull { findDate(it, dayFirst) }
        if (labelled.isNotEmpty()) return labelled.max()

        val all = lines.mapNotNull { findDate(it, dayFirst) }
        val today = LocalDate.now()
        return all.filter { !it.isBefore(today) }.minOrNull() ?: all.maxOrNull()
    }

    private fun parseLabelledReceipt(lines: List<String>): List<ScannedBook> {
        val books = mutableListOf<ScannedBook>()
        val title = StringBuilder()
        var accumulating = false

        fun flush(due: LocalDate? = null) {
            val text = title.toString().trim().trim('-', '·', ':', '|').trim()
            if (text.isNotBlank()) books.add(ScannedBook(text, due))
            title.clear()
            accumulating = false
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                // Due date first: "반납예정일자" must never be mistaken for a
                // title, and "대출일자" must never be mistaken for a due date.
                LABEL_DUE.containsMatchIn(line) -> flush(findDate(line))

                LABEL_TITLE.containsMatchIn(line) -> {
                    flush() // previous entry had no due date
                    title.append(line.substringAfter(':').substringAfter('：').trim())
                    accumulating = true
                }

                LABEL_IGNORED.any { it.containsMatchIn(line) } ||
                    ITEM_MARKER.matches(line) ||
                    RECEIPT_HEADING.containsMatchIn(line) -> accumulating = false

                // Wrapped continuation of the current title. Receipts wrap by
                // character count, so the break can fall mid-word; join directly.
                // Footers (library name, phone, URL) end the title instead.
                accumulating -> if (isNoise(line)) accumulating = false else title.append(line)

                else -> Unit // footer, library name, stray text
            }
        }
        flush()
        return books.distinctBy { it.title }
    }

    private fun parseLooseReceipt(lines: List<String>): List<ScannedBook> {
        val books = mutableListOf<ScannedBook>()
        val pendingTitles = mutableListOf<String>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val date = findDate(line)
            val title = cleanTitle(line).takeUnless { it.isBlank() || isNoise(it) }

            when {
                // Title and date together → complete entry
                date != null && title != null -> books.add(ScannedBook(title, date))

                // Date alone → applies to the titles seen just before it
                date != null && pendingTitles.isNotEmpty() -> {
                    pendingTitles.forEach { books.add(ScannedBook(it, date)) }
                    pendingTitles.clear()
                }

                date == null && title != null -> pendingTitles.add(title)
            }
        }

        // Titles with no date at all are still worth offering to the user
        pendingTitles.forEach { books.add(ScannedBook(it, null)) }
        return books.distinctBy { it.title }
    }

    /**
     * Parse a book cover. Titles are often set across several lines in a large
     * face ("생 활 / 스포츠 / 지도사"), so all lines close in size to the
     * largest are joined top-to-bottom rather than picking a single line.
     */
    fun parseCover(lines: List<ScanLine>): ScannedBook? {
        val candidates = lines
            .map { it.copy(text = cleanTitle(it.text)) }
            .filter { it.text.isNotBlank() && !isNoise(it.text) }
        if (candidates.isEmpty()) return null

        if (candidates.none { it.height > 0 }) {
            return candidates.minByOrNull { it.top }?.let { ScannedBook(it.text) }
        }

        val tallest = candidates.maxOf { it.height }
        val titleBlock = candidates
            .filter { it.height >= tallest * TITLE_HEIGHT_RATIO }
            .sortedBy { it.top }
        val title = titleBlock.joinToString(" ") { it.text }
            .replace(Regex("""\s+"""), " ")
            .trim()
        return title.takeIf { it.isNotBlank() }?.let { ScannedBook(it) }
    }

    /**
     * Read a cover, returning the best-guess title plus every readable line so
     * the user can assemble or correct the title by tapping. Stylised covers
     * (mixed sizes, promo badges, handwriting) rarely yield a clean guess, and
     * tapping a few lines beats retyping the whole title.
     */
    fun readCover(lines: List<ScanLine>): ScanReading {
        val guess = parseCover(lines)
        val candidates = lines
            .map { cleanTitle(it.text) to it.height }
            .filter { (text, _) -> text.length >= 2 && !isNoise(text) }
            .sortedByDescending { (_, height) -> height }
            .map { (text, _) -> text }
            .distinct()
            .take(20)
        return ScanReading(
            books = listOfNotNull(guess),
            candidateLines = candidates,
        )
    }


    val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
}
