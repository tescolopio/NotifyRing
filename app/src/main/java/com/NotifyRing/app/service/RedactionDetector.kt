package com.notifyring.app.service

import java.util.Locale

/**
 * Pure-Kotlin snapshot of extracted notification fields & visibility flags inspected for
 * Android 15 (API 35) OTP / sensitive-content redaction and lockscreen privacy redaction.
 */
data class RedactionCandidate(
    val appName: String,
    val visibility: Int = RedactionDetector.VISIBILITY_PRIVATE,
    val extractedTitle: String = "",
    val extractedText: String = "",
    val extractedMessages: List<String> = emptyList(),
    val publicVersionTitle: String? = null,
    val publicVersionText: String? = null
)

/**
 * Signal that triggered [RedactionDetector] so callers and tests can inspect exact cause.
 */
enum class RedactionReason {
    SYSTEM_OR_OEM_PLACEHOLDER,
    VISIBILITY_SECRET,
    PUBLIC_VERSION_PLACEHOLDER_MATCH
}

/**
 * Result of evaluating a [RedactionCandidate] for sensitive/redacted content.
 */
data class RedactionResult(
    val isRedacted: Boolean,
    val fallbackMessage: String? = null,
    val reason: RedactionReason? = null
)

/**
 * Pure-Kotlin multi-signal detector for Android 15 (API 35) automatic OTP / sensitive
 * notification redaction and private/secret visibility placeholders:
 * 1. Localized system string phrases resolved at runtime from Android framework resources
 *    (`redacted_notification_message`, `notification_hidden_text`) plus known AOSP/OEM fallbacks.
 * 2. `Notification.VISIBILITY_SECRET` (`-1`) flag.
 * 3. `Notification.VISIBILITY_PRIVATE` (`0`) where the extracted body was replaced by or stripped
 *    down to the `publicVersion` placeholder text.
 */
class RedactionDetector(
    systemRedactedPhrases: Set<String> = emptySet(),
    private val defaultAppLabel: String = DEFAULT_APP_LABEL,
    private val fallbackFormatter: (appName: String) -> String = { app ->
        "Private alert from $app. Open the app to view it."
    }
) {

    private val normalizedPlaceholderPhrases: Set<String> = buildSet {
        for (phrase in KNOWN_OEM_REDACTED_PHRASES) {
            val norm = normalizeForComparison(phrase)
            if (norm.isNotEmpty()) add(norm)
        }
        for (phrase in systemRedactedPhrases) {
            val norm = normalizeForComparison(phrase)
            if (norm.isNotEmpty()) add(norm)
        }
    }

    fun detect(candidate: RedactionCandidate): RedactionResult {
        val safeAppName = sanitizeAppName(candidate.appName)

        // Signal 1: Notification.VISIBILITY_SECRET always hides content unconditionally.
        if (candidate.visibility == VISIBILITY_SECRET) {
            return RedactionResult(
                isRedacted = true,
                fallbackMessage = fallbackFormatter(safeAppName),
                reason = RedactionReason.VISIBILITY_SECRET
            )
        }

        // Signal 2: System resource phrases (e.g. Android 15 OTP redaction) or OEM placeholders
        // appearing in extracted body text, MessagingStyle messages, or title.
        val bodyMatchesPlaceholder = isRedactedPhrase(candidate.extractedText)
        val messageMatchesPlaceholder = candidate.extractedMessages.any(::isRedactedPhrase)
        val titleMatchesPlaceholder = isRedactedPhrase(candidate.extractedTitle) &&
            candidate.extractedText.isBlank() &&
            candidate.extractedMessages.isEmpty()

        if (bodyMatchesPlaceholder || messageMatchesPlaceholder || titleMatchesPlaceholder) {
            return RedactionResult(
                isRedacted = true,
                fallbackMessage = fallbackFormatter(safeAppName),
                reason = RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER
            )
        }

        // Signal 3: VISIBILITY_PRIVATE where extracted content matches publicVersion placeholder
        // or where private body/messages were stripped empty while publicVersion text exists.
        if (candidate.visibility == VISIBILITY_PRIVATE) {
            val normalizedPublicText = normalizeForComparison(candidate.publicVersionText)
            if (normalizedPublicText.isNotEmpty()) {
                val normalizedExtractedText = normalizeForComparison(candidate.extractedText)
                val matchesPublicPlaceholder = normalizedExtractedText.isNotEmpty() &&
                    normalizedExtractedText == normalizedPublicText
                val strippedToPublicOnly = normalizedExtractedText.isEmpty() &&
                    candidate.extractedMessages.all { it.isBlank() }

                if (matchesPublicPlaceholder || strippedToPublicOnly) {
                    return RedactionResult(
                        isRedacted = true,
                        fallbackMessage = fallbackFormatter(safeAppName),
                        reason = RedactionReason.PUBLIC_VERSION_PLACEHOLDER_MATCH
                    )
                }
            }
        }

        return RedactionResult(
            isRedacted = false,
            fallbackMessage = null,
            reason = null
        )
    }

    /**
     * Checks whether an individual string (such as `EXTRA_TITLE` or `EXTRA_TEXT`) matches a
     * known system or OEM redaction placeholder phrase.
     */
    fun isRedactedPhrase(value: String?): Boolean {
        val normalized = normalizeForComparison(value)
        if (normalized.isEmpty()) return false
        return normalized in normalizedPlaceholderPhrases
    }

    private fun sanitizeAppName(rawAppName: String): String {
        val normalized = rawAppName.replace(WHITESPACE_REGEX, " ").trim()
        return normalized.ifEmpty { defaultAppLabel }
    }

    companion object {
        // Mirrors android.app.Notification.VISIBILITY_* constants.
        const val VISIBILITY_PUBLIC: Int = 1
        const val VISIBILITY_PRIVATE: Int = 0
        const val VISIBILITY_SECRET: Int = -1

        const val DEFAULT_APP_LABEL: String = "App"

        val KNOWN_OEM_REDACTED_PHRASES: Set<String> = setOf(
            "Sensitive notification content hidden",
            "Contents hidden",
            "Content hidden",
            "Notification content hidden",
            "Sensitive content hidden",
            "Hidden content"
        )

        private val WHITESPACE_REGEX = Regex("\\s+")

        internal fun normalizeForComparison(value: String?): String {
            if (value.isNullOrBlank()) return ""
            return value
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .trimEnd('.', '!', '?')
                .trim()
                .lowercase(Locale.ROOT)
        }
    }
}
