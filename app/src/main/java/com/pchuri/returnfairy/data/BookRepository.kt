package com.pchuri.returnfairy.data

import com.pchuri.returnfairy.core.DooraeStatus
import com.pchuri.returnfairy.core.SplibClient
import com.pchuri.returnfairy.core.SplibErrorKind
import com.pchuri.returnfairy.data.db.BookDao
import com.pchuri.returnfairy.data.db.BookEntry
import com.pchuri.returnfairy.data.db.BookSource
import com.pchuri.returnfairy.data.db.PickupStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class SyncResult(
    val imported: Int,
    val updated: Int,
    val markedReturned: Int,
    val errors: List<Pair<String, SplibErrorKind>>, // account userId → error
)

class BookRepository(
    private val dao: BookDao,
    private val client: SplibClient = SplibClient(),
) {
    val activeBooks: Flow<List<BookEntry>> = dao.activeBooks()
    val returnedBooks: Flow<List<BookEntry>> = dao.returnedBooks()

    suspend fun add(book: BookEntry) = dao.insert(book)

    suspend fun update(book: BookEntry) = dao.update(book)

    suspend fun delete(book: BookEntry) = dao.delete(book)

    suspend fun markReturned(book: BookEntry) =
        dao.update(book.copy(returnedAt = LocalDate.now()))

    suspend fun unmarkReturned(book: BookEntry) =
        dao.update(book.copy(returnedAt = null))

    suspend fun extend(book: BookEntry, days: Long = 7) {
        val due = book.dueDate ?: return
        dao.update(book.copy(dueDate = due.plusDays(days)))
    }

    suspend fun dueSoon(until: LocalDate): List<BookEntry> = dao.dueSoon(until)

    /**
     * Sync loans and interlibrary (책솔이) requests from the Songpa library
     * accounts. Matches existing SPLIB entries by (title, borrower): updates
     * due dates and pickup statuses, inserts new items, and marks vanished
     * loans returned (vanished pickup requests are deleted — they were never
     * borrowed). Manual entries are never touched. Accounts that fail are
     * reported without aborting the others.
     */
    suspend fun syncFromSplib(credentials: List<Pair<String, String>>): SyncResult {
        val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        val infos = client.getInfos(credentials)

        val errors = credentials.zip(infos)
            .mapNotNull { (cred, info) -> info.error?.let { cred.first to it } }
        val okInfos = infos.filter { it.error == null }

        // Include returned entries: a book the user returned early in the app
        // is still listed on the site for a while, and must not be re-imported.
        val all = dao.allBySource(BookSource.SPLIB)
        val existingByKey = all.associateBy { entryKey(it) }
        val existing = all.filter { it.returnedAt == null }

        var imported = 0
        var updated = 0
        val seenKeys = mutableSetOf<Triple<String, String, LocalDate?>>()

        for (info in okInfos) {
            for (book in info.books) {
                val dueDate = runCatching { LocalDate.parse(book.dueDate, dateFormat) }.getOrNull()
                val pickupStatus = if (dueDate == null) parsePickupStatus(book.dueDate) else null
                if (dueDate == null && pickupStatus == null) continue // 알 수 없는 상태 (복귀중 등)
                val loanDate = runCatching { LocalDate.parse(book.loanDate, dateFormat) }.getOrNull()
                // Loan date is part of the key so re-borrowing the same title
                // creates a new entry instead of reviving the old one.
                val key = Triple(book.title, info.name, if (pickupStatus != null) null else loanDate)
                seenKeys.add(key)

                val match = existingByKey[key]
                if (match?.returnedAt != null) continue // 앱에서 이미 반납 처리함

                if (match == null) {
                    dao.insert(
                        BookEntry(
                            title = book.title,
                            borrower = info.name,
                            library = book.library,
                            loanDate = loanDate ?: LocalDate.now(),
                            dueDate = dueDate,
                            source = BookSource.SPLIB,
                            isInterlibrary = book.isInterlibrary,
                            pickupStatus = pickupStatus,
                        )
                    )
                    imported++
                } else if (match.dueDate != dueDate || match.library != book.library ||
                    match.pickupStatus != pickupStatus || match.isInterlibrary != book.isInterlibrary
                ) {
                    dao.update(
                        match.copy(
                            dueDate = dueDate,
                            library = book.library,
                            isInterlibrary = book.isInterlibrary,
                            pickupStatus = pickupStatus,
                        )
                    )
                    updated++
                }
            }
        }

        // Entries that vanished from the site — but only for accounts that
        // synced successfully. Loans were returned; pickup requests that
        // vanished were cancelled or converted, so just remove them.
        val okNames = okInfos.map { it.name }.toSet()
        var markedReturned = 0
        for (entry in existing) {
            if (entry.borrower in okNames && entryKey(entry) !in seenKeys) {
                if (entry.pickupStatus != null) {
                    dao.delete(entry)
                } else {
                    dao.update(entry.copy(returnedAt = LocalDate.now()))
                    markedReturned++
                }
            }
        }

        return SyncResult(imported, updated, markedReturned, errors)
    }

    private fun entryKey(entry: BookEntry) = Triple(
        entry.title,
        entry.borrower,
        if (entry.pickupStatus != null) null else entry.loanDate,
    )

    private fun parsePickupStatus(status: String): PickupStatus? = when (status) {
        DooraeStatus.REQUESTED -> PickupStatus.REQUESTED
        DooraeStatus.SENDING -> PickupStatus.SENDING
        DooraeStatus.OBTAINED -> PickupStatus.OBTAINED
        else -> null
    }
}
