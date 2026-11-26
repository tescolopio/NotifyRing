package com.notifyring.app.ui.call

import com.notifyring.app.speech.SpeechEngineState
import com.notifyring.app.telecom.AudioEndpointType
import com.notifyring.app.telecom.CallAudioEndpoint
import com.notifyring.app.telecom.CallDisconnectReason
import com.notifyring.app.telecom.CallSessionState
import java.util.UUID

/**
 * Pure-Kotlin speech status projection for the active call screen.
 */
enum class CallSpeechStatus {
    WARMING_UP,
    PREPARING,
    SPEAKING,
    PAUSED,
    MUTED,
    READY,
    ERROR
}

/**
 * Immutable UI state rendered by [IncomingCallScreen].
 */
sealed interface IncomingCallUiState {
    data class Ringing(
        val callId: UUID,
        val appName: String,
        val packageName: String,
        val sender: String,
        val message: String,
        val badgeInitial: String
    ) : IncomingCallUiState

    data class Active(
        val callId: UUID,
        val appName: String,
        val packageName: String,
        val sender: String,
        val message: String,
        val badgeInitial: String,
        val isHeld: Boolean,
        val isMuted: Boolean,
        val currentEndpoint: CallAudioEndpoint?,
        val nextEndpoint: CallAudioEndpoint?,
        val isSpeakerActive: Boolean,
        val speechStatus: CallSpeechStatus,
        val speechErrorDetail: String?,
        val canOpenSourceApp: Boolean
    ) : IncomingCallUiState

    data class Ending(
        val appName: String,
        val reason: CallDisconnectReason?
    ) : IncomingCallUiState
}

/**
 * Selects the next audio endpoint when the user taps the Speaker / Audio Route toggle.
 *
 * Strategy:
 * - If currently on [AudioEndpointType.SPEAKER], switch back to the best non-speaker route
 *   (preferring [AudioEndpointType.BLUETOOTH], then [AudioEndpointType.WIRED_HEADSET],
 *   then [AudioEndpointType.EARPIECE], then any other non-speaker endpoint).
 * - If not currently on [AudioEndpointType.SPEAKER], prefer switching to [AudioEndpointType.SPEAKER]
 *   when available; otherwise cycle to the next available endpoint.
 * - Returns `null` if no alternate endpoint exists.
 */
fun selectNextAudioEndpoint(
    currentEndpoint: CallAudioEndpoint?,
    availableEndpoints: List<CallAudioEndpoint>
): CallAudioEndpoint? {
    val distinctEndpoints = availableEndpoints.distinctBy { it.id }
    if (distinctEndpoints.isEmpty()) return null

    if (distinctEndpoints.size == 1) {
        val single = distinctEndpoints.first()
        return if (single.id == currentEndpoint?.id) null else single
    }

    if (currentEndpoint?.type == AudioEndpointType.SPEAKER) {
        val preferredOrder = listOf(
            AudioEndpointType.BLUETOOTH,
            AudioEndpointType.WIRED_HEADSET,
            AudioEndpointType.EARPIECE
        )
        for (preferredType in preferredOrder) {
            val match = distinctEndpoints.firstOrNull {
                it.type == preferredType && it.id != currentEndpoint.id
            }
            if (match != null) return match
        }
        return distinctEndpoints.firstOrNull { it.id != currentEndpoint.id }
    }

    val speakerEndpoint = distinctEndpoints.firstOrNull {
        it.type == AudioEndpointType.SPEAKER && it.id != currentEndpoint?.id
    }
    if (speakerEndpoint != null) return speakerEndpoint

    val currentIndex = currentEndpoint?.let { current ->
        distinctEndpoints.indexOfFirst { it.id == current.id }
    } ?: -1
    val nextIndex = if (currentIndex >= 0) {
        (currentIndex + 1) % distinctEndpoints.size
    } else {
        0
    }
    val candidate = distinctEndpoints[nextIndex]
    return if (candidate.id == currentEndpoint?.id) null else candidate
}

/**
 * Returns `true` when Back press must be intercepted so accidental Back navigation cannot
 * orphan a ringing or connected alert call.
 */
fun shouldInterceptBackPress(sessionState: CallSessionState): Boolean =
    sessionState is CallSessionState.Ringing || sessionState is CallSessionState.Active

/**
 * Safely parses a string extra into a [UUID], returning `null` if blank or malformed.
 */
fun parseCallIdOrNull(raw: String?): UUID? {
    if (raw.isNullOrBlank()) return null
    return runCatching { UUID.fromString(raw.trim()) }.getOrNull()
}

/**
 * Guards auto-answer handling across `onCreate` and `onNewIntent` (`singleTop`) so the same
 * ringing call is answered at most once and stale/mismatched intents are ignored.
 */
class AutoAnswerGuard {
    private var lastAnsweredCallId: UUID? = null

    fun consumeTargetCallId(
        autoAnswerRequested: Boolean,
        requestedCallId: UUID?,
        sessionState: CallSessionState
    ): UUID? {
        if (!autoAnswerRequested) return null
        val ringing = sessionState as? CallSessionState.Ringing ?: return null
        if (requestedCallId != null && ringing.callId != requestedCallId) return null
        if (lastAnsweredCallId == ringing.callId) return null
        lastAnsweredCallId = ringing.callId
        return ringing.callId
    }

    fun releaseOnFailure(callId: UUID) {
        if (lastAnsweredCallId == callId) {
            lastAnsweredCallId = null
        }
    }
}

/**
 * Tracks whether [IncomingCallActivity] should call `finishAndRemoveTask()` upon receiving
 * a terminal [CallSessionState.Ended] or a post-call [CallSessionState.Idle].
 */
class CallTerminalFinishTracker(
    private var expectedCallId: UUID? = null
) {
    var hasObservedCall: Boolean = false
        private set

    fun updateExpectedCallId(callId: UUID?) {
        if (callId != null) {
            expectedCallId = callId
        }
    }

    fun shouldFinish(state: CallSessionState): Boolean = when (state) {
        is CallSessionState.Ringing -> {
            hasObservedCall = true
            false
        }
        is CallSessionState.Active -> {
            hasObservedCall = true
            false
        }
        is CallSessionState.Ended -> {
            hasObservedCall || expectedCallId == null || state.callId == expectedCallId
        }
        CallSessionState.Idle -> hasObservedCall
    }
}

/**
 * Maps Telecom [CallSessionState], [SpeechEngineState], and local mute state into an
 * immutable [IncomingCallUiState] for Compose rendering.
 */
fun mapToIncomingCallUiState(
    sessionState: CallSessionState,
    speechState: SpeechEngineState,
    isLocalMuted: Boolean
): IncomingCallUiState = when (sessionState) {
    is CallSessionState.Ringing -> IncomingCallUiState.Ringing(
        callId = sessionState.callId,
        appName = sessionState.payload.appName.trim(),
        packageName = sessionState.payload.packageName.trim(),
        sender = sessionState.payload.sender.trim(),
        message = sessionState.payload.message.trim(),
        badgeInitial = extractBadgeInitial(
            appName = sessionState.payload.appName,
            sender = sessionState.payload.sender
        )
    )

    is CallSessionState.Active -> {
        val effectiveMuted = sessionState.isMuted || isLocalMuted
        val speechStatus = when {
            sessionState.isHeld -> CallSpeechStatus.PAUSED
            effectiveMuted -> CallSpeechStatus.MUTED
            else -> when (speechState) {
                is SpeechEngineState.WarmingUp -> CallSpeechStatus.WARMING_UP
                is SpeechEngineState.WaitingLeadIn -> CallSpeechStatus.PREPARING
                is SpeechEngineState.Speaking -> CallSpeechStatus.SPEAKING
                is SpeechEngineState.Paused -> CallSpeechStatus.PAUSED
                is SpeechEngineState.Error -> CallSpeechStatus.ERROR
                SpeechEngineState.Stopped, SpeechEngineState.Idle -> CallSpeechStatus.READY
            }
        }
        IncomingCallUiState.Active(
            callId = sessionState.callId,
            appName = sessionState.payload.appName.trim(),
            packageName = sessionState.payload.packageName.trim(),
            sender = sessionState.payload.sender.trim(),
            message = sessionState.payload.message.trim(),
            badgeInitial = extractBadgeInitial(
                appName = sessionState.payload.appName,
                sender = sessionState.payload.sender
            ),
            isHeld = sessionState.isHeld,
            isMuted = effectiveMuted,
            currentEndpoint = sessionState.currentEndpoint,
            nextEndpoint = selectNextAudioEndpoint(
                currentEndpoint = sessionState.currentEndpoint,
                availableEndpoints = sessionState.availableEndpoints
            ),
            isSpeakerActive = sessionState.currentEndpoint?.type == AudioEndpointType.SPEAKER,
            speechStatus = speechStatus,
            speechErrorDetail = (speechState as? SpeechEngineState.Error)?.message,
            canOpenSourceApp = sessionState.payload.packageName.isNotBlank()
        )
    }

    is CallSessionState.Ended -> IncomingCallUiState.Ending(
        appName = sessionState.payload.appName.trim(),
        reason = sessionState.reason
    )

    CallSessionState.Idle -> IncomingCallUiState.Ending(
        appName = "",
        reason = null
    )
}

internal fun extractBadgeInitial(appName: String, sender: String): String {
    val source = appName.trim().ifEmpty { sender.trim() }
    val firstLetterOrDigit = source.firstOrNull { it.isLetterOrDigit() }
    return firstLetterOrDigit?.uppercaseChar()?.toString() ?: "!"
}
