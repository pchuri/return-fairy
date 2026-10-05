package com.pchuri.returnfairy.data

import android.content.Context
import com.pchuri.returnfairy.core.AccountStatus
import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.LibraryBook
import com.pchuri.returnfairy.core.Reservation
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibErrorKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The last lookup, kept in app-private storage so the dashboard opens instantly (and offline)
 * while a fresh lookup runs. Holds titles and dates, never credentials.
 */
class SnapshotStore(context: Context) {
    private val file = File(context.filesDir, "last_snapshot.json")

    fun load(): Snapshot? = runCatching { SnapshotJson.decode(file.readText()) }.getOrNull()

    fun save(snapshot: Snapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(SnapshotJson.encode(snapshot))
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}

object SnapshotJson {
    fun encode(snapshot: Snapshot): String = JSONObject()
        .put("fetchedAt", snapshot.fetchedAt.toString())
        .put("accounts", JSONArray().apply { snapshot.accounts.forEach { put(account(it)) } })
        .toString()

    private fun account(a: AccountStatus) = JSONObject()
        .put("label", a.label)
        .put("error", a.error?.name ?: JSONObject.NULL)
        .put("books", JSONArray().apply {
            a.books.forEach { b ->
                put(
                    JSONObject()
                        .put("title", b.title)
                        .put("status", b.status.name)
                        .put("dueDate", b.dueDate?.toString() ?: JSONObject.NULL)
                        .put("rawDue", b.rawDue)
                        .put("transitStatus", b.transitStatus ?: JSONObject.NULL)
                        .put("isInterlibrary", b.isInterlibrary)
                        .put("library", b.library)
                        .put("providingLibrary", b.providingLibrary)
                )
            }
        })
        .put("reservations", JSONArray().apply {
            a.reservations.forEach { r ->
                put(
                    JSONObject()
                        .put("title", r.title)
                        .put("library", r.library)
                        .put("rank", r.rank)
                        .put("waitingCount", r.waitingCount)
                        .put("pickupDeadline", r.pickupDeadline?.toString() ?: JSONObject.NULL)
                )
            }
        })

    fun decode(text: String): Snapshot {
        val root = JSONObject(text)
        val accounts = root.getJSONArray("accounts")
        return Snapshot(
            fetchedAt = LocalDateTime.parse(root.getString("fetchedAt")),
            accounts = (0 until accounts.length()).map { i ->
                val a = accounts.getJSONObject(i)
                val books = a.getJSONArray("books")
                val reservations = a.getJSONArray("reservations")
                AccountStatus(
                    label = a.getString("label"),
                    error = a.optStringOrNull("error")?.let { SplibErrorKind.valueOf(it) },
                    books = (0 until books.length()).map { j ->
                        val b = books.getJSONObject(j)
                        LibraryBook(
                            title = b.getString("title"),
                            status = BookStatus.valueOf(b.getString("status")),
                            dueDate = b.optStringOrNull("dueDate")?.let(LocalDate::parse),
                            rawDue = b.optString("rawDue"),
                            transitStatus = b.optStringOrNull("transitStatus"),
                            isInterlibrary = b.getBoolean("isInterlibrary"),
                            library = b.optString("library"),
                            providingLibrary = b.optString("providingLibrary"),
                        )
                    },
                    reservations = (0 until reservations.length()).map { j ->
                        val r = reservations.getJSONObject(j)
                        Reservation(
                            title = r.getString("title"),
                            library = r.optString("library"),
                            rank = r.optInt("rank"),
                            waitingCount = r.optInt("waitingCount"),
                            pickupDeadline = r.optStringOrNull("pickupDeadline")?.let(LocalDate::parse),
                        )
                    },
                )
            },
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
