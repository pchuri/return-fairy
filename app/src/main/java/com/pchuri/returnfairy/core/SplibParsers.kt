package com.pchuri.returnfairy.core

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

data class LoanBook(
    val title: String,
    val library: String,
    val loanDate: String,
    val dueDate: String,
    val isBooksole: Boolean,
)

data class DooraeBook(
    val status: String,
    val receivingLibrary: String?,
)

data class IndexInfo(
    val name: String,
    val totalBorrowedBooks: Int,
    val interlibraryLoans: Int,
)

object SplibParsers {

    fun convertLibraryNameToAbbreviations(libraryName: String): String = when (libraryName) {
        "송파어린이영어도서관" -> "영어도서관"
        "송파스마트도서관(잠실나루역)" -> "스마트도서관"
        "송파어린이도서관" -> "엘스도서관"
        else -> libraryName
    }

    private fun cleanLibraryName(libraryName: String): String =
        libraryName.replace("송파", "").replace("도서관", "").replace("어린이영어", "영어")

    fun parseIndexContent(content: String): IndexInfo {
        val doc = Jsoup.parse(content)
        val barcodeInfo = doc.selectFirst("div.barcodeInfo")
            ?: throw AuthException(SplibErrorKind.LOGIN_FAILED, "index page missing user info")
        val name = barcodeInfo.childNodes()
            .filterIsInstance<TextNode>()
            .firstOrNull { it.text().isNotBlank() }
            ?.text()?.trim()
            ?: throw AuthException(SplibErrorKind.LOGIN_FAILED, "index page missing user name")

        val loanLink = doc.selectFirst("a[href='${SplibConfig.LOAN_PATH}']")
        val interlibraryLink = doc.selectFirst("a[href='${SplibConfig.INTERLIBRARY_PATH}']")
        if (loanLink == null || interlibraryLink == null) {
            throw AuthException(SplibErrorKind.SESSION_EXPIRED, "index page missing navigation links")
        }

        val numLoans = loanLink.selectFirst("span")?.text()?.trim()?.toIntOrNull() ?: 0
        val numInterlibrary = interlibraryLink.selectFirst("span")?.text()?.trim()?.toIntOrNull() ?: 0
        return IndexInfo(name, numLoans, numInterlibrary)
    }

    fun parseLoanStatus(content: String): List<LoanBook> {
        val doc = Jsoup.parse(content)
        val entries = mutableListOf<LoanBook>()

        val infoBoxes = doc.select("div.infoBox")
        val statusBoxes = doc.select("div.statusBox")

        val parsed = infoBoxes.mapNotNull { infoBox ->
            val titleDiv = infoBox.selectFirst("div.title") ?: return@mapNotNull null
            val infos = infoBox.select("div.info")
            if (infos.size < 2) return@mapNotNull null
            val libraryStrong = infos[0].selectFirst("span strong") ?: return@mapNotNull null
            val dateSpans = infos[1].select("span")
            if (dateSpans.size != 2) return@mapNotNull null
            Triple(
                // wholeText keeps inner whitespace as-is (matches BeautifulSoup get_text)
                titleDiv.wholeText().trim(),
                cleanLibraryName(libraryStrong.text().trim()),
                Pair(
                    dateSpans[0].text().trim().replace("대출일 : ", ""),
                    dateSpans[1].text().trim().replace("반납예정일 : ", "").replace("반납일 : ", ""),
                ),
            )
        }

        parsed.forEachIndexed { i, (title, library, dates) ->
            val isBooksole = statusBoxes.getOrNull(i)?.text()?.contains("책솔이") ?: false
            entries.add(LoanBook(title, library, dates.first, dates.second, isBooksole))
        }
        return entries
    }

    fun parseDooraeStatus(content: String): Pair<Map<String, DooraeBook>, Int> {
        val doc = Jsoup.parse(content)
        val titles = mutableListOf<String>()
        val receivingLibraries = mutableListOf<String?>()
        val statuses = mutableListOf<String>()
        var returningCount = 0

        for (infoBox in doc.select("div.infoBox")) {
            infoBox.selectFirst("div.title")?.let { titles.add(it.wholeText().trim()) }

            val receiving = infoBox.select("div.info span")
                .firstOrNull { it.text().contains("수령도서관 :") }
                ?.text()?.substringAfterLast(':')?.trim()
            receivingLibraries.add(receiving?.let { convertLibraryNameToAbbreviations(it) })
        }

        for (statusBox in doc.select("div.statusBox")) {
            var statusText = statusBox.text().replace(" ", "")
            if (statusText == DooraeStatus.REQUESTED_RAW) statusText = DooraeStatus.REQUESTED
            statuses.add(statusText)
            if (statusText.contains(DooraeStatus.RETURNING)) returningCount++
        }

        val books = linkedMapOf<String, DooraeBook>()
        titles.reversed().zip(statuses.reversed()).zip(receivingLibraries.reversed()).forEach { (pair, library) ->
            books[pair.first] = DooraeBook(pair.second, library)
        }

        val filtered = books.filterValues {
            it.status in listOf(DooraeStatus.SENDING, DooraeStatus.OBTAINED, DooraeStatus.REQUESTED)
        }
        return Pair(filtered, returningCount)
    }
}
