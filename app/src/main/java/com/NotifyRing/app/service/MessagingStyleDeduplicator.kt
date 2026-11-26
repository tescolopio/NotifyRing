package com.notifyring.app.service

import java.util.LinkedHashMap

/**
 * Pure-Kotlin snapshot of a single message inside a `NotificationCompat.MessagingStyle` payload.
 */
data class MessagingMessageCandidate(
    val sender: String,
    val text: String,
    val timestamp: Long
)

/**
 * Thread-safe, pure-Kotlin bounded LRU deduplicator (`maxEntries = 256`) for notification updates:
 * - Tracks `sbnKey -> maxTimestamp` for `MessagingStyle` notifications and emits ONLY messages
 *   with `timestamp > lastMaxTimestamp`, preventing group chats from re-reading older messages.
 * - Tracks `sbnKey -> List<String>` prior normalized lines for `InboxStyle`, `EXTRA_BIG_TEXT`,
 *   and standard text notifications so cumulative updates emit ONLY genuinely new lines.
 * - Uses an access-order [LinkedHashMap] so unit tests execute directly on the JVM without
 *   `android.util.LruCache` stubs while strictly bounding memory to [maxEntries] entries.
 */
class MessagingStyleDeduplicator(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    init {
        require(maxEntries >= 1) { "maxEntries must be at least 1" }
    }

    private data class KeyHistory(
        val maxTimestamp: Long? = null,
        val priorLines: List<String>? = null
    )

    private val lock = Any()

    private val cache = object : LinkedHashMap<String, KeyHistory>(
        minOf(maxEntries, INITIAL_CAPACITY),
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyHistory>?): Boolean {
            return size > maxEntries
        }
    }

    val size: Int
        get() = synchronized(lock) { cache.size }

    /**
     * Filters [messages] for [sbnKey], returning only non-blank messages that are genuinely new
     * (`timestamp > lastMaxTimestamp` when a prior timestamp exists for [sbnKey]) and advancing
     * the cached high-water timestamp for [sbnKey].
     */
    fun filterNewMessagingMessages(
        sbnKey: String,
        messages: List<MessagingMessageCandidate>
    ): List<MessagingMessageCandidate> {
        val key = sbnKey.trim()
        if (key.isEmpty()) return emptyList()

        val sanitized = messages.mapNotNull { candidate ->
            val cleanText = normalizeLine(candidate.text)
            if (cleanText.isEmpty()) {
                null
            } else {
                MessagingMessageCandidate(
                    sender = normalizeLine(candidate.sender),
                    text = cleanText,
                    timestamp = candidate.timestamp
                )
            }
        }
        if (sanitized.isEmpty()) return emptyList()

        synchronized(lock) {
            val existing = cache[key]
            val previousMaxTimestamp = existing?.maxTimestamp

            val freshMessages = if (previousMaxTimestamp == null) {
                sanitized
            } else {
                sanitized.filter { it.timestamp > previousMaxTimestamp }
            }

            val incomingMaxTimestamp = sanitized.maxOf { it.timestamp }
            val updatedMaxTimestamp = if (previousMaxTimestamp == null) {
                incomingMaxTimestamp
            } else {
                maxOf(previousMaxTimestamp, incomingMaxTimestamp)
            }

            cache[key] = KeyHistory(
                maxTimestamp = updatedMaxTimestamp,
                priorLines = existing?.priorLines
            )

            return freshMessages
        }
    }

    /**
     * Diffs cumulative [lines] (from `InboxStyle` `EXTRA_TEXT_LINES`, multi-line `EXTRA_BIG_TEXT`,
     * or single-line `EXTRA_TEXT`) against the prior normalized lines stored for [sbnKey],
     * returning only genuinely new lines in order.
     */
    fun filterNewLines(
        sbnKey: String,
        lines: List<String>
    ): List<String> {
        val key = sbnKey.trim()
        if (key.isEmpty()) return emptyList()

        val normalizedCurrent = lines
            .map(::normalizeLine)
            .filter { it.isNotEmpty() }
        if (normalizedCurrent.isEmpty()) return emptyList()

        synchronized(lock) {
            val existing = cache[key]
            val prior = existing?.priorLines

            val delta = if (prior == null) {
                normalizedCurrent
            } else {
                computeNewLines(prior = prior, current = normalizedCurrent)
            }

            cache[key] = KeyHistory(
                maxTimestamp = existing?.maxTimestamp,
                priorLines = normalizedCurrent
            )

            return delta
        }
    }

    /**
     * Convenience helper splitting a multi-line body (such as `EXTRA_BIG_TEXT` or `EXTRA_TEXT`)
     * into individual lines and returning only newly added/changed lines for [sbnKey].
     */
    fun filterNewTextBlock(
        sbnKey: String,
        textBlock: String
    ): List<String> {
        val lines = splitLines(textBlock)
        if (lines.isEmpty()) return emptyList()
        return filterNewLines(sbnKey, lines)
    }

    /**
     * Evicts cached state when the user or source app dismisses [sbnKey].
     */
    fun onNotificationRemoved(sbnKey: String) {
        val key = sbnKey.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            cache.remove(key)
        }
    }

    /**
     * Resets all cached deduplication state (e.g., on `onListenerConnected()` or `onDestroy()`).
     */
    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 256
        private const val INITIAL_CAPACITY: Int = 32
        private const val LOAD_FACTOR: Float = 0.75f

        private val LINE_BREAK_REGEX = Regex("\\r?\\n")
        private val INLINE_WHITESPACE_REGEX = Regex("\\s+")

        internal fun splitLines(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return text.split(LINE_BREAK_REGEX)
                .map(::normalizeLine)
                .filter { it.isNotEmpty() }
        }

        internal fun normalizeLine(value: String): String {
            if (value.isEmpty()) return ""
            return value.replace(INLINE_WHITESPACE_REGEX, " ").trim()
        }

        /**
         * Computes genuinely new lines between [prior] and [current]:
         * 1. Exact match -> empty list.
         * 2. Longest suffix of [prior] matching a prefix of [current] -> handles standard append
         *    AND sliding-window `InboxStyle` notifications where oldest lines scroll off the top,
         *    while preserving legitimate duplicate appended lines (e.g. `["Ping"]` -> `["Ping", "Ping"]`).
         * 3. Longest prefix of [prior] matching a suffix of [current] -> handles top-prepended
         *    `InboxStyle` lists where newest lines are inserted at index 0.
         * 4. Multiset frequency diff fallback -> emits lines whose count in [current] exceeds [prior].
         */
        internal fun computeNewLines(
            prior: List<String>,
            current: List<String>
        ): List<String> {
            if (prior == current) {
                return emptyList()
            }

            val suffixPrefixOverlap = longestSuffixMatchingPrefix(prior = prior, current = current)
            if (suffixPrefixOverlap > 0) {
                return current.drop(suffixPrefixOverlap)
            }

            val prefixSuffixOverlap = longestPrefixMatchingSuffix(prior = prior, current = current)
            if (prefixSuffixOverlap > 0) {
                return current.take(current.size - prefixSuffixOverlap)
            }

            val remainingCounts = HashMap<String, Int>(prior.size)
            for (line in prior) {
                remainingCounts[line] = (remainingCounts[line] ?: 0) + 1
            }

            val added = ArrayList<String>(current.size)
            for (line in current) {
                val count = remainingCounts[line] ?: 0
                if (count > 0) {
                    remainingCounts[line] = count - 1
                } else {
                    added.add(line)
                }
            }
            return added
        }

        private fun longestSuffixMatchingPrefix(
            prior: List<String>,
            current: List<String>
        ): Int {
            val maxOverlap = minOf(prior.size, current.size)
            for (overlap in maxOverlap downTo 1) {
                val priorStart = prior.size - overlap
                var matches = true
                for (i in 0 until overlap) {
                    if (prior[priorStart + i] != current[i]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    return overlap
                }
            }
            return 0
        }

        private fun longestPrefixMatchingSuffix(
            prior: List<String>,
            current: List<String>
        ): Int {
            val maxOverlap = minOf(prior.size, current.size)
            for (overlap in maxOverlap downTo 1) {
                val currentStart = current.size - overlap
                var matches = true
                for (i in 0 until overlap) {
                    if (prior[i] != current[currentStart + i]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    return overlap
                }
            }
            return 0
        }
    }
}
