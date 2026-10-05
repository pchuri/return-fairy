package com.pchuri.returnfairy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun fromEpochDay(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun fromSourceName(value: String): BookSource = BookSource.valueOf(value)

    @TypeConverter
    fun toSourceName(source: BookSource): String = source.name

    @TypeConverter
    fun fromPickupName(value: String?): PickupStatus? = value?.let { PickupStatus.valueOf(it) }

    @TypeConverter
    fun toPickupName(status: PickupStatus?): String? = status?.name
}

/** v2: dueDate nullable + isInterlibrary/pickupStatus columns for 책솔이 items */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                borrower TEXT NOT NULL,
                library TEXT NOT NULL,
                loanDate INTEGER NOT NULL,
                dueDate INTEGER,
                returnedAt INTEGER,
                source TEXT NOT NULL,
                memo TEXT NOT NULL,
                isInterlibrary INTEGER NOT NULL,
                pickupStatus TEXT
            )"""
        )
        db.execSQL(
            """INSERT INTO books_new
               (id, title, borrower, library, loanDate, dueDate, returnedAt, source, memo, isInterlibrary)
               SELECT id, title, borrower, library, loanDate, dueDate, returnedAt, source, memo, 0
               FROM books"""
        )
        db.execSQL("DROP TABLE books")
        db.execSQL("ALTER TABLE books_new RENAME TO books")
    }
}

@Database(entities = [BookEntry::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "returnfairy.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
