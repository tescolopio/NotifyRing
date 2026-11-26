package com.notifyring.app.ui.call

import com.notifyring.app.speech.SpeechEngineState
import com.notifyring.app.telecom.AlertPayload
import com.notifyring.app.telecom.AudioEndpointType
import com.notifyring.app.telecom.CallAudioEndpoint
import com.notifyring.app.telecom.CallDisconnectReason
import com.notifyring.app.telecom.CallSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IncomingCallUiStateTest {

    private val earpiece = CallAudioEndpoint("ep", "Earpiece", AudioEndpointType.EARPIECE)
    private val speaker = CallAudioEndpoint("sp", "Speaker", AudioEndpointType.SPEAKER)
    private val bluetooth = CallAudioEndpoint("bt", "Hearing Aid", AudioEndpointType.BLUETOOTH)
    private val wiredHeadset = CallAudioEndpoint("wh", "Headset", AudioEndpointType.WIRED_HEADSET)

    private val samplePayload = AlertPayload(
        appName = "MyChart",
        packageName = "epic.mychart.android",
        sender = "Dr. Patel",
        message = "Prescription ready for pickup."
    )

    @Test
    fun selectNextAudioEndpoint_togglesEarpieceAndSpeaker() {
        val available = listOf(earpiece, speaker)

        assertEquals(speaker, selectNextAudioEndpoint(earpiece, available))
        assertEquals(earpiece, selectNextAudioEndpoint(speaker, available))
    }

    @Test
    fun selectNextAudioEndpoint_prefersBluetoothWhenLeavingSpeaker() {
        val available = listOf(earpiece, speaker, bluetooth)

        assertEquals(speaker, selectNextAudioEndpoint(bluetooth, available))
        assertEquals(bluetooth, selectNextAudioEndpoint(speaker, available))
    }

    @Test
    fun selectNextAudioEndpoint_cyclesWhenSpeakerUnavailable() {
        val available = listOf(earpiece, wiredHeadset)

        assertEquals(wiredHeadset, selectNextAudioEndpoint(earpiece, available))
        assertEquals(earpiece, selectNextAudioEndpoint(wiredHeadset, available))
    }

    @Test
    fun selectNextAudioEndpoint_returnsNullWhenOnlyCurrentOrEmpty() {
        assertNull(selectNextAudioEndpoint(earpiece, emptyList()))
        assertNull(selectNextAudioEndpoint(earpiece, listOf(earpiece)))
    }

    @Test
    fun autoAnswerGuard_preventsDoubleAnswerForSameCallId() {
        val callId = UUID.randomUUID()
        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        val guard = AutoAnswerGuard()

        assertEquals(
            callId,
            guard.consumeTargetCallId(
                autoAnswerRequested = true,
                requestedCallId = callId,
                sessionState = ringing
            )
        )
        // Second attempt for same callId is suppressed.
        assertNull(
            guard.consumeTargetCallId(
                autoAnswerRequested = true,
                requestedCallId = callId,
                sessionState = ringing
            )
        )
    }

    @Test
    fun autoAnswerGuard_ignoresMismatchedCallIdAndAllowsNullExtra() {
        val callId = UUID.randomUUID()
        val otherCallId = UUID.randomUUID()
        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        val guard = AutoAnswerGuard()

        assertNull(
            guard.consumeTargetCallId(
                autoAnswerRequested = true,
                requestedCallId = otherCallId,
                sessionState = ringing
            )
        )
        assertEquals(
            callId,
            guard.consumeTargetCallId(
                autoAnswerRequested = true,
                requestedCallId = null,
                sessionState = ringing
            )
        )
    }

    @Test
    fun autoAnswerGuard_releaseOnFailureAllowsRetry() {
        val callId = UUID.randomUUID()
        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        val guard = AutoAnswerGuard()

        assertEquals(callId, guard.consumeTargetCallId(true, callId, ringing))
        guard.releaseOnFailure(callId)
        assertEquals(callId, guard.consumeTargetCallId(true, callId, ringing))
    }

    @Test
    fun terminalFinishTracker_finishesOnEndedOrPostCallIdle() {
        val callId = UUID.randomUUID()
        val tracker = CallTerminalFinishTracker(expectedCallId = callId)

        // Initial Idle before any call is observed does not finish prematurely.
        assertFalse(tracker.shouldFinish(CallSessionState.Idle))

        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        assertFalse(tracker.shouldFinish(ringing))
        assertTrue(tracker.hasObservedCall)

        // Idle after observing a call triggers finish.
        assertTrue(tracker.shouldFinish(CallSessionState.Idle))

        // Ended state triggers finish.
        val ended = CallSessionState.Ended(
            callId = callId,
            payload = samplePayload,
            reason = CallDisconnectReason.LOCAL
        )
        assertTrue(tracker.shouldFinish(ended))
    }

    @Test
    fun shouldInterceptBackPress_blocksOnlyRingingAndActive() {
        val callId = UUID.randomUUID()
        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        val active = CallSessionState.Active(
            callId = callId,
            payload = samplePayload,
            answeredAtMs = 2_000L,
            currentEndpoint = speaker,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false,
            isHeld = false
        )
        val ended = CallSessionState.Ended(
            callId = callId,
            payload = samplePayload,
            reason = CallDisconnectReason.LOCAL
        )

        assertTrue(shouldInterceptBackPress(ringing))
        assertTrue(shouldInterceptBackPress(active))
        assertFalse(shouldInterceptBackPress(ended))
        assertFalse(shouldInterceptBackPress(CallSessionState.Idle))
    }

    @Test
    fun mapToIncomingCallUiState_projectsActiveStateAndSpeechStatus() {
        val callId = UUID.randomUUID()
        val active = CallSessionState.Active(
            callId = callId,
            payload = samplePayload,
            answeredAtMs = 2_000L,
            currentEndpoint = speaker,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false,
            isHeld = false
        )

        val speakingState = mapToIncomingCallUiState(
            sessionState = active,
            speechState = SpeechEngineState.Speaking(callId, "Hello"),
            isLocalMuted = false
        ) as IncomingCallUiState.Active

        assertEquals(CallSpeechStatus.SPEAKING, speakingState.speechStatus)
        assertTrue(speakingState.isSpeakerActive)
        assertEquals(earpiece, speakingState.nextEndpoint)
        assertTrue(speakingState.canOpenSourceApp)
        assertEquals("M", speakingState.badgeInitial)

        val mutedState = mapToIncomingCallUiState(
            sessionState = active,
            speechState = SpeechEngineState.Paused(callId),
            isLocalMuted = true
        ) as IncomingCallUiState.Active

        assertEquals(CallSpeechStatus.MUTED, mutedState.speechStatus)
        assertTrue(mutedState.isMuted)
    }

    @Test
    fun parseCallIdOrNull_handlesValidAndInvalidInputs() {
        val id = UUID.randomUUID()
        assertEquals(id, parseCallIdOrNull(id.toString()))
        assertNull(parseCallIdOrNull(null))
        assertNull(parseCallIdOrNull(""))
        assertNull(parseCallIdOrNull("not-a-uuid"))
    }

    @Test
    fun selectNextAudioEndpoint_handlesWiredFallbackNullCurrentAndDuplicates() {
        val availableWithoutBluetooth = listOf(earpiece, speaker, wiredHeadset, speaker)
        assertEquals(wiredHeadset, selectNextAudioEndpoint(speaker, availableWithoutBluetooth))

        // Null current endpoint prefers speaker if available, or single endpoint if only one exists.
        assertEquals(speaker, selectNextAudioEndpoint(null, listOf(earpiece, speaker)))
        assertEquals(earpiece, selectNextAudioEndpoint(null, listOf(earpiece)))
    }

    @Test
    fun autoAnswerGuard_ignoresWhenNotRequestedOrNotRingingAndGuardsRelease() {
        val callId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val ringing = CallSessionState.Ringing(
            callId = callId,
            payload = samplePayload,
            startedAtMs = 1_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false
        )
        val active = CallSessionState.Active(
            callId = callId,
            payload = samplePayload,
            answeredAtMs = 2_000L,
            currentEndpoint = speaker,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false,
            isHeld = false
        )
        val guard = AutoAnswerGuard()

        assertNull(guard.consumeTargetCallId(autoAnswerRequested = false, requestedCallId = callId, sessionState = ringing))
        assertNull(guard.consumeTargetCallId(autoAnswerRequested = true, requestedCallId = callId, sessionState = active))
        assertNull(guard.consumeTargetCallId(autoAnswerRequested = true, requestedCallId = callId, sessionState = CallSessionState.Idle))

        assertEquals(callId, guard.consumeTargetCallId(true, callId, ringing))
        // Releasing a different callId must not unlock the consumed callId.
        guard.releaseOnFailure(otherId)
        assertNull(guard.consumeTargetCallId(true, callId, ringing))
    }

    @Test
    fun terminalFinishTracker_ignoresUnrelatedEndedBeforeObservationAndTracksActive() {
        val expectedId = UUID.randomUUID()
        val unrelatedId = UUID.randomUUID()
        val tracker = CallTerminalFinishTracker(expectedCallId = expectedId)

        val unrelatedEnded = CallSessionState.Ended(
            callId = unrelatedId,
            payload = samplePayload,
            reason = CallDisconnectReason.LOCAL
        )
        assertFalse(tracker.shouldFinish(unrelatedEnded))
        assertFalse(tracker.hasObservedCall)

        tracker.updateExpectedCallId(unrelatedId)
        assertTrue(tracker.shouldFinish(unrelatedEnded))

        val activeTracker = CallTerminalFinishTracker(expectedCallId = expectedId)
        val active = CallSessionState.Active(
            callId = expectedId,
            payload = samplePayload,
            answeredAtMs = 2_000L,
            currentEndpoint = speaker,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = false,
            isHeld = false
        )
        assertFalse(activeTracker.shouldFinish(active))
        assertTrue(activeTracker.hasObservedCall)
        assertTrue(activeTracker.shouldFinish(CallSessionState.Idle))
    }

    @Test
    fun mapToIncomingCallUiState_projectsHeldErrorRingingEndedAndBadgeFallback() {
        val callId = UUID.randomUUID()
        val active = CallSessionState.Active(
            callId = callId,
            payload = samplePayload.copy(appName = "   ", sender = "911 Nurse", packageName = ""),
            answeredAtMs = 2_000L,
            currentEndpoint = earpiece,
            availableEndpoints = listOf(earpiece, speaker),
            isMuted = true,
            isHeld = true
        )

        val heldState = mapToIncomingCallUiState(
            sessionState = active,
            speechState = SpeechEngineState.Speaking(callId, "Hi"),
            isLocalMuted = false
        ) as IncomingCallUiState.Active
        assertEquals(CallSpeechStatus.PAUSED, heldState.speechStatus)
        assertFalse(heldState.canOpenSourceApp)
        assertEquals("9", heldState.badgeInitial)

        val errorState = mapToIncomingCallUiState(
            sessionState = active.copy(isHeld = false, isMuted = false),
            speechState = SpeechEngineState.Error("TTS unavailable"),
            isLocalMuted = false
        ) as IncomingCallUiState.Active
        assertEquals(CallSpeechStatus.ERROR, errorState.speechStatus)
        assertEquals("TTS unavailable", errorState.speechErrorDetail)

        val endedState = mapToIncomingCallUiState(
            sessionState = CallSessionState.Ended(callId, samplePayload, CallDisconnectReason.MISSED),
            speechState = SpeechEngineState.Stopped,
            isLocalMuted = false
        ) as IncomingCallUiState.Ending
        assertEquals("MyChart", endedState.appName)
        assertEquals(CallDisconnectReason.MISSED, endedState.reason)

        assertEquals("!", extractBadgeInitial("  ---  ", "   "))
    }
}
