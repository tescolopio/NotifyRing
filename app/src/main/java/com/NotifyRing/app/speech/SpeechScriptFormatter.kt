package com.notifyring.app.speech

import com.notifyring.app.telecom.AlertPayload

/**
 * Pure-Kotlin formatter turning an [AlertPayload] into a clear spoken announcement script
 * tailored for `0.85f` TTS playback over Telecom voice call channels.
 */
class SpeechScriptFormatter(
    val defaultMaxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS
) {

    init {
        require(defaultMaxMessageChars >= MIN_MAX_MESSAGE_CHARS) {
            "defaultMaxMessageChars must be at least $MIN_MAX_MESSAGE_CHARS"
        }
    }

    fun format(
        payload: AlertPayload,
        maxMessageChars: Int = defaultMaxMessageChars
    ): String {
        val effectiveMax = maxMessageChars.coerceAtLeast(MIN_MAX_MESSAGE_CHARS)
        val appName = normalizeWhitespace(payload.appName).trimEnd('.', '!', '?', ',', ';', ':')
        val sender = normalizeWhitespace(payload.sender).trimEnd('.', '!', '?', ',', ';', ':')
        val normalizedMessage = normalizeWhitespace(payload.message)

        val header = buildHeaderSentence(appName = appName, sender = sender)
        val body = if (normalizedMessage.isEmpty()) {
            BLANK_MESSAGE_FALLBACK
        } else {
            val bounded = truncateAtWordBoundary(normalizedMessage, effectiveMax)
            ensureTerminalPunctuation(bounded)
        }

        return "$header $body"
    }

    private fun buildHeaderSentence(appName: String, sender: String): String {
        val hasApp = appName.isNotEmpty()
        val hasSender = sender.isNotEmpty()
        val isDuplicateSender = hasApp && hasSender && sender.equals(appName, ignoreCase = true)

        return when {
            hasApp && hasSender && !isDuplicateSender -> "$appName alert from $sender."
            hasApp -> "Alert from $appName."
            hasSender -> "Alert from $sender."
            else -> "New alert."
        }
    }

    companion object {
        const val DEFAULT_MAX_MESSAGE_CHARS: Int = 320
        const val MIN_MAX_MESSAGE_CHARS: Int = 16
        const val BLANK_MESSAGE_FALLBACK: String = "No message content available."

        private val WHITESPACE_REGEX = Regex("\\s+")

        internal fun normalizeWhitespace(value: String): String {
            if (value.isEmpty()) return ""
            return value.replace(WHITESPACE_REGEX, " ").trim()
        }

        internal fun truncateAtWordBoundary(text: String, maxChars: Int): String {
            if (text.length <= maxChars) {
                return text
            }
            val window = text.substring(0, maxChars + 1)
            val lastSpaceIndex = window.lastIndexOf(' ')
            val rawCut = if (lastSpaceIndex > 0) {
                window.substring(0, lastSpaceIndex)
            } else {
                text.substring(0, maxChars)
            }
            val cleaned = rawCut.trimEnd { ch ->
                ch.isWhitespace() || ch == ',' || ch == ';' || ch == ':' || ch == '-'
            }
            return cleaned.ifEmpty { text.substring(0, maxChars) }
        }

        private fun ensureTerminalPunctuation(text: String): String {
            if (text.isEmpty()) return BLANK_MESSAGE_FALLBACK
            val lastChar = text.last()
            return if (lastChar == '.' || lastChar == '!' || lastChar == '?') {
                text
            } else {
                "$text."
            }
        }
    }
}
