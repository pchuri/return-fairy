package com.pchuri.returnfairy.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object SplibConfig {
    const val BASE = "https://splib.or.kr"
    const val LOGIN_URL = "$BASE/intro/program/memberLoginProc.do"
    const val INDEX_URL = "$BASE/intro/index.do"
    const val LOAN_URL = "$BASE/intro/program/mypage/loanStatusList.do"
    const val INTERLIBRARY_LOAN_URL = "$BASE/intro/program/mypage/dooraeLillStatusList.do"
    const val RESERVATION_URL = "$BASE/intro/program/mypage/reservationStatusList.do"
    const val LOAN_PATH = "/intro/program/mypage/loanStatusList.do"
    const val INTERLIBRARY_PATH = "/intro/program/mypage/dooraeLillStatusList.do"
}

/** Interlibrary (상호대차) statuses as printed on the site. */
object DooraeStatus {
    const val SENDING = "발송"
    const val OBTAINED = "입수"
    const val RETURNING = "복귀중"
    const val REQUESTED = "요청중"
    const val APPLIED = "신청중"

    /** Still in progress. 완료·복귀중·신청취소 are history. Same as songpa_core ACTIVE_DOORAE_STATUSES. */
    val ACTIVE = setOf(OBTAINED, SENDING, REQUESTED, APPLIED)
}

enum class BookStatus { READY_FOR_PICKUP, LOANED, IN_TRANSIT }

data class LibraryBook(
    val title: String,
    val status: BookStatus,
    /** Due date of a loan; null while in transit or when the site text could not be read. */
    val dueDate: LocalDate?,
    /** Site text of the due/status column, shown when [dueDate] is null on a loan. */
    val rawDue: String,
    /** Site status of an interlibrary request (입수·발송·요청중·신청중), else null. */
    val transitStatus: String?,
    val isInterlibrary: Boolean,
    /** Pickup/return library for interlibrary books, lending library otherwise. */
    val library: String,
    /** Library that sent an interlibrary book. */
    val providingLibrary: String,
) {
    fun daysLeft(today: LocalDate): Long? = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
}

data class Reservation(
    val title: String,
    val library: String,
    val rank: Int,
    val waitingCount: Int,
    /** Set when the book has arrived and waits to be picked up until this date. */
    val pickupDeadline: LocalDate?,
) {
    val readyForPickup: Boolean get() = pickupDeadline != null
}

data class AccountStatus(
    val label: String,
    val books: List<LibraryBook>,
    val reservations: List<Reservation>,
    val error: SplibErrorKind? = null,
    /** Library login ID, kept so a cached result can be matched to its account. Not displayed. */
    val userId: String = "",
    /** Time these books/reservations were last fetched successfully, retained across outages. */
    val lastSuccessfulAt: LocalDateTime? = null,
) {
    val interlibraryCount: Int get() = books.count { it.isInterlibrary }
    val isConnectionFailure: Boolean get() = error == SplibErrorKind.NETWORK || error == SplibErrorKind.TIMEOUT
    /** A failed connection with a retained successful result, including a successful empty list. */
    val isStale: Boolean get() = isConnectionFailure && lastSuccessfulAt != null
    val pickupBookCount: Int get() = books.count { it.status == BookStatus.READY_FOR_PICKUP }
    val readyReservationCount: Int get() = reservations.count { it.readyForPickup }

    /** Pickups first, then loans by due date (unreadable dates first), then books in transit. */
    fun sortedBooks(today: LocalDate): List<LibraryBook> = books.sortedWith(
        compareBy<LibraryBook> { it.status.ordinal }.thenBy { it.daysLeft(today) ?: Long.MIN_VALUE }
    )
}

data class Snapshot(val fetchedAt: LocalDateTime, val accounts: List<AccountStatus>) {
    /** Every account failed to reach the site (no network, site down), as opposed to a login problem. */
    fun isOffline(): Boolean = accounts.isNotEmpty() && accounts.all { it.isConnectionFailure }

    val hasStaleResults: Boolean get() = accounts.any { it.isStale }

    /**
     * Merge each current account independently. Only connection failures may reuse a successful
     * result; authentication/layout errors remain visible and are never cached as fresh data.
     * The snapshot time is the latest lookup attempt; each account keeps its own success time.
     * Iterating the new account list also drops removed accounts and uses current labels.
     */
    fun withCachedFallback(vararg cached: Snapshot?): Snapshot {
        val candidates = cached.filterNotNull().flatMap { snapshot ->
            snapshot.accounts.filter { it.userId.isNotEmpty() && (it.error == null || it.isStale) }
                .map { it.copy(lastSuccessfulAt = it.lastSuccessfulAt ?: snapshot.fetchedAt) }
        }.groupBy { it.userId }
        return copy(accounts = accounts.map { fresh ->
            when {
                fresh.error == null -> fresh.copy(lastSuccessfulAt = fetchedAt)
                fresh.isConnectionFailure -> {
                    val previous = candidates[fresh.userId]?.maxByOrNull { it.lastSuccessfulAt!! }
                    previous?.copy(label = fresh.label, error = fresh.error) ?: fresh
                }
                else -> fresh
            }
        })
    }
}

enum class SplibErrorKind {
    LOGIN_FAILED, SESSION_EXPIRED, NETWORK, TIMEOUT,
    /** The page loaded but could not be read: the site layout probably changed. Not "offline". */
    SITE_CHANGED,
}

class AuthException(val kind: SplibErrorKind, message: String) : Exception(message)
/** [siteChanged]: the server answered but not with a usable page (4xx, page without the footer marker). */
class FetchException(message: String, cause: Throwable? = null, val siteChanged: Boolean = false) : Exception(message, cause)
