package com.tazawa.snapcal.processing

import com.tazawa.snapcal.domain.NormalizedEvent
import java.util.UUID

/**
 * Ephemeral, process-local review state. No OCR, screenshots, candidate titles
 * or image URIs are persisted to disk, logs, notifications or Intent extras.
 * An expired/missing session requires a fresh screenshot.
 */
internal object ReviewSessionStore {
    private const val LIFETIME_MILLIS = 10 * 60 * 1_000L
    private const val MAX_SESSIONS = 5
    private val sessions = linkedMapOf<String, ReviewSession>()

    @Synchronized
    fun put(events: List<NormalizedEvent>, now: Long = System.currentTimeMillis()): String? {
        if (events.isEmpty()) return null
        prune(now)
        val id = UUID.randomUUID().toString()
        sessions[id] = ReviewSession(id, events.toList().take(4), now + LIFETIME_MILLIS)
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        return id
    }

    @Synchronized
    fun get(id: String, now: Long = System.currentTimeMillis()): List<NormalizedEvent>? {
        prune(now)
        return sessions[id]?.events?.toList()
    }

    @Synchronized
    fun consume(id: String, index: Int, now: Long = System.currentTimeMillis()): NormalizedEvent? {
        prune(now)
        val session = sessions[id] ?: return null
        val event = session.events.getOrNull(index) ?: return null
        // Exactly one write attempt per review session prevents duplicate taps.
        sessions.remove(id)
        return event
    }

    @Synchronized
    fun discard(id: String) { sessions.remove(id) }

    private fun prune(now: Long) {
        sessions.entries.removeAll { it.value.expiresAt <= now }
    }

    private data class ReviewSession(
        val id: String,
        val events: List<NormalizedEvent>,
        val expiresAt: Long,
    )
}
