package com.pchuri.returnfairy.data

import com.pchuri.returnfairy.data.db.BookDao
import com.pchuri.returnfairy.data.db.BookEntry
import com.pchuri.returnfairy.data.db.BookSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/** In-memory BookDao for unit tests. */
class FakeBookDao : BookDao {
    val books = mutableListOf<BookEntry>()
    private var nextId = 1L

    override fun activeBooks(): Flow<List<BookEntry>> = flowOf(books.filter { it.returnedAt == null })
    override fun returnedBooks(): Flow<List<BookEntry>> = flowOf(books.filter { it.returnedAt != null })
    override suspend fun dueSoon(until: LocalDate): List<BookEntry> =
        books.filter { it.returnedAt == null && it.dueDate != null && !it.dueDate!!.isAfter(until) }

    override suspend fun activeBySource(source: BookSource): List<BookEntry> =
        books.filter { it.source == source && it.returnedAt == null }

    override suspend fun allBySource(source: BookSource): List<BookEntry> =
        books.filter { it.source == source }

    override suspend fun insert(book: BookEntry): Long {
        val entry = book.copy(id = nextId++)
        books.add(entry)
        return entry.id
    }

    override suspend fun insertAll(books: List<BookEntry>) = books.forEach { insert(it) }
    override suspend fun update(book: BookEntry) {
        books.replaceAll { if (it.id == book.id) book else it }
    }

    override suspend fun delete(book: BookEntry) {
        books.removeAll { it.id == book.id }
    }
}

