package com.notifyring.app.data

data class UserSettings(
    val monitoredPackages: Set<String> = emptySet(),
    val debounceWindowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS,
    val ringTimeoutSeconds: Int = DEFAULT_RING_TIMEOUT_SECONDS,
    val autoSpeakerEnabled: Boolean = false,
    val speechRate: Float = DEFAULT_SPEECH_RATE,
    val onboardingCompleted: Boolean = false,
    val caregiverPinHash: String? = null
) {
    val hasCaregiverPin: Boolean
        get() = !caregiverPinHash.isNullOrBlank()

    companion object {
        const val MIN_DEBOUNCE_WINDOW_MS: Long = 0L
        const val MAX_DEBOUNCE_WINDOW_MS: Long = 5_000L
        const val DEFAULT_DEBOUNCE_WINDOW_MS: Long = 2_000L

        const val MIN_RING_TIMEOUT_SECONDS: Int = 15
        const val MAX_RING_TIMEOUT_SECONDS: Int = 120
        const val DEFAULT_RING_TIMEOUT_SECONDS: Int = 45

        const val MIN_SPEECH_RATE: Float = 0.5f
        const val MAX_SPEECH_RATE: Float = 2.0f
        const val DEFAULT_SPEECH_RATE: Float = 0.85f

        fun clampDebounceWindowMs(value: Long): Long =
            value.coerceIn(MIN_DEBOUNCE_WINDOW_MS, MAX_DEBOUNCE_WINDOW_MS)

        fun clampRingTimeoutSeconds(value: Int): Int =
            value.coerceIn(MIN_RING_TIMEOUT_SECONDS, MAX_RING_TIMEOUT_SECONDS)

        fun clampSpeechRate(value: Float): Float =
            if (value.isNaN()) DEFAULT_SPEECH_RATE else value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }
}
