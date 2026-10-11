package com.pchuri.returnfairy.data

/** All account mutations, cache commits and immediate result side effects share this monitor.
 * The app and WorkManager run in the same process (no remote worker/service in the manifest).
 * Never hold it during a network call or suspend inside a result callback.
 */
internal object LookupCoordination { val lock = Any() }

/** Captured before network I/O. Revisions identify account incarnations, sequence orders attempts. */
data class LookupSession internal constructor(val accounts: List<Account>, internal val sequence: Long)
