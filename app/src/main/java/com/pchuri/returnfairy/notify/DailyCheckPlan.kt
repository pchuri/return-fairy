package com.pchuri.returnfairy.notify

import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.Snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DEADLINE = DateTimeFormatter.ofPattern("MM.dd")
internal const val DAILY_CHECK_BACKOFF_MINUTES = 15L
internal const val MAX_DAILY_CHECK_RETRIES = 3

/** One initial attempt and up to three retries, after 15, 30 and 60 minutes. */
internal data class DailyCheckPlan(val retry: Boolean, val digest: DailyDigest)

internal fun planDailyCheck(snapshot: Snapshot, today: LocalDate, runAttemptCount: Int): DailyCheckPlan {
    val retry = snapshot.accounts.any { it.isConnectionFailure } && runAttemptCount < MAX_DAILY_CHECK_RETRIES
    // Defer the digest until the terminal attempt, avoiding duplicate alerts for healthy accounts
    // while another account is retried. Permanent errors do not cause retries.
    val digest = if (retry) DailyDigest(emptyList(), emptyList(), emptyList(), emptyList())
        else buildDailyDigest(snapshot, today)
    return DailyCheckPlan(retry, digest)
}

/** What deserves a nudge today. Kept free of Android types so it can be unit-tested. */
data class DailyDigest(
    val overdue: List<String>,
    val dueToday: List<String>,
    val dueTomorrow: List<String>,
    /** Interlibrary arrivals and arrived reservations: "label · title (library)". */
    val pickups: List<String>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && dueToday.isEmpty() && dueTomorrow.isEmpty() && pickups.isEmpty()
}

fun buildDailyDigest(snapshot: Snapshot, today: LocalDate): DailyDigest {
    val overdue = mutableListOf<String>()
    val dueToday = mutableListOf<String>()
    val dueTomorrow = mutableListOf<String>()
    val pickups = mutableListOf<String>()
    for (account in snapshot.accounts) {
        // A cached book may already have been returned; never remind from a failed lookup.
        if (account.error != null) continue
        for (book in account.sortedBooks(today)) {
            when (book.status) {
                BookStatus.READY_FOR_PICKUP -> pickups += "${account.label} · ${book.title} (${book.library})"
                BookStatus.LOANED -> when (val left = book.daysLeft(today)) {
                    null -> Unit
                    else -> when {
                        left < 0 -> overdue += "${account.label} · ${book.title}"
                        left == 0L -> dueToday += "${account.label} · ${book.title}"
                        left == 1L -> dueTomorrow += "${account.label} · ${book.title}"
                    }
                }
                BookStatus.IN_TRANSIT -> Unit
            }
        }
        for (reservation in account.reservations) {
            val deadline = reservation.pickupDeadline ?: continue
            pickups += "${account.label} · ${reservation.title} (${reservation.library}, ~${deadline.format(DEADLINE)})"
        }
    }
    return DailyDigest(overdue, dueToday, dueTomorrow, pickups)
}
