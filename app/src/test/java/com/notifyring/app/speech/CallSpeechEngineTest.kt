package com.notifyring.app.speech

import com.notifyring.app.data.AlertCallLogEntry
import com.notifyring.app.data.AlertCallLogStore
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.data.UserSettings
import com.notifyring.app.telecom.AlertPayload
import com.notifyring.app.telecom.AudioEndpointType
import com.notifyring.app.telecom.CallAudioController
import com.notifyring.app.telecom.CallAudioEndpoint
import com.notifyring.app.telecom.CallDisconnectReason
import com.notifyring.app.telecom.CallForegroundUiBridge
import com.notifyring.app.telecom.CallLifecycleListener
import com.notifyring.app.telecom.CallOpResult
import com.notifyring.app.telecom.CallSessionState
import com.notifyring.app.telecom.LocalCallManager
import com.notifyring.app.telecom.TelecomCallCallbacks
import com.notifyring.app.telecom.TelecomCallDriver
import com.notifyring.app.telecom.TelecomCallHandle
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallSpeechEngineTest {

    private val earpieceEndpoint = CallAudioEndpoint(
        id = "ep-earpiece",
        name = "Earpiece",
        type = AudioEndpointType.EARPIECE
    )
    private val speakerEndpoint = CallAudioEndpoint(
        id = "ep-speaker",
        name = "Speaker",
        type = AudioEndpointType.SPEAKER
    )
    private val bluetoothEndpoint = CallAudioEndpoint(
        id = "ep-bluetooth",
        name = "Hearing Aid BT",
        type = AudioEndpointType.BLUETOOTH
    )
    private val wiredEndpoint = CallAudioEndpoint(
        id = "ep-wired",
        name = "Wired Headset",
        type = AudioEndpointType.WIRED_HEADSET
    )

    private val samplePayload = AlertPayload(
        appName = "WhatsApp",
        packageName = "com.whatsapp",
        sender = "Dr. Patel",
        message = "Take your evening medication."
    )

    @Test
    fun `ringing warmup completes before answer and skips init wait on warm hit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = false)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository()
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        val callId = UUID.randomUUID()
        engine.onCallRinging(callId, samplePayload)
        runCurrent()

        // Warmup started immediately during ringing.
        assertEquals(1, tts.initializeCalls)
        assertEquals(SpeechEngineState.WarmingUp(callId), engine.speechState.value)

        // TTS finishes initializing while the phone is still ringing.
        tts.completeInit(true)
        runCurrent()

        // User answers -> warm hit proceeds immediately to WaitingLeadIn at 0ms without re-initializing.
        engine.onCallAnswered(callId, samplePayload)
        runCurrent()

        assertEquals(1, tts.initializeCalls)
        assertEquals(SpeechEngineState.WaitingLeadIn(callId), engine.speechState.value)
        assertTrue(tts.spokenUtterances.isEmpty())

        // After 1_500ms lead-in, speaks immediately.
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(1, tts.spokenUtterances.size)
        assertEquals(
            SpeechEngineState.Speaking(
                callId,
                "WhatsApp alert from Dr. Patel. Take your evening medication."
            ),
            engine.speechState.value
        )

        engine.onCallEnded(callId, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        engine.close()
    }

    @Test
    fun `delayed TTS init completing within 3s proceeds to lead-in and speaks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = false)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository()
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        val callId = UUID.randomUUID()
        engine.onCallRinging(callId, samplePayload)
        engine.onCallAnswered(callId, samplePayload)
        runCurrent()

        // Still waiting on CompletableDeferred init gate.
        assertEquals(SpeechEngineState.WarmingUp(callId), engine.speechState.value)

        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(SpeechEngineState.WarmingUp(callId), engine.speechState.value)

        // Init completes at 1.2s (< 3.0s timeout) -> enters 1.5s lead-in.
        tts.completeInit(true)
        runCurrent()
        assertEquals(SpeechEngineState.WaitingLeadIn(callId), engine.speechState.value)

        advanceTimeBy(1_499L)
        runCurrent()
        assertTrue(tts.spokenUtterances.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)

        engine.onCallEnded(callId, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        engine.close()
    }

    @Test
    fun `TTS init timeout over 3s and explicit init failure transition cleanly to Error without crashing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = false)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository()
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        // Case 1: >3_000ms timeout.
        val callId1 = UUID.randomUUID()
        engine.onCallRinging(callId1, samplePayload)
        engine.onCallAnswered(callId1, samplePayload)
        runCurrent()

        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(SpeechEngineState.WarmingUp(callId1), engine.speechState.value)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(engine.speechState.value is SpeechEngineState.Error)
        assertTrue(tts.spokenUtterances.isEmpty())

        engine.onCallEnded(callId1, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        assertEquals(SpeechEngineState.Idle, engine.speechState.value)

        // Case 2: Explicit init failure (onReady(false)).
        val callId2 = UUID.randomUUID()
        engine.onCallRinging(callId2, samplePayload)
        engine.onCallAnswered(callId2, samplePayload)
        runCurrent()

        tts.completeInit(false)
        runCurrent()
        assertTrue(engine.speechState.value is SpeechEngineState.Error)
        assertTrue(tts.spokenUtterances.isEmpty())

        engine.onCallEnded(callId2, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        engine.close()
    }

    @Test
    fun `1_5s post-answer lead-in speaks at 0_85f rate and cancels cleanly if held or ended during lead-in`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = true)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository(UserSettings()) // default speechRate == 0.85f
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        // Call 1: Full 1.5s lead-in -> speaks at 0.85f -> completes to Stopped on onDone.
        val callId1 = UUID.randomUUID()
        engine.onCallRinging(callId1, samplePayload)
        runCurrent()
        engine.onCallAnswered(callId1, samplePayload)
        runCurrent()

        assertEquals(SpeechEngineState.WaitingLeadIn(callId1), engine.speechState.value)
        advanceTimeBy(1_499L)
        runCurrent()
        assertTrue(tts.spokenUtterances.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertEquals(0.85f, tts.lastSpeechRate ?: 0f, 0.0001f)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)

        tts.completeCurrentUtteranceDone()
        runCurrent()
        assertEquals(SpeechEngineState.Stopped, engine.speechState.value)

        engine.onCallEnded(callId1, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // Call 2: Cancelled by onCallHeld at 700ms during lead-in -> never speaks.
        val callId2 = UUID.randomUUID()
        engine.onCallRinging(callId2, samplePayload)
        runCurrent()
        engine.onCallAnswered(callId2, samplePayload)
        runCurrent()

        advanceTimeBy(700L)
        runCurrent()
        engine.onCallHeld(callId2, samplePayload)
        runCurrent()
        assertEquals(SpeechEngineState.Paused(callId2), engine.speechState.value)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size) // Still only Call 1's utterance!

        engine.onCallEnded(callId2, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // Call 3: Cancelled by onCallEnded at 500ms during lead-in -> never speaks and resets to Idle.
        val callId3 = UUID.randomUUID()
        engine.onCallRinging(callId3, samplePayload)
        runCurrent()
        engine.onCallAnswered(callId3, samplePayload)
        runCurrent()

        advanceTimeBy(500L)
        runCurrent()
        engine.onCallEnded(callId3, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertEquals(SpeechEngineState.Idle, engine.speechState.value)

        engine.close()
    }

    @Test
    fun `auto-speaker switches ONLY from EARPIECE and never overrides BLUETOOTH or WIRED_HEADSET and handles errors`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = true)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository(
            UserSettings(autoSpeakerEnabled = true, speechRate = 0.9f)
        )
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        // 1) autoSpeakerEnabled == true + EARPIECE -> switches to SPEAKER.
        val callEar = UUID.randomUUID()
        audioController.setActiveCallEndpoints(
            callId = callEar,
            payload = samplePayload,
            current = earpieceEndpoint,
            available = listOf(earpieceEndpoint, speakerEndpoint)
        )
        engine.onCallRinging(callEar, samplePayload)
        engine.onCallAnswered(callEar, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf(callEar to speakerEndpoint.id), audioController.requestedChanges)
        assertEquals(1, tts.spokenUtterances.size)
        engine.onCallEnded(callEar, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // 2) autoSpeakerEnabled == true + BLUETOOTH -> MUST NOT switch!
        audioController.requestedChanges.clear()
        val callBt = UUID.randomUUID()
        audioController.setActiveCallEndpoints(
            callId = callBt,
            payload = samplePayload,
            current = bluetoothEndpoint,
            available = listOf(earpieceEndpoint, speakerEndpoint, bluetoothEndpoint)
        )
        engine.onCallRinging(callBt, samplePayload)
        engine.onCallAnswered(callBt, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()

        assertTrue(audioController.requestedChanges.isEmpty())
        assertEquals(2, tts.spokenUtterances.size)
        engine.onCallEnded(callBt, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // 3) autoSpeakerEnabled == true + WIRED_HEADSET -> MUST NOT switch!
        val callWired = UUID.randomUUID()
        audioController.setActiveCallEndpoints(
            callId = callWired,
            payload = samplePayload,
            current = wiredEndpoint,
            available = listOf(wiredEndpoint, speakerEndpoint)
        )
        engine.onCallRinging(callWired, samplePayload)
        engine.onCallAnswered(callWired, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()

        assertTrue(audioController.requestedChanges.isEmpty())
        assertEquals(3, tts.spokenUtterances.size)
        engine.onCallEnded(callWired, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // 4) autoSpeakerEnabled == false + EARPIECE -> MUST NOT switch!
        settingsRepo.setAutoSpeakerEnabled(false)
        val callDisabled = UUID.randomUUID()
        audioController.setActiveCallEndpoints(
            callId = callDisabled,
            payload = samplePayload,
            current = earpieceEndpoint,
            available = listOf(earpieceEndpoint, speakerEndpoint)
        )
        engine.onCallRinging(callDisabled, samplePayload)
        engine.onCallAnswered(callDisabled, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()

        assertTrue(audioController.requestedChanges.isEmpty())
        assertEquals(4, tts.spokenUtterances.size)
        engine.onCallEnded(callDisabled, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()

        // 5) Endpoint switch fails with CallOpResult.Error -> continues speaking gracefully on active route!
        settingsRepo.setAutoSpeakerEnabled(true)
        audioController.nextResult = CallOpResult.Error(errorCode = 5, detail = "BLUETOOTH_CONNECT denied")
        val callErr = UUID.randomUUID()
        audioController.setActiveCallEndpoints(
            callId = callErr,
            payload = samplePayload,
            current = earpieceEndpoint,
            available = listOf(earpieceEndpoint, speakerEndpoint)
        )
        engine.onCallRinging(callErr, samplePayload)
        engine.onCallAnswered(callErr, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf(callErr to speakerEndpoint.id), audioController.requestedChanges)
        assertEquals(5, tts.spokenUtterances.size)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)

        engine.onCallEnded(callErr, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        engine.close()
    }

    @Test
    fun `onCallHeld stops TTS immediately into Paused and repeatMessage replays only when active and unheld`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tts = FakeTtsEngineAdapter(autoCompleteInit = true)
        val audioController = FakeCallAudioController()
        val settingsRepo = FakeSettingsRepository(UserSettings(speechRate = 0.85f))
        val logStore = FakeAlertCallLogStore()

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = audioController,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )

        // repeatMessage() ignored while Idle.
        engine.repeatMessage()
        runCurrent()
        assertTrue(tts.spokenUtterances.isEmpty())

        val callId = UUID.randomUUID()
        engine.onCallRinging(callId, samplePayload)
        runCurrent()

        // repeatMessage() ignored while still Ringing (not answered yet).
        engine.repeatMessage()
        runCurrent()
        assertTrue(tts.spokenUtterances.isEmpty())

        // Answer and advance through 1.5s lead-in so TTS is actively speaking.
        engine.onCallAnswered(callId, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)

        val stopsBeforeHold = tts.stopCalls

        // Incoming cellular call preempts -> onCallHeld stops TTS immediately and sets Paused.
        engine.onCallHeld(callId, samplePayload)
        runCurrent()
        assertTrue(tts.stopCalls > stopsBeforeHold)
        assertEquals(SpeechEngineState.Paused(callId), engine.speechState.value)

        // repeatMessage() is ignored while call is held.
        engine.repeatMessage()
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertEquals(SpeechEngineState.Paused(callId), engine.speechState.value)

        // Cellular call finishes -> onCallResumed keeps Paused until user taps Repeat.
        engine.onCallResumed(callId, samplePayload)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)

        // User updates speech rate and taps Repeat -> replays immediately without duplicating call log.
        settingsRepo.setSpeechRate(1.15f)
        engine.repeatMessage()
        runCurrent()

        assertEquals(2, tts.spokenUtterances.size)
        assertEquals(1.15f, tts.lastSpeechRate ?: 0f, 0.0001f)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)
        assertEquals(1, logStore.entries.first().size)

        engine.onCallEnded(callId, samplePayload, CallDisconnectReason.LOCAL, answered = true)
        runCurrent()
        engine.close()
    }

    @Test
    fun `onCallEnded stops and shuts down TTS ignores stale callId records spoken log and integrates with LocalCallManager`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val driver = SimpleTelecomDriver(
            endpoints = listOf(earpieceEndpoint, speakerEndpoint),
            initialEndpoint = earpieceEndpoint
        )
        val uiBridge = NoOpUiBridge()
        val manager = LocalCallManager(
            telecomDriver = driver,
            uiBridge = uiBridge,
            scope = managerScope,
            clock = { 5_000L },
            logger = {}
        )

        val tts = FakeTtsEngineAdapter(autoCompleteInit = true)
        val settingsRepo = FakeSettingsRepository(
            UserSettings(autoSpeakerEnabled = true, speechRate = 0.85f)
        )
        val logStore = FakeAlertCallLogStore()
        var nowMs = 1_700_000_500_000L

        val engine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = manager,
            settingsRepository = settingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher,
            clock = { nowMs }
        )
        manager.addLifecycleListener(engine)

        // Call 1: declined while ringing -> never spoken, must NOT record spoken alert log, shuts down cleanly.
        val declinedPayload = AlertPayload("Signal", "org.thoughtcrime.securesms", "Alex", "Ping")
        val declinedId = manager.enqueueCall(declinedPayload)
        runCurrent()
        assertEquals(SpeechEngineState.WarmingUp(declinedId), engine.speechState.value)

        assertTrue(manager.disconnect(declinedId, CallDisconnectReason.REJECTED))
        runCurrent()
        assertEquals(SpeechEngineState.Idle, engine.speechState.value)
        assertEquals(1, tts.shutdownCalls)
        assertTrue(logStore.entries.first().isEmpty())

        // Call 2: answered, auto-switched from EARPIECE -> SPEAKER, spoken after 1.5s, logged in AlertCallLogStore.
        val spokenId = manager.enqueueCall(samplePayload)
        runCurrent()
        assertEquals(SpeechEngineState.WarmingUp(spokenId), engine.speechState.value)

        // Stale onCallEnded from previous/unknown callId must NOT tear down active Call 2!
        engine.onCallEnded(declinedId, declinedPayload, CallDisconnectReason.REJECTED, answered = false)
        engine.onCallEnded(UUID.randomUUID(), CallDisconnectReason.LOCAL)
        runCurrent()
        assertEquals(SpeechEngineState.WarmingUp(spokenId), engine.speechState.value)
        assertEquals(1, tts.shutdownCalls)

        assertTrue(manager.answer(spokenId))
        runCurrent()
        assertEquals(SpeechEngineState.WaitingLeadIn(spokenId), engine.speechState.value)
        assertEquals(
            AudioEndpointType.SPEAKER,
            (manager.sessionState.value as CallSessionState.Active).currentEndpoint?.type
        )

        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, tts.spokenUtterances.size)
        assertTrue(engine.speechState.value is SpeechEngineState.Speaking)

        // Verify AlertCallLogStore recorded the spoken alert accurately.
        val loggedEntries = logStore.entries.first()
        assertEquals(1, loggedEntries.size)
        val entry = loggedEntries.single()
        assertEquals(spokenId.toString(), entry.id)
        assertEquals(nowMs, entry.timestampMillis)
        assertEquals("com.whatsapp", entry.packageName)
        assertEquals("WhatsApp", entry.appLabel)
        assertEquals("Dr. Patel", entry.sender)
        assertEquals("Take your evening medication.", entry.messagePreview)
        assertTrue(entry.answered)

        // End Call 2 -> deterministic stop() + shutdown() and Idle state.
        val stopsBeforeEnd = tts.stopCalls
        assertTrue(manager.disconnect(spokenId, CallDisconnectReason.LOCAL))
        runCurrent()

        assertTrue(tts.stopCalls > stopsBeforeEnd)
        assertEquals(2, tts.shutdownCalls)
        assertEquals(SpeechEngineState.Idle, engine.speechState.value)

        // Settings failure fallback check: IOException in settings.first() still speaks at 0.85f.
        val failingSettingsRepo = object : SettingsRepository by settingsRepo {
            override val settings: Flow<UserSettings> = flow {
                throw IOException("Simulated disk error")
            }
        }
        val resilientEngine = CallSpeechEngine(
            ttsAdapter = tts,
            callAudioController = FakeCallAudioController(),
            settingsRepository = failingSettingsRepo,
            alertCallLogStore = logStore,
            dispatcher = dispatcher
        )
        val fallbackCallId = UUID.randomUUID()
        resilientEngine.onCallRinging(fallbackCallId, samplePayload)
        resilientEngine.onCallAnswered(fallbackCallId, samplePayload)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(2, tts.spokenUtterances.size)
        assertEquals(0.85f, tts.lastSpeechRate ?: 0f, 0.0001f)

        resilientEngine.onCallEnded(fallbackCallId, CallDisconnectReason.LOCAL)
        runCurrent()
        resilientEngine.close()
        engine.close()
        manager.shutdown()
        managerScope.cancel()
    }

    private class FakeTtsEngineAdapter(
        private val autoCompleteInit: Boolean = true
    ) : TtsEngineAdapter {
        var initializeCalls: Int = 0
        var stopCalls: Int = 0
        var shutdownCalls: Int = 0
        var lastSpeechRate: Float? = null
        val spokenUtterances = mutableListOf<Pair<String, String>>()

        private var isReady: Boolean = false
        private val initCallbacks = mutableListOf<(Boolean) -> Unit>()
        private var currentOnDone: (() -> Unit)? = null
        private var currentOnError: ((String?) -> Unit)? = null

        override fun initialize(onReady: (Boolean) -> Unit) {
            initializeCalls += 1
            if (isReady) {
                onReady(true)
                return
            }
            if (autoCompleteInit) {
                isReady = true
                onReady(true)
            } else {
                initCallbacks.add(onReady)
            }
        }

        fun completeInit(ready: Boolean) {
            isReady = ready
            val callbacks = initCallbacks.toList()
            initCallbacks.clear()
            callbacks.forEach { it(ready) }
        }

        override fun setSpeechRate(rate: Float) {
            lastSpeechRate = rate
        }

        override fun speak(
            utteranceId: String,
            text: String,
            onStart: () -> Unit,
            onDone: () -> Unit,
            onError: (String?) -> Unit
        ): Boolean {
            if (!isReady) return false
            spokenUtterances.add(utteranceId to text)
            currentOnDone = onDone
            currentOnError = onError
            onStart()
            return true
        }

        fun completeCurrentUtteranceDone() {
            val done = currentOnDone
            currentOnDone = null
            currentOnError = null
            done?.invoke()
        }

        override fun stop() {
            stopCalls += 1
            currentOnDone = null
            currentOnError = null
        }

        override fun shutdown() {
            shutdownCalls += 1
            isReady = false
            initCallbacks.clear()
            currentOnDone = null
            currentOnError = null
        }
    }

    private class FakeCallAudioController : CallAudioController {
        private val _sessionState = MutableStateFlow<CallSessionState>(CallSessionState.Idle)
        override val sessionState: StateFlow<CallSessionState> = _sessionState.asStateFlow()

        private val listeners = CopyOnWriteArraySet<CallLifecycleListener>()
        val requestedChanges = mutableListOf<Pair<UUID, String>>()
        var nextResult: CallOpResult = CallOpResult.Success

        fun setActiveCallEndpoints(
            callId: UUID,
            payload: AlertPayload,
            current: CallAudioEndpoint?,
            available: List<CallAudioEndpoint>,
            isHeld: Boolean = false
        ) {
            _sessionState.value = CallSessionState.Active(
                callId = callId,
                payload = payload,
                answeredAtMs = 1_000L,
                currentEndpoint = current,
                availableEndpoints = available,
                isMuted = false,
                isHeld = isHeld
            )
        }

        override suspend fun requestEndpointChange(callId: UUID, endpointId: String): Boolean {
            requestedChanges.add(callId to endpointId)
            return when (nextResult) {
                CallOpResult.Success -> {
                    val current = _sessionState.value
                    if (current is CallSessionState.Active && current.callId == callId) {
                        val target = current.availableEndpoints.firstOrNull { it.id == endpointId }
                        if (target != null) {
                            _sessionState.value = current.copy(currentEndpoint = target)
                        }
                    }
                    true
                }
                is CallOpResult.Error -> false
            }
        }

        override fun addLifecycleListener(listener: CallLifecycleListener) {
            listeners.add(listener)
        }

        override fun removeLifecycleListener(listener: CallLifecycleListener) {
            listeners.remove(listener)
        }
    }

    private class FakeSettingsRepository(
        initial: UserSettings = UserSettings()
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UserSettings> = state.asStateFlow()

        override suspend fun setMonitoredPackages(packages: Set<String>) {
            state.value = state.value.copy(monitoredPackages = packages)
        }

        override suspend fun addMonitoredPackage(packageName: String) {
            state.value = state.value.copy(monitoredPackages = state.value.monitoredPackages + packageName)
        }

        override suspend fun removeMonitoredPackage(packageName: String) {
            state.value = state.value.copy(monitoredPackages = state.value.monitoredPackages - packageName)
        }

        override suspend fun setDebounceWindowMs(debounceWindowMs: Long) {
            state.value = state.value.copy(
                debounceWindowMs = UserSettings.clampDebounceWindowMs(debounceWindowMs)
            )
        }

        override suspend fun setRingTimeoutSeconds(ringTimeoutSeconds: Int) {
            state.value = state.value.copy(
                ringTimeoutSeconds = UserSettings.clampRingTimeoutSeconds(ringTimeoutSeconds)
            )
        }

        override suspend fun setAutoSpeakerEnabled(enabled: Boolean) {
            state.value = state.value.copy(autoSpeakerEnabled = enabled)
        }

        override suspend fun setSpeechRate(speechRate: Float) {
            state.value = state.value.copy(speechRate = UserSettings.clampSpeechRate(speechRate))
        }

        override suspend fun setOnboardingCompleted(completed: Boolean) {
            state.value = state.value.copy(onboardingCompleted = completed)
        }

        override suspend fun setCaregiverPin(pin: String) {
            state.value = state.value.copy(caregiverPinHash = "hash:$pin")
        }

        override suspend fun clearCaregiverPin() {
            state.value = state.value.copy(caregiverPinHash = null)
        }

        override suspend fun verifyCaregiverPin(pin: String): Boolean {
            return state.value.caregiverPinHash == "hash:$pin"
        }
    }

    private class FakeAlertCallLogStore : AlertCallLogStore {
        private val state = MutableStateFlow<List<AlertCallLogEntry>>(emptyList())
        override val entries: Flow<List<AlertCallLogEntry>> = state.asStateFlow()

        override suspend fun record(entry: AlertCallLogEntry) {
            state.value = (listOf(entry) + state.value).take(AlertCallLogStore.MAX_ENTRIES)
        }

        override suspend fun clear() {
            state.value = emptyList()
        }
    }

    private class NoOpUiBridge : CallForegroundUiBridge {
        override fun startRingingForegroundService(
            callId: UUID,
            payload: AlertPayload,
            launchFallbackActivity: Boolean
        ) = Unit

        override fun showConnectedNotification(callId: UUID, payload: AlertPayload) = Unit
        override fun stopForegroundService(callId: UUID) = Unit
    }

    private class SimpleTelecomDriver(
        private val endpoints: List<CallAudioEndpoint>,
        private val initialEndpoint: CallAudioEndpoint
    ) : TelecomCallDriver {
        override fun registerApp() = Unit

        override suspend fun runIncomingCall(
            callId: UUID,
            payload: AlertPayload,
            callbacks: TelecomCallCallbacks
        ) {
            val done = CompletableDeferred<CallDisconnectReason>()
            callbacks.onAvailableEndpointsChanged(endpoints)
            callbacks.onCurrentEndpointChanged(initialEndpoint)
            callbacks.onCallRegistered(object : TelecomCallHandle {
                override suspend fun answer(): CallOpResult = CallOpResult.Success

                override suspend fun disconnect(reason: CallDisconnectReason): CallOpResult {
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
                    val target = endpoints.firstOrNull { it.id == endpointId }
                        ?: return CallOpResult.Error(-1, "Not found")
                    callbacks.onCurrentEndpointChanged(target)
                    return CallOpResult.Success
                }
            })
            done.await()
        }
    }
}
