package com.pchuri.returnfairy.data

import android.content.Context
import android.util.AtomicFile
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
class SnapshotStore internal constructor(
    context: Context,
    private val currentAccounts: () -> List<Account> = { AccountStore(context).load() },
) {
    private val cacheFile = File(context.filesDir, "last_snapshot.json")
    private val file = AtomicFile(cacheFile)

    // Shared across short-lived Worker store instances. If disk is full, an accepted attempt
    // must still fence older in-flight completions for the lifetime of this process.
    private fun loadCached(): Snapshot? = memory[cacheFile.absolutePath] ?: loadRaw()

    private fun loadRaw(): Snapshot? =
        runCatching { SnapshotJson.decode(String(file.readFully(), Charsets.UTF_8)) }.getOrNull()

    fun load(): Snapshot? = synchronized(LookupCoordination.lock) {
        val revisions = currentAccounts().associate { it.userId to it.revision }
        loadCached()?.let { cache ->
            cache.copy(accounts = cache.accounts.filter { revisions[it.userId] == it.accountRevision })
                .takeIf { it.accounts.isNotEmpty() }
        }
    }

    /** The app and the daily worker both write here; the lock keeps one write at a time. */
    private fun save(snapshot: Snapshot): Unit = synchronized(LookupCoordination.lock) {
        memory[cacheFile.absolutePath] = snapshot
        // A cache that cannot be written (disk full) is not worth crashing over.
        val out = runCatching { file.startWrite() }.getOrNull() ?: return
        try {
            out.write(SnapshotJson.encode(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    /** Session is mandatory: callers cannot bypass account-revision validation.
     * Attempt sequence is authoritative for all outcomes, ties keep the first accepted result.
     * A success also cannot move the last-success clock backwards. New transient failures retain
     * successful data; new permanent failures clear it. Older outcomes never replace newer ones.
     * [onAccepted] runs under the mutation lock, closing the validation-to-display/notify gap.
     */
    fun mergeAndSave(
        fresh: Snapshot,
        session: LookupSession,
        inMemory: Snapshot? = null,
        onAccepted: (shown: Snapshot, accepted: Snapshot) -> Unit = { _, _ -> },
    ): Snapshot = synchronized(LookupCoordination.lock) {
        val current = currentAccounts().associateBy { it.userId }
        val captured = session.accounts.associateBy { it.userId }
        val disk = load()
        val candidates = listOfNotNull(disk, inMemory).flatMap { cache ->
            cache.accounts.filter { current[it.userId]?.revision == it.accountRevision }
                .map { it.copy(lastSuccessfulAt = it.lastSuccessfulAt ?: cache.fetchedAt.takeIf { _ -> it.error == null }) }
        }
        val latest = candidates.groupBy { it.userId }.mapValues { (_, values) ->
            values.maxWith(compareBy<AccountStatus> { it.requestSequence }.thenBy { it.lastSuccessfulAt })
        }.toMutableMap()
        val accepted = fresh.accounts.distinctBy { it.userId }.mapNotNull { result ->
            val account = current[result.userId] ?: return@mapNotNull null
            if (captured[result.userId]?.revision != account.revision) return@mapNotNull null
            val previous = latest[result.userId]
            if (previous != null && (session.sequence <= previous.requestSequence ||
                (result.error == null && previous.lastSuccessfulAt?.isAfter(fresh.fetchedAt) == true)))
                return@mapNotNull null
            val incoming = result.copy(accountRevision = account.revision, requestSequence = session.sequence)
            val merged = Snapshot(fresh.fetchedAt, listOf(incoming)).withCachedFallback(
                previous?.let { Snapshot(fresh.fetchedAt, listOf(it)) },
            ).accounts.single().copy(accountRevision = account.revision, requestSequence = session.sequence)
            latest[result.userId] = merged
            incoming
        }
        val shown = Snapshot(
            maxOf(fresh.fetchedAt, disk?.fetchedAt ?: fresh.fetchedAt),
            current.keys.mapNotNull { latest[it] },
        )
        if (current.isEmpty()) clear() else save(shown)
        onAccepted(shown, Snapshot(fresh.fetchedAt, accepted))
        shown
    }

    internal fun retainAccounts(accounts: List<Account>) = synchronized(LookupCoordination.lock) {
        val revisions = accounts.associate { it.userId to it.revision }
        val cache = loadCached() ?: return@synchronized
        val retained = cache.copy(accounts = cache.accounts.filter { revisions[it.userId] == it.accountRevision })
        if (retained.accounts.isEmpty()) clear() else save(retained)
    }

    fun clear() = synchronized(LookupCoordination.lock) {
        memory.remove(cacheFile.absolutePath)
        file.delete()
    }

    private companion object { val memory = mutableMapOf<String, Snapshot>() }

}

object SnapshotJson {
    fun encode(snapshot: Snapshot): String = JSONObject()
        .put("fetchedAt", snapshot.fetchedAt.toString())
        .put("accounts", JSONArray().apply { snapshot.accounts.forEach { put(account(it)) } })
        .toString()

    private fun account(a: AccountStatus) = JSONObject()
        .put("label", a.label)
        .put("userId", a.userId)
        .put("error", a.error?.name ?: JSONObject.NULL)
        .put("lastSuccessfulAt", a.lastSuccessfulAt?.toString() ?: JSONObject.NULL)
        .put("accountRevision", a.accountRevision)
        .put("requestSequence", a.requestSequence)
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
                    accountRevision = a.optString("accountRevision"),
                    requestSequence = a.optLong("requestSequence", 0),
                    userId = a.optString("userId"),
                    error = a.optStringOrNull("error")?.let { SplibErrorKind.valueOf(it) },
                    lastSuccessfulAt = a.optStringOrNull("lastSuccessfulAt")?.let(LocalDateTime::parse),
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
