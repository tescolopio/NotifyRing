package com.notifyring.app.telecom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCallManagerTest {

    @Test
    fun `single-flight queue processes calls serially and preserves full AlertPayload`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver()
        val uiBridge = RecordingUiBridge()
        var nowMs = 1_000L

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            clock = { nowMs },
            logger = {}
        )

        val payload1 = AlertPayload(
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            sender = "Dr. Patel",
            message = "Your clinic lab results are ready."
        )
        val payload2 = AlertPayload(
            appName = "Messages",
            packageName = "com.google.android.apps.messaging",
            sender = "Daughter Maya",
            message = "Picking you up at 4 PM!"
        )

        val callId1 = manager.enqueueCall(payload1)
        val callId2 = manager.enqueueCall(payload2)
        assertNotEquals(callId1, callId2)

        runCurrent()

        // Only Call 1 is started; Call 2 waits in the single-flight channel.
        assertEquals(listOf(callId1), driver.startedCallIds)
        val ringing1 = manager.sessionState.value as CallSessionState.Ringing
        assertEquals(callId1, ringing1.callId)
        assertEquals(payload1, ringing1.payload)
        assertEquals(1_000L, ringing1.startedAtMs)

        // Verify FGS started AFTER Telecom call registration, not before.
        assertEquals(listOf("registerApp", "onCallRegistered:$callId1", "startFgs:$callId1:fallback=false"), driver.andBridgeTimeline(uiBridge))

        // Answer Call 1 and disconnect it; Call 2 must immediately start next.
        nowMs = 2_500L
        assertTrue(manager.answer(callId1))
        runCurrent()

        val active1 = manager.sessionState.value as CallSessionState.Active
        assertEquals(callId1, active1.callId)
        assertEquals(payload1, active1.payload)
        assertEquals(2_500L, active1.answeredAtMs)

        assertTrue(manager.disconnect(callId1, CallDisconnectReason.LOCAL))
        runCurrent()

        // Call 2 should now be ringing with its complete AlertPayload intact.
        assertEquals(listOf(callId1, callId2), driver.startedCallIds)
        val ringing2 = manager.sessionState.value as CallSessionState.Ringing
        assertEquals(callId2, ringing2.callId)
        assertEquals(payload2, ringing2.payload)
        assertEquals("com.google.android.apps.messaging", ringing2.payload.packageName)

        // Finish Call 2 cleanly.
        assertTrue(manager.disconnect(callId2, CallDisconnectReason.REJECTED))
        runCurrent()

        val ended2 = manager.sessionState.value as CallSessionState.Ended
        assertEquals(callId2, ended2.callId)
        assertEquals(CallDisconnectReason.REJECTED, ended2.reason)

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `stale or mismatched callId is rejected by answer, disconnect, and requestEndpointChange`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver()
        val uiBridge = RecordingUiBridge()

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )

        val payload1 = AlertPayload("Signal", "org.thoughtcrime.securesms", "Alex", "Urgent")
        val payload2 = AlertPayload("Signal", "org.thoughtcrime.securesms", "Sam", "Follow-up")

        val callId1 = manager.enqueueCall(payload1)
        val callId2 = manager.enqueueCall(payload2)
        runCurrent()

        val bogusId = UUID.randomUUID()
        assertFalse(manager.answer(bogusId))
        assertFalse(manager.disconnect(bogusId, CallDisconnectReason.REJECTED))
        assertFalse(manager.requestEndpointChange(bogusId, "ep-speaker"))

        // Active call 1 remains untouched in Ringing.
        assertTrue(manager.sessionState.value is CallSessionState.Ringing)
        assertEquals(callId1, (manager.sessionState.value as CallSessionState.Ringing).callId)

        // End call 1 so call 2 begins ringing; stale actions targeting callId1 must be ignored!
        assertTrue(manager.disconnect(callId1, CallDisconnectReason.REJECTED))
        runCurrent()

        assertEquals(callId2, (manager.sessionState.value as CallSessionState.Ringing).callId)
        assertFalse(manager.answer(callId1))
        assertFalse(manager.disconnect(callId1, CallDisconnectReason.LOCAL))
        assertEquals(callId2, (manager.sessionState.value as CallSessionState.Ringing).callId)

        assertTrue(manager.disconnect(callId2, CallDisconnectReason.LOCAL))
        runCurrent()

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `45s default ring timeout disconnects unanswered call with MISSED and is cancelled on answer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver()
        val uiBridge = RecordingUiBridge()

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )
        assertEquals(45_000L, manager.defaultRingTimeoutMs)

        val payload1 = AlertPayload("Calendar", "com.google.android.calendar", "Clinic", "Appointment")
        val callId1 = manager.enqueueCall(payload1)
        runCurrent()

        // Just before 45s: still ringing.
        advanceTimeBy(44_999L)
        runCurrent()
        assertTrue(manager.sessionState.value is CallSessionState.Ringing)

        // At 45_000ms: automatically disconnects with MISSED.
        advanceTimeBy(1L)
        runCurrent()

        val ended1 = manager.sessionState.value as CallSessionState.Ended
        assertEquals(callId1, ended1.callId)
        assertEquals(CallDisconnectReason.MISSED, ended1.reason)
        assertEquals(CallDisconnectReason.MISSED, driver.lastDisconnectReasonByCall[callId1])
        assertTrue(uiBridge.stoppedCallIds.contains(callId1))

        // Enqueue second call, answer at 20s, advance past 45s -> must stay Active (timeout cancelled).
        val payload2 = AlertPayload("Messages", "com.android.mms", "Caregiver", "Check in")
        val callId2 = manager.enqueueCall(payload2)
        runCurrent()

        advanceTimeBy(20_000L)
        assertTrue(manager.answer(callId2))
        runCurrent()
        assertTrue(manager.sessionState.value is CallSessionState.Active)

        advanceTimeBy(40_000L)
        runCurrent()
        assertTrue(manager.sessionState.value is CallSessionState.Active)

        assertTrue(manager.disconnect(callId2, CallDisconnectReason.LOCAL))
        runCurrent()

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `CallControlResult Error on answer and endpoint change preserves valid session state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver()
        val uiBridge = RecordingUiBridge()

        val earpiece = CallAudioEndpoint("ep-ear", "Earpiece", AudioEndpointType.EARPIECE)
        val speaker = CallAudioEndpoint("ep-spk", "Speaker", AudioEndpointType.SPEAKER)
        val bluetooth = CallAudioEndpoint("ep-bt", "Hearing Aid", AudioEndpointType.BLUETOOTH)

        driver.initialEndpoints = listOf(earpiece, speaker, bluetooth)
        driver.initialCurrentEndpoint = earpiece
        driver.nextAnswerResult = CallOpResult.Error(errorCode = 2, detail = "Cannot hold active SIM call")
        driver.failingEndpointIds.add("ep-bt")

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )

        val callId = manager.enqueueCall(
            AlertPayload("WhatsApp", "com.whatsapp", "Home Nurse", "On my way")
        )
        runCurrent()

        // First answer fails -> returns false, stays Ringing, does NOT show fake connected notification.
        assertFalse(manager.answer(callId))
        assertTrue(manager.sessionState.value is CallSessionState.Ringing)
        assertTrue(uiBridge.connectedCallIds.isEmpty())

        // Subsequent answer succeeds -> transitions to Active with initial endpoint intact.
        driver.nextAnswerResult = CallOpResult.Success
        assertTrue(manager.answer(callId))
        runCurrent()

        val activeBeforeSwitch = manager.sessionState.value as CallSessionState.Active
        assertEquals(earpiece, activeBeforeSwitch.currentEndpoint)
        assertEquals(listOf(earpiece, speaker, bluetooth), activeBeforeSwitch.availableEndpoints)

        // Failing Bluetooth endpoint switch returns false and does NOT corrupt currentEndpoint.
        assertFalse(manager.requestEndpointChange(callId, "ep-bt"))
        val activeAfterFailedSwitch = manager.sessionState.value as CallSessionState.Active
        assertEquals(earpiece, activeAfterFailedSwitch.currentEndpoint)

        // Valid Speaker switch via CallAudioController convenience overload succeeds and updates state.
        assertTrue(manager.requestEndpointChange("ep-spk"))
        val activeAfterSpeakerSwitch = manager.sessionState.value as CallSessionState.Active
        assertEquals(speaker, activeAfterSpeakerSwitch.currentEndpoint)

        assertTrue(manager.disconnect(callId, CallDisconnectReason.LOCAL))
        runCurrent()

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `onSetInactive sets isHeld true, onSetActive resumes false, and onDisconnect tears down cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver()
        val uiBridge = RecordingUiBridge()
        val lifecycleEvents = mutableListOf<String>()

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )
        manager.addLifecycleListener(object : CallLifecycleListener {
            override fun onCallRinging(callId: UUID, payload: AlertPayload) {
                lifecycleEvents.add("ringing")
            }

            override fun onCallAnswered(callId: UUID, payload: AlertPayload) {
                lifecycleEvents.add("answered")
            }

            override fun onCallHeld(callId: UUID, payload: AlertPayload) {
                lifecycleEvents.add("held")
            }

            override fun onCallResumed(callId: UUID, payload: AlertPayload) {
                lifecycleEvents.add("resumed")
            }

            override fun onCallEnded(
                callId: UUID,
                payload: AlertPayload,
                reason: CallDisconnectReason,
                answered: Boolean
            ) {
                lifecycleEvents.add("ended:${reason.name}:answered=$answered")
            }
        })

        val callId = manager.enqueueCall(
            AlertPayload("Messages", "com.google.android.apps.messaging", "Pharmacy", "Refill ready")
        )
        runCurrent()

        // Remote answer (e.g. headset button) + mute state update.
        driver.simulateRemoteAnswer(callId)
        driver.simulateMuteChanged(callId, true)
        runCurrent()

        val activeInitial = manager.sessionState.value as CallSessionState.Active
        assertFalse(activeInitial.isHeld)
        assertTrue(activeInitial.isMuted)

        // Incoming cellular call preempts -> onSetInactive sets isHeld = true.
        driver.simulateSetInactive(callId)
        runCurrent()
        assertTrue((manager.sessionState.value as CallSessionState.Active).isHeld)

        // Cellular call ends -> onSetActive sets isHeld = false.
        driver.simulateSetActive(callId)
        runCurrent()
        assertFalse((manager.sessionState.value as CallSessionState.Active).isHeld)

        // Remote disconnect tears down session and stops FGS.
        driver.simulateRemoteDisconnect(callId, CallDisconnectReason.REMOTE)
        runCurrent()

        val ended = manager.sessionState.value as CallSessionState.Ended
        assertEquals(callId, ended.callId)
        assertEquals(CallDisconnectReason.REMOTE, ended.reason)
        assertNull(ended.errorMessage)
        assertTrue(uiBridge.stoppedCallIds.contains(callId))
        assertEquals(
            listOf("ringing", "answered", "held", "resumed", "ended:REMOTE:answered=true"),
            lifecycleEvents
        )

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `missing FEATURE_TELECOM falls back to local FGS and IncomingCallActivity with one-time registration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = FakeTelecomCallDriver(
            registerException = UnsupportedOperationException("No FEATURE_TELECOM")
        )
        val uiBridge = RecordingUiBridge()

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )

        val payload1 = AlertPayload("Telegram", "org.telegram.messenger", "Son", "Call me")
        val payload2 = AlertPayload("Telegram", "org.telegram.messenger", "Daughter", "Dinner?")

        val callId1 = manager.enqueueCall(payload1)
        runCurrent()

        // Fallback launched FGS + fallback activity, and registerApp was only attempted once.
        assertEquals(1, driver.registerAttempts)
        assertTrue(driver.startedCallIds.isEmpty())
        assertEquals(listOf(callId1 to true), uiBridge.startedCallsWithFallbackFlag)

        val ringing1 = manager.sessionState.value as CallSessionState.Ringing
        assertEquals(AudioEndpointType.EARPIECE, ringing1.currentEndpoint?.type)
        assertEquals(2, ringing1.availableEndpoints.size)

        assertTrue(manager.answer(callId1))
        runCurrent()
        assertTrue(manager.sessionState.value is CallSessionState.Active)

        // Switch to fallback speaker endpoint.
        assertTrue(manager.requestEndpointChange(callId1, LocalCallManager.FALLBACK_SPEAKER_ID))
        assertEquals(
            AudioEndpointType.SPEAKER,
            (manager.sessionState.value as CallSessionState.Active).currentEndpoint?.type
        )

        assertTrue(manager.disconnect(callId1, CallDisconnectReason.LOCAL))
        runCurrent()

        // Second call reuses cached fallback state without re-calling registerApp().
        val callId2 = manager.enqueueCall(payload2)
        runCurrent()
        assertEquals(1, driver.registerAttempts)
        assertEquals(callId2, (manager.sessionState.value as CallSessionState.Ringing).callId)

        assertTrue(manager.disconnect(callId2, CallDisconnectReason.REJECTED))
        runCurrent()

        manager.shutdown()
        managerScope.cancel()
    }

    @Test
    fun `CancellationException is propagated and never swallowed as CallDisconnectReason ERROR`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val job = SupervisorJob()
        val managerScope = CoroutineScope(job + dispatcher)
        val driver = FakeTelecomCallDriver()
        driver.throwCancellationOnAnswer = true
        val uiBridge = RecordingUiBridge()

        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            logger = {}
        )

        val callId = manager.enqueueCall(
            AlertPayload("Signal", "org.thoughtcrime.securesms", "Doctor", "Test")
        )
        runCurrent()

        var caughtCancellation = false
        try {
            manager.answer(callId)
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("Expected CancellationException to be rethrown", caughtCancellation)

        // Cancel scope mid-call and verify FGS cleanup runs without emitting ERROR state.
        managerScope.cancel(CancellationException("Scope shutdown"))
        advanceUntilIdle()

        assertTrue(uiBridge.stoppedCallIds.contains(callId))
        assertEquals(CallSessionState.Idle, manager.sessionState.value)
        assertEquals("com.whatsapp", sanitizePackageForSip(" com.whatsapp "))
        assertEquals("com.example_bad_pkg", sanitizePackageForSip("com.example/bad@pkg"))
    }

    private class RecordingUiBridge : CallForegroundUiBridge {
        val events = mutableListOf<String>()
        val startedCallsWithFallbackFlag = mutableListOf<Pair<UUID, Boolean>>()
        val connectedCallIds = mutableListOf<UUID>()
        val stoppedCallIds = mutableListOf<UUID>()

        override fun startRingingForegroundService(
            callId: UUID,
            payload: AlertPayload,
            launchFallbackActivity: Boolean
        ) {
            events.add("startFgs:$callId:fallback=$launchFallbackActivity")
            startedCallsWithFallbackFlag.add(callId to launchFallbackActivity)
        }

        override fun showConnectedNotification(callId: UUID, payload: AlertPayload) {
            events.add("connected:$callId")
            connectedCallIds.add(callId)
        }

        override fun stopForegroundService(callId: UUID) {
            events.add("stopFgs:$callId")
            stoppedCallIds.add(callId)
        }
    }

    private class FakeTelecomCallDriver(
        private val registerException: RuntimeException? = null
    ) : TelecomCallDriver {
        var registerAttempts = 0
        val timeline = mutableListOf<String>()
        val startedCallIds = mutableListOf<UUID>()
        val lastDisconnectReasonByCall = mutableMapOf<UUID, CallDisconnectReason>()

        var initialEndpoints: List<CallAudioEndpoint> = emptyList()
        var initialCurrentEndpoint: CallAudioEndpoint? = null
        var nextAnswerResult: CallOpResult = CallOpResult.Success
        var throwCancellationOnAnswer: Boolean = false
        val failingEndpointIds = mutableSetOf<String>()

        private val callbacksByCall = mutableMapOf<UUID, TelecomCallCallbacks>()
        private val disconnectsByCall = mutableMapOf<UUID, CompletableDeferred<CallDisconnectReason>>()

        override fun registerApp() {
            registerAttempts++
            timeline.add("registerApp")
            registerException?.let { throw it }
        }

        override suspend fun runIncomingCall(
            callId: UUID,
            payload: AlertPayload,
            callbacks: TelecomCallCallbacks
        ) {
            startedCallIds.add(callId)
            callbacksByCall[callId] = callbacks
            val done = CompletableDeferred<CallDisconnectReason>()
            disconnectsByCall[callId] = done

            if (initialEndpoints.isNotEmpty()) {
                callbacks.onAvailableEndpointsChanged(initialEndpoints)
            }
            if (initialCurrentEndpoint != null) {
                callbacks.onCurrentEndpointChanged(initialCurrentEndpoint)
            }

            val handle = object : TelecomCallHandle {
                override suspend fun answer(): CallOpResult {
                    if (throwCancellationOnAnswer) {
                        throw CancellationException("Simulated answer cancellation")
                    }
                    return nextAnswerResult
                }

                override suspend fun disconnect(reason: CallDisconnectReason): CallOpResult {
                    lastDisconnectReasonByCall[callId] = reason
                    done.complete(reason)
                    return CallOpResult.Success
                }

                override suspend fun setActive(): CallOpResult {
                    callbacks.onSetActive()
                    return CallOpResult.Success
                }

                override suspend fun setInactive(): CallOpResult {
                    callbacks.onSetInactive()
                    return CallOpResult.Success
                }

                override suspend fun requestEndpointChange(endpointId: String): CallOpResult {
                    if (endpointId in failingEndpointIds) {
                        return CallOpResult.Error(errorCode = 8, detail = "Endpoint unavailable")
                    }
                    val target = initialEndpoints.firstOrNull { it.id == endpointId }
                        ?: CallAudioEndpoint(endpointId, endpointId, AudioEndpointType.UNKNOWN)
                    callbacks.onCurrentEndpointChanged(target)
                    return CallOpResult.Success
                }
            }

            timeline.add("onCallRegistered:$callId")
            callbacks.onCallRegistered(handle)

            try {
                done.await()
            } finally {
                callbacksByCall.remove(callId)
                disconnectsByCall.remove(callId)
            }
        }

        suspend fun simulateRemoteAnswer(callId: UUID) {
            callbacksByCall[callId]?.onRemoteAnswer()
        }

        suspend fun simulateSetInactive(callId: UUID) {
            callbacksByCall[callId]?.onSetInactive()
        }

        suspend fun simulateSetActive(callId: UUID) {
            callbacksByCall[callId]?.onSetActive()
        }

        fun simulateMuteChanged(callId: UUID, isMuted: Boolean) {
            callbacksByCall[callId]?.onMutedChanged(isMuted)
        }

        suspend fun simulateRemoteDisconnect(callId: UUID, reason: CallDisconnectReason) {
            lastDisconnectReasonByCall[callId] = reason
            callbacksByCall[callId]?.onRemoteDisconnect(reason)
            disconnectsByCall[callId]?.complete(reason)
        }

        fun andBridgeTimeline(bridge: RecordingUiBridge): List<String> {
            return timeline + bridge.events
        }
    }
}
