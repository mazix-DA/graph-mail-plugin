package com.ritense.valtimoplugins.graphmail

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Default token cache capacity — override via constructor parameter.
// Increase if the deployment manages more than 64 distinct Entra app registrations.
private const val DEFAULT_MAX_CACHED_TOKENS = 64

/**
 * Thread-safe OAuth2 access-token cache keyed by "tenantId:clientId".
 *
 * This is registered as a single Spring bean (see [GraphMailAutoConfiguration]) and shared
 * between the [GraphMailClient] used by the plugin action path and the one used by the
 * test-send endpoint. Valtimo hydrates a fresh [GraphMailPlugin] — and therefore, previously,
 * a fresh [GraphMailClientImpl] with its own private cache — per plugin action invocation.
 * A cache that lives inside the client instance never accumulates hits under that lifecycle;
 * pulling it out into a shared bean is what makes caching actually effective.
 *
 * - Cache hits never block (plain map read).
 * - Concurrent misses for the same key collapse into a single fetch via a per-key lock.
 * - Lock eviction only removes a lock that is provably free right now (`tryLock` succeeds),
 *   never merely "not yet in the token cache" — a key mid-fetch is legitimately lock-held
 *   but not yet cached, and evicting it would hand a second thread a *different* Lock
 *   instance for the same key, defeating the mutual exclusion this class exists to provide.
 */
class GraphTokenCache(private val maxCachedTokens: Int = DEFAULT_MAX_CACHED_TOKENS) {

    private data class CachedToken(val token: String, val expiresAt: Instant, val createdAt: Instant)

    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun freshToken(key: String): String? =
        tokens[key]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.token

    /**
     * Returns the cached token for [key] if still valid, otherwise calls [fetch] to obtain a
     * new (token, expiresAt) pair, caches it, and returns the token. Concurrent callers for
     * the same [key] serialise on a per-key lock so only one of them actually calls [fetch].
     */
    fun getOrFetch(key: String, fetch: () -> Pair<String, Instant>): String {
        freshToken(key)?.let { return it }
        return lockFor(key).withLock {
            freshToken(key)?.let { return@withLock it }
            val (token, expiresAt) = fetch()
            evictIfFull()
            tokens[key] = CachedToken(token, expiresAt, Instant.now())
            token
        }
    }

    fun invalidate(key: String): Boolean = tokens.remove(key) != null

    /** Returns the number of entries that were cleared. */
    fun invalidateAll(): Int {
        val count = tokens.size
        tokens.clear()
        return count
    }

    private fun lockFor(key: String): ReentrantLock =
        locks.computeIfAbsent(key) { ReentrantLock() }.also { evictStaleLocksIfNeeded(key) }

    private fun evictStaleLocksIfNeeded(currentKey: String) {
        if (locks.size <= maxCachedTokens + maxCachedTokens / 2) return
        locks.keys
            .filter { it != currentKey && !tokens.containsKey(it) }
            .toList()
            .forEach { k ->
                val lock = locks[k] ?: return@forEach
                if (lock.tryLock()) {
                    try {
                        if (!tokens.containsKey(k)) locks.remove(k)
                    } finally {
                        lock.unlock()
                    }
                }
            }
    }

    private fun evictIfFull() {
        if (tokens.size < maxCachedTokens) return
        // Evict the oldest entry by createdAt — bounded scan, only runs at capacity.
        tokens.entries.minByOrNull { it.value.createdAt }?.key?.let { tokens.remove(it) }
    }
}
