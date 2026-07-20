package com.tazawa.snapcal.calendar

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

object CalendarEventFactory {
    const val REMINDER_MINUTES = 30
    const val REMINDER_METHOD_DEFAULT = 0
    const val REMINDER_METHOD_ALERT = 1
    private const val TOKEN_PREFIX = "SnapCal-ID: "
    private val validToken = Regex("[A-Za-z0-9-]{16,128}")

    fun createPayload(
        calendarId: Long,
        draft: CalendarEventDraft,
        operationToken: String,
        reminderMethod: Int = REMINDER_METHOD_ALERT,
    ): CalendarEventPayload {
        validateOperationToken(operationToken)
        require(
            reminderMethod == REMINDER_METHOD_ALERT ||
                reminderMethod == REMINDER_METHOD_DEFAULT,
        ) { "unsupported reminder method" }
        return CalendarEventPayload(
            calendarId = calendarId,
            title = draft.title,
            startMillis = draft.startInstant.toEpochMilli(),
            endMillis = draft.endInstant.toEpochMilli(),
            timeZoneId = draft.timeZoneId,
            description = appendOperationToken(draft.description, operationToken),
            location = draft.location,
            reminderMinutes = REMINDER_MINUTES,
            reminderMethod = reminderMethod,
        )
    }

    fun appendOperationToken(description: String?, operationToken: String): String {
        validateOperationToken(operationToken)
        val marker = tokenMarker(operationToken)
        val body = description?.trimEnd().orEmpty()
        return if (body.isEmpty()) marker else "$body\n\n$marker"
    }

    fun hasOperationTokenAtEnd(description: String?, operationToken: String): Boolean {
        validateOperationToken(operationToken)
        return description?.endsWith(tokenMarker(operationToken)) == true
    }

    fun tokenMarker(operationToken: String): String {
        validateOperationToken(operationToken)
        return TOKEN_PREFIX + operationToken
    }

    fun signature(title: String, startInstant: Instant, calendarId: Long): String {
        val canonical = title + startInstant.toString() + calendarId.toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun classifyMatches(
        matches: List<EventFingerprint>,
        expectedSignature: String,
    ): MatchClassification = when (matches.size) {
        0 -> MatchClassification.NoMatch
        1 -> {
            val match = matches.single()
            if (match.signature == expectedSignature) {
                MatchClassification.ExactMatch(match.eventId)
            } else {
                MatchClassification.SignatureMismatch
            }
        }
        else -> MatchClassification.Ambiguous(matches.size)
    }

    fun parseAllowedReminderMethods(rawValue: String?): Set<Int> = rawValue
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.toSet()
        .orEmpty()

    fun selectReminderMethod(maxReminders: Int, allowedMethods: Set<Int>): Int? {
        if (maxReminders < 1) return null
        return when {
            REMINDER_METHOD_ALERT in allowedMethods -> REMINDER_METHOD_ALERT
            REMINDER_METHOD_DEFAULT in allowedMethods -> REMINDER_METHOD_DEFAULT
            else -> null
        }
    }

    fun validateOperationToken(operationToken: String) {
        require(validToken.matches(operationToken)) {
            "operationToken must contain 16-128 ASCII letters, digits, or hyphens"
        }
    }
}
