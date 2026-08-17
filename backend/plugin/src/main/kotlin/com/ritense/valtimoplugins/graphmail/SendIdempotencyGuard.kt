package com.ritense.valtimoplugins.graphmail

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Default capacity before stale entries get swept. Sized generously above realistic concurrent
// in-flight-retry counts — see evictStaleIfNeeded.
private const val DEFAULT_MAX_ENTRIES = 1_000

// How long a "sent" marker is kept. Must comfortably exceed the time between a job-executor
// retry attempt and the original attempt that succeeded — MAX_DRAFT_SEND_WALL_CLOCK_MS (120s)
// plus normal Operaton retry backoff is well under this.
private const val DEFAULT_ENTRY_TTL_MS = 30L * 60L * 1000L // 30 minutes

/**
 * Guards [GraphMailPlugin.sendEmail] against duplicate sends when Operaton retries a
 * SERVICE_TASK_START activity after its surrounding transaction rolls back for a reason
 * unrelated to the email itself (e.g. an optimistic lock on other process data written in the
 * same transaction) — the Graph API call already succeeded and cannot be undone, but the retry
 * re-executes the same activity from scratch.
 *
 * A process variable is NOT a reliable guard here: it is written inside the same transaction
 * that rolls back, so it rolls back together with the retry and is never actually a signal the
 * next attempt can see. This guard is intentionally in-memory and non-transactional — that is
 * exactly what makes it survive a transaction rollback that a process variable cannot.
 *
 * Scope and limitation: this only protects against a retry handled by the *same, still-running*
 * JVM instance — the realistic failure mode (an optimistic-lock retry happens within the same
 * request/thread-pool cycle, typically milliseconds to seconds later). It does NOT survive an
 * application restart between the original send and a later retry; a crash-and-restart recovery
 * guarantee would require a durable, transactionally-independent store (e.g. a dedicated table
 * committed in its own transaction), which is a larger change than this in-memory guard.
 *
 * Registered as a single Spring bean (see [GraphMailAutoConfiguration]), for the same reason
 * [GraphTokenCache] is: Valtimo hydrates a fresh [GraphMailPlugin] per plugin action invocation,
 * so a guard owned by the plugin instance would never see the earlier attempt's marker.
 */
class SendIdempotencyGuard(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS,
) {
    private val sentAt = ConcurrentHashMap<String, Instant>()

    /** True if [markSent] was already called for [key] within the last [entryTtlMs]. */
    fun alreadySent(key: String): Boolean {
        val at = sentAt[key] ?: return false
        return Instant.now().isBefore(at.plusMillis(entryTtlMs))
    }

    /** Records that the send identified by [key] has completed successfully. */
    fun markSent(key: String) {
        evictStaleIfNeeded()
        sentAt[key] = Instant.now()
    }

    // sentAt only grows (one entry per distinct execution+activity that has ever sent). Once it
    // gets large, sweep out entries already past their TTL — mirrors the eviction pattern in
    // GraphTokenCache / GraphMailTestSendController's rate-limit store.
    private fun evictStaleIfNeeded() {
        if (sentAt.size < maxEntries) return
        val now = Instant.now()
        sentAt.entries.removeIf { now.isAfter(it.value.plusMillis(entryTtlMs)) }
    }
}
