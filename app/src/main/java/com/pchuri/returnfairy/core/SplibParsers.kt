package com.pchuri.returnfairy.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate

/**
 * Parsers for the splib.or.kr "my page" lists.
 *
 * Kotlin port of songpa-loan-tracker `songpa_core/splib.py` and `splib_utils.py`. Keep the two
 * in step: when the site changes, fix both (and `scriptable/songpa-loan-tracker.js`).
 */

data class IndexInfo(val name: String, val totalBorrowedBooks: Int, val interlibraryLoans: Int)

data class LoanRow(
    val title: String,
    val library: String,
    val loanDate: String,
    val dueDate: String,
    val isBooksole: Boolean,
)

data class DooraeRow(
    val title: String,
    val status: String,
    val receivingLibrary: String,
    val providingLibrary: String,
)

object SplibParsers {
    private const val ROW_CLASS = "myArticle-list"
    private const val CANCEL_BUTTON_TEXT = "신청취소"
    private const val EXPIRY_LABEL = "예약만기일"
    const val MAX_PAGES = 20

    private val DATE_PATTERN = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})""")
    private val RANK_PATTERN = Regex("""예약순번\s*:\s*(\d+)""")
    private val WAITING_COUNT_PATTERN = Regex("""\((\d+)\s*명\s*예약\)""")
    private val PAGE_NUMBER_PATTERN = Regex("""^\d+$""")
    /**
     * Unicode whitespace (nbsp, ideographic space…), like Python's \s and str.split().
     * Spelled out because Android's ICU regex rejects the (?U) flag that desktop Java accepts.
     */
    private val WHITESPACE = Regex("""[\s\p{Z}]+""")

    /** Branches whose local name differs from the official one. */
    private val LIBRARY_ALIASES = mapOf(
        "송파어린이영어도서관" to "영어도서관",
        "송파스마트도서관(잠실나루역)" to "스마트도서관",
        "송파어린이도서관" to "엘스도서관",
    )

    /** Short library name for cards. Every list uses this one function so a branch reads the same everywhere. */
    fun abbreviateLibraryName(libraryName: String?): String {
        if (libraryName.isNullOrEmpty()) return ""
        val aliased = LIBRARY_ALIASES[libraryName] ?: libraryName
        val shortened = aliased.replace("송파", "").replace("도서관", "").trim()
        // A name made only of the stripped words (송파도서관) would vanish; keep the original then.
        return shortened.ifEmpty { aliased }
    }

    /** BeautifulSoup get_text(strip=True): each text piece stripped and joined without separator. */
    private fun Element.strippedText(): String = buildString {
        for (node in this@strippedText.textNodesDeep()) append(node.wholeText.trim())
    }

    /** BeautifulSoup get_text(" ", strip=True). */
    private fun Element.spacedText(): String =
        textNodesDeep().map { it.wholeText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun Element.textNodesDeep(): List<TextNode> {
        val out = mutableListOf<TextNode>()
        fun walk(e: Element) {
            for (child in e.childNodes()) {
                when (child) {
                    is TextNode -> out.add(child)
                    is Element -> walk(child)
                }
            }
        }
        walk(this)
        return out
    }

    /** Python str.strip(): also drops nbsp, which Java's trim() keeps. */
    private fun String.pyStrip(): String = trim { it.isWhitespace() || it == '\u00A0' }

    private fun squash(text: String?): String = (text ?: "").replace(WHITESPACE, " ").trim()

    /** BeautifulSoup get_text() then squashed: text pieces joined as-is (a <br> adds no space). */
    private fun Element.squashedText(): String = squash(textNodesDeep().joinToString("") { it.wholeText })

    /** First date in [text] as a LocalDate. Accepts 2026.8.29 as well as 2026.08.29. */
    fun findDate(text: String?): LocalDate? {
        val match = DATE_PATTERN.find(text ?: "") ?: return null
        val (y, m, d) = match.destructured
        return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }

    /**
     * (infoBox, statusBox) per list row. Every list wraps a row in .myArticle-list; pairing inside
     * the wrapper keeps a row with a missing box from shifting the rest. Without wrappers, falls
     * back to pairing in document order.
     */
    private fun rows(content: String): List<Pair<Element, Element?>> {
        val doc = Jsoup.parse(content)
        val wrappers = doc.getElementsByClass(ROW_CLASS)
        if (wrappers.isNotEmpty()) {
            return wrappers.mapNotNull { row ->
                val info = row.selectFirst("div.infoBox") ?: return@mapNotNull null
                info to row.selectFirst("div.statusBox")
            }
        }
        val out = mutableListOf<Pair<Element, Element?>>()
        var pending: Element? = null
        for (element in doc.select("div.infoBox, div.statusBox")) {
            if (element.hasClass("infoBox")) {
                pending?.let { out.add(it to null) }
                pending = element
            } else if (pending != null) {
                out.add(pending to element)
                pending = null
            }
        }
        pending?.let { out.add(it to null) }
        return out
    }

    fun parseIndexContent(content: String): IndexInfo {
        val doc = Jsoup.parse(content)
        val barcodeInfo = doc.selectFirst("div.barcodeInfo")
            ?: throw AuthException(SplibErrorKind.SESSION_EXPIRED, "index page missing user info")
        // The name may be missing or wrapped in a tag; that is not a sign-out (the label falls back).
        val name = (barcodeInfo.childNodes().firstOrNull() as? TextNode)?.wholeText?.pyStrip().orEmpty()

        val loanLink = doc.selectFirst("a[href=${SplibConfig.LOAN_PATH}]")
        val interlibraryLink = doc.selectFirst("a[href=${SplibConfig.INTERLIBRARY_PATH}]")
        if (loanLink == null || interlibraryLink == null) {
            throw AuthException(SplibErrorKind.SESSION_EXPIRED, "index page missing navigation links")
        }
        val numLoans = loanLink.selectFirst("span")?.text()?.trim()?.toIntOrNull() ?: 0
        val numInterlibrary = interlibraryLink.selectFirst("span")?.text()?.trim()?.toIntOrNull() ?: 0
        return IndexInfo(name, numLoans, numInterlibrary)
    }

    fun parseLoanStatus(content: String): List<LoanRow> = rows(content).mapNotNull { (info, status) ->
        val title = info.selectFirst("div.title") ?: return@mapNotNull null
        val infos = info.select("div.info")
        val library = infos.firstOrNull()?.selectFirst("span")?.selectFirst("strong") ?: return@mapNotNull null
        val dates = infos.getOrNull(1)?.select("span") ?: return@mapNotNull null
        if (dates.size != 2) return@mapNotNull null
        LoanRow(
            title = title.strippedText(),
            library = abbreviateLibraryName(library.strippedText()),
            loanDate = dates[0].strippedText().replace("대출일 : ", ""),
            dueDate = dates[1].strippedText().replace("반납예정일 : ", "").replace("반납일 : ", ""),
            isBooksole = status != null && "책솔이" in status.text(),
        )
    }

    private fun libraryField(infos: List<Element>, label: String): String? {
        for (info in infos) {
            for (span in info.select("span")) {
                if (label in span.text()) return span.strippedText().substringAfterLast(':').trim()
            }
        }
        return null
    }

    /**
     * The status word of a row. Pending requests carry the cancel button text ("요청중 신청취소",
     * or glued "요청중신청취소"); only that suffix is dropped. No prefix matching, so an unknown
     * status such as 입수취소 is never mistaken for 입수.
     */
    internal fun dooraeStatus(statusBox: Element?): String {
        // Any whitespace, nbsp included, like Python str.split().
        val first = statusBox?.spacedText()?.split(WHITESPACE)?.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (first != CANCEL_BUTTON_TEXT && first.endsWith(CANCEL_BUTTON_TEXT)) {
            return first.removeSuffix(CANCEL_BUTTON_TEXT)
        }
        return first
    }

    /**
     * Interlibrary rows still in progress, plus the number of 복귀중 rows. Kept as a list, not keyed
     * by title: the same title can come from two libraries at once.
     */
    fun parseDooraeStatus(content: String): Pair<List<DooraeRow>, Int> {
        val entries = mutableListOf<DooraeRow>()
        var returning = 0
        for ((info, statusBox) in rows(content)) {
            val status = dooraeStatus(statusBox)
            // Counted even for rows without a title, or the active count comes out too high.
            if (status == DooraeStatus.RETURNING) returning++
            val title = info.selectFirst("div.title") ?: continue
            val infos = info.select("div.info")
            entries.add(
                DooraeRow(
                    title = title.strippedText(),
                    status = status,
                    receivingLibrary = abbreviateLibraryName(libraryField(infos, "수령도서관 :")),
                    providingLibrary = abbreviateLibraryName(libraryField(infos, "제공도서관 :")),
                )
            )
        }
        return entries.filter { it.status in DooraeStatus.ACTIVE } to returning
    }

    private fun labelledSpan(infos: List<Element>, label: String): String {
        for (info in infos) {
            for (span in info.select("span")) {
                val text = squash(span.text())
                if (text.startsWith(label)) return text
            }
        }
        return ""
    }

    /** Pickup deadline after the 예약만기일 label only; an earlier date in the box is a different date. */
    private fun expiryDate(statusBox: Element): LocalDate? {
        val text = squash(statusBox.spacedText())
        if (EXPIRY_LABEL !in text) return null
        return findDate(text.substringAfter(EXPIRY_LABEL))
    }

    fun parseReservationStatus(content: String): List<Reservation> = rows(content).mapNotNull { (info, status) ->
        val title = info.selectFirst("div.title") ?: return@mapNotNull null
        val infos = info.select("div.info")
        val library = infos.firstOrNull()?.selectFirst("strong")?.let { abbreviateLibraryName(squash(it.text())) } ?: ""
        val rankText = labelledSpan(infos, "예약순번")
        Reservation(
            title = title.squashedText(),
            library = library,
            rank = RANK_PATTERN.find(rankText)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            waitingCount = WAITING_COUNT_PATTERN.find(rankText)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            // Arrival is told only by the presence of 예약만기일 (도서현황 always reads 대출중).
            pickupDeadline = status?.let { expiryDate(it) },
        )
    }

    /** Last page number in the pager, capped so an odd pager cannot cause hundreds of requests. */
    fun parseMaxPage(content: String): Int {
        val paging = Jsoup.parse(content).selectFirst(".paging") ?: return 1
        val pages = paging.textNodesDeep().map { it.wholeText.pyStrip() }
            .filter { PAGE_NUMBER_PATTERN.matches(it) }.map { it.toBigInteger().min(MAX_PAGES.toBigInteger()).toInt() }
        return pages.maxOrNull() ?: 1
    }
}

/**
 * Combines the lists like songpa_core `_fetch_user_info`, then classifies each book like
 * songpa_cli `report.normalize_book`.
 */
internal fun assembleAccount(
    label: String,
    userId: String = "",
    loans: List<LoanRow>,
    doorae: List<DooraeRow>,
    reservations: List<Reservation>,
): AccountStatus {
    // Only used to decorate a loan that is also an interlibrary entry; the first entry wins.
    val dooraeByTitle = LinkedHashMap<String, DooraeRow>()
    doorae.forEach { dooraeByTitle.putIfAbsent(it.title, it) }
    val loanTitles = loans.map { it.title }.toSet()

    val books = mutableListOf<LibraryBook>()
    for (loan in loans) {
        val entry = dooraeByTitle[loan.title]
        val isInterlibrary = loan.isBooksole || entry != null
        books.add(
            classify(
                title = loan.title,
                raw = loan.dueDate,
                isInterlibrary = isInterlibrary,
                library = if (isInterlibrary) entry?.receivingLibrary.orEmpty() else loan.library,
                providing = if (isInterlibrary) entry?.providingLibrary.orEmpty() else "",
            )
        )
    }
    for (entry in doorae) {
        if (entry.title in loanTitles) continue
        books.add(classify(entry.title, entry.status, true, entry.receivingLibrary, entry.providingLibrary))
    }
    return AccountStatus(label = label, userId = userId, books = books, reservations = reservations)
}

private fun classify(title: String, raw: String, isInterlibrary: Boolean, library: String, providing: String): LibraryBook {
    val due = SplibParsers.findDate(raw)
    val (status, transit) = when {
        due != null -> BookStatus.LOANED to null
        raw == DooraeStatus.OBTAINED -> BookStatus.READY_FOR_PICKUP to raw
        isInterlibrary && raw in DooraeStatus.ACTIVE -> BookStatus.IN_TRANSIT to raw
        // A loan whose due date could not be read: keep it a loan rather than a transfer.
        else -> BookStatus.LOANED to null
    }
    return LibraryBook(
        title = title,
        status = status,
        dueDate = due,
        rawDue = raw,
        transitStatus = transit,
        isInterlibrary = isInterlibrary,
        library = library,
        providingLibrary = providing,
    )
}
