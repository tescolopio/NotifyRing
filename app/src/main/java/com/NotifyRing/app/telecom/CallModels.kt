package com.notifyring.app.telecom

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Immutable alert payload carried end-to-end from notification listener through
 * Telecom session management, foreground notification, UI, and TTS speech engine.
 */
data class AlertPayload(
    val appName: String,
    val packageName: String = "",
    val sender: String,
    val message: String
)

/**
 * Pure-Kotlin audio endpoint classification decoupled from Android framework classes
 * so state machines and unit tests run directly on the JVM.
 */
enum class AudioEndpointType {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED_HEADSET,
    STREAMING,
    UNKNOWN
}

/**
 * Pure-Kotlin representation of an audio routing endpoint for an active or ringing call.
 */
data class CallAudioEndpoint(
    val id: String,
    val name: String,
    val type: AudioEndpointType
)

/**
 * Explicit reason why an alert call disconnected.
 */
enum class CallDisconnectReason {
    LOCAL,
    REJECTED,
    MISSED,
    REMOTE,
    ERROR
}

/**
 * End-to-end observable call session state shared across Telecom, FGS, Compose UI, and TTS.
 */
sealed interface CallSessionState {
    val activeCallIdOrNull: UUID?
        get() = when (this) {
            is Ringing -> callId
            is Active -> callId
            Idle, is Ended -> null
        }

    data object Idle : CallSessionState

    data class Ringing(
        val callId: UUID,
        val payload: AlertPayload,
        val startedAtMs: Long,
        val currentEndpoint: CallAudioEndpoint?,
        val availableEndpoints: List<CallAudioEndpoint>,
        val isMuted: Boolean
    ) : CallSessionState

    data class Active(
        val callId: UUID,
        val payload: AlertPayload,
        val answeredAtMs: Long,
        val currentEndpoint: CallAudioEndpoint?,
        val availableEndpoints: List<CallAudioEndpoint>,
        val isMuted: Boolean,
        val isHeld: Boolean
    ) : CallSessionState

    data class Ended(
        val callId: UUID,
        val payload: AlertPayload,
        val reason: CallDisconnectReason,
        val errorMessage: String? = null
    ) : CallSessionState
}

/**
 * Enqueued alert call item processed serially by [LocalCallManager]'s single-flight actor.
 */
data class AlertCallRequest(
    val callId: UUID = UUID.randomUUID(),
    val payload: AlertPayload,
    val ringTimeoutMs: Long? = null
)

/**
 * Result of a call control operation without exposing AndroidX Telecom internals to callers/tests.
 */
sealed interface CallOpResult {
    data object Success : CallOpResult
    data class Error(val errorCode: Int, val detail: String? = null) : CallOpResult
}

/**
 * Clean audio & state facade for NR-04 `CallSpeechEngine` and call UI screens so they can
 * inspect active endpoints and request endpoint switches without tight coupling to Telecom APIs.
 */
interface CallAudioController {
    val sessionState: StateFlow<CallSessionState>

    /**
     * Requests switching audio routing to [endpointId] for the specified [callId].
     * Returns `true` only when the underlying Telecom / fallback session accepted the switch.
     */
    suspend fun requestEndpointChange(callId: UUID, endpointId: String): Boolean

    /**
     * Convenience overload targeting the currently ringing or active call, if any.
     */
    suspend fun requestEndpointChange(endpointId: String): Boolean {
        val activeId = sessionState.value.activeCallIdOrNull ?: return false
        return requestEndpointChange(activeId, endpointId)
    }

    fun addLifecycleListener(listener: CallLifecycleListener)
    fun removeLifecycleListener(listener: CallLifecycleListener)
}

/**
 * Lifecycle observer hooks so NR-04 `CallSpeechEngine` (or call logging) can react cleanly
 * to ringing warm-up, answer playback, cellular hold preemption, resume, and teardown.
 */
interface CallLifecycleListener {
    fun onCallRinging(callId: UUID, payload: AlertPayload) {}
    fun onCallAnswered(callId: UUID, payload: AlertPayload) {}
    fun onCallHeld(callId: UUID, payload: AlertPayload) {}
    fun onCallResumed(callId: UUID, payload: AlertPayload) {}
    fun onCallEnded(
        callId: UUID,
        payload: AlertPayload,
        reason: CallDisconnectReason,
        answered: Boolean
    ) {}
}

/**
 * Sanitizes an Android application package name into a safe SIP user token (`[a-zA-Z0-9._-]`).
 */
internal fun sanitizePackageForSip(packageName: String): String {
    val trimmed = packageName.trim()
    if (trimmed.isEmpty()) return ""
    return buildString(trimmed.length) {
        for (ch in trimmed) {
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') {
                append(ch)
            } else {
                append('_')
            }
        }
    }.trim('_', '.')
}
