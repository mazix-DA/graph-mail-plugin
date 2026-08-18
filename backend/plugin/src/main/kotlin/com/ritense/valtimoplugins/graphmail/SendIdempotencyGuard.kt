package com.ritense.valtimoplugins.graphmail

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

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
 * [ifNotAlreadySent] serialises same-key callers on a per-key lock, so [action] is only ever
 * running for a given key on one thread at a time — a genuine concurrent retry (e.g. an
 * automatic job-executor retry racing a manual retrigger from an admin console) cannot slip two
 * sends past a plain check-then-act. [action] is only marked sent once it returns normally, so a
 * failed attempt never blocks a legitimate next retry from actually sending.
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
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // Size of `locks` at the last eviction scan that found nothing to remove. Guards against
    // rescanning the whole (oversized) map on every single call once it's full of keys that are
    // all still legitimately in use — see evictStaleLocksIfNeeded.
    private val locksSizeAtLastEviction = AtomicInteger(0)

    /**
     * Cheap, non-locking check for whether [key] was already sent. Safe to use as an early exit
     * before doing expensive work (resolving attachments, sanitising HTML) that would otherwise
     * be thrown away on a detected retry — but NOT sufficient on its own to decide whether to
     * actually send: use [ifNotAlreadySent] for that, which is the atomic, authoritative check.
     */
    fun alreadySent(key: String): Boolean = isSent(key)

    /**
     * Runs [action] for [key] unless it was already sent within the last [entryTtlMs], in which
     * case [action] is skipped entirely and this returns null. See the class doc for why this is
     * safe under both a sequential retry and a genuine concurrent race on the same key.
     */
    fun <T> ifNotAlreadySent(
        key: String,
        action: () -> T,
    ): T? {
        val lock = lockFor(key)
        try {
            if (isSent(key)) return null
            val result = action()
            markSent(key)
            return result
        } finally {
            lock.unlock()
            evictStaleLocksIfNeeded(key)
        }
    }

    private fun isSent(key: String): Boolean {
        val at = sentAt[key] ?: return false
        return Instant.now().isBefore(at.plusMillis(entryTtlMs))
    }

    private fun markSent(key: String) {
        evictStaleIfNeeded()
        sentAt[key] = Instant.now()
    }

    // Returns a Lock for `key`, held (locked) by the caller on return — the caller must unlock
    // it. Acquires and validates in a loop rather than a single computeIfAbsent+lock: a lock is
    // only ever evicted while free (tryLock succeeds in evictStaleLocksIfNeeded), so there is a
    // narrow window between us reading the map's current instance and actually locking it where
    // a concurrent eviction (triggered by a *different* key's call) can slip in, remove it, and
    // leave us holding an orphaned Lock that a later caller for the same key will never see —
    // two callers would then hold two different Lock objects for one key, and the exclusion this
    // class exists to provide is gone. Re-checking identity against the live map entry after
    // locking closes that window: if it no longer matches, our lock was orphaned mid-acquisition,
    // so we drop it and retry against whatever is current.
    private fun lockFor(key: String): ReentrantLock {
        while (true) {
            val lock = locks.computeIfAbsent(key) { ReentrantLock() }
            lock.lock()
            if (locks[key] === lock) return lock
            lock.unlock()
        }
    }

    // Only removes a lock that is provably free right now (tryLock succeeds) and not backing a
    // currently-sent key — a key whose action is still running is legitimately lock-held but not
    // yet marked sent, and tryLock() correctly fails for it, so it's never a candidate.
    //
    // Bounded: once a scan at a given map size finds nothing evictable, further calls are a
    // no-op until the map actually grows past that size again — without this, a map that's full
    // of keys all still legitimately in use (e.g. many concurrent in-flight sends) would pay for
    // a full linear rescan on every single call, with a guaranteed empty result each time.
    private fun evictStaleLocksIfNeeded(currentKey: String) {
        val size = locks.size
        if (size <= maxEntries + maxEntries / 2) return
        if (size <= locksSizeAtLastEviction.get()) return
        locksSizeAtLastEviction.set(size)
        locks.keys
            .filter { it != currentKey && !sentAt.containsKey(it) }
            .toList()
            .forEach { k ->
                val lock = locks[k] ?: return@forEach
                if (lock.tryLock()) {
                    try {
                        if (!sentAt.containsKey(k)) locks.remove(k)
                    } finally {
                        lock.unlock()
                    }
                }
            }
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
