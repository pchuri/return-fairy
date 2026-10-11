# Lookup consistency

Account editing and foreground/background lookups share a process-wide monitor. The Android
manifest runs the UI and WorkManager in one process; adding a remote worker would require
cross-process coordination instead.

Before network I/O, `AccountStore.beginLookup()` captures persisted account revisions and a
persisted monotonically increasing request sequence. Editing the password or label, adding an
account, or deleting and re-adding it creates a new UUID revision. Unchanged accounts retain their
revision. Password encryption remains Android Keystore AES-GCM; revisions do not encode passwords.

`AccountStore.save()` updates account preferences and prunes changed/deleted cache entries under
that same monitor. `SnapshotStore.mergeAndSave()` requires the captured session and independently
validates each returned account. There is no public unguarded snapshot write API. Reloads filter
cache entries against current revisions too, so a failed cleanup write cannot expose removed data.

## Result ordering

- Higher request sequence wins independently per account, whether foreground or background.
- Equal sequences keep the first accepted result; equal clock times use sequence ordering.
- A successful result cannot replace retained successful data with an earlier success timestamp.
- A newer transient failure retains the previous successful books and success time, marks them
  stale, and advances the attempt sequence. A newer permanent failure clears the old data.
- An older success or failure cannot overwrite a newer accepted outcome. A new successful attempt
  can recover from an accepted failure. Accounts without an accepted newer result can still accept
  an earlier valid completion when another account in the same batch has already advanced.

Successful outcomes, failed outcomes, revisions and sequences round-trip through the cache JSON.
Legacy account/cache JSON without revision/sequence fields remains readable. A subsequent account
change replaces the legacy empty revision and invalidates those entries.

## Side effects and UI

The commit callback runs under the mutation monitor and receives two snapshots: the merged display
cache and only this lookup's newly accepted raw outcomes. Daily retry/reminder planning uses the
latter, so rejected or cached fallback results cannot generate reminders. Notification construction
and submission remain inside this callback, closing the account validation-to-notify gap.
The Worker rechecks coroutine cancellation after acquiring the monitor and before reminder planning;
a cancelled lookup can retain valid cache data while skipping reminders and terminal reanchoring. No network call or
suspension occurs while the monitor is held. An account edit that completes before the callback is
rejected; if a notification call wins the monitor first, that submission precedes the edit.

Dashboard completion also occurs under the monitor. The refresh sequence is part of the StateFlow
value and checked in its CAS update, preventing a cancelled older refresh from ending the spinner
or replacing the screen for a replacement refresh.

## Storage failure and validation limits

The process shares its latest accepted cache between store instances, retaining ordering even when
AtomicFile cannot write. Account preference saves and sequence allocation require successful
synchronous commits and fail fast if preferences cannot be persisted; a graceful storage-error UI
is outside this change. Cache writes remain best effort. If a cache write fails and the process dies,
its unsaved result cannot be recovered; only successfully persisted bytes survive restart.

`LookupCommitTest` uses production preferences, AtomicFile and commit/mutation paths with synthetic
accounts and an injected test-only password codec. Its 16 tests cover deletion, credential/label
changes, same-ID re-registration, mixed/inverted completions, ties, transient/permanent failures,
clock rollback, reload, legacy JSON, write failure, and actual monitor contention before a side
effect callback. `RefreshCompletionTest` adds two tests, including a forced StateFlow CAS collision.
The independent `DeepLookupValidationTest` additionally covers cold reload after clearing process
memory, stale disk bytes after account changes, simultaneous completions, cancelled UI completion,
callback failure/lock release, and the actual production Worker commit/notification-planning helper
including cancellation while blocked on the monitor. No credentials, library requests, real device
mutations or real notifications are used.

These tests do not prove real notification delivery, the actual selected-time device run, or the
full production WorkManager/network retry-to-terminal-to-next-day lifecycle. Existing scheduling,
retry/digest and real enqueue/database tests run with the complete unit suite. Independent review
covers the final source and test design; execution evidence is kept alongside the checkout.
