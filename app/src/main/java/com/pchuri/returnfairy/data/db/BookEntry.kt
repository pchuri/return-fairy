package com.pchuri.returnfairy.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class BookSource { MANUAL, SPLIB }

/** 책솔이(상호대차) 대기 상태 — 아직 대출 전인 항목 */
enum class PickupStatus { REQUESTED, SENDING, OBTAINED }

@Entity(tableName = "books")
data class BookEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val borrower: String = "",       // 빌린 사람 태그 (가족 이름 등)
    val library: String = "",        // 도서관/수령도서관 (선택)
    val loanDate: LocalDate,
    val dueDate: LocalDate? = null,  // null이면 책솔이 대기 항목 (pickupStatus 참조)
    val returnedAt: LocalDate? = null,
    val source: BookSource = BookSource.MANUAL,
    val memo: String = "",
    val isInterlibrary: Boolean = false,
    val pickupStatus: PickupStatus? = null,
)
