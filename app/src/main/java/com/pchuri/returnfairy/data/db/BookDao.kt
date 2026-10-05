package com.pchuri.returnfairy.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE returnedAt IS NULL ORDER BY dueDate IS NULL ASC, dueDate ASC, title ASC")
    fun activeBooks(): Flow<List<BookEntry>>

    @Query("SELECT * FROM books WHERE returnedAt IS NOT NULL ORDER BY returnedAt DESC, title ASC")
    fun returnedBooks(): Flow<List<BookEntry>>

    @Query("SELECT * FROM books WHERE returnedAt IS NULL AND dueDate IS NOT NULL AND dueDate <= :until ORDER BY dueDate ASC")
    suspend fun dueSoon(until: LocalDate): List<BookEntry>

    @Query("SELECT * FROM books WHERE source = :source AND returnedAt IS NULL")
    suspend fun activeBySource(source: BookSource): List<BookEntry>

    /** Includes returned entries — needed so a book returned early in the app
     *  is not re-imported while the library site still lists it. */
    @Query("SELECT * FROM books WHERE source = :source")
    suspend fun allBySource(source: BookSource): List<BookEntry>

    @Insert
    suspend fun insert(book: BookEntry): Long

    @Insert
    suspend fun insertAll(books: List<BookEntry>)

    @Update
    suspend fun update(book: BookEntry)

    @Delete
    suspend fun delete(book: BookEntry)
}
