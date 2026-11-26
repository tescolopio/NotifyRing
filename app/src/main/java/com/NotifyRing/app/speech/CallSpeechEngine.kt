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
import com.notifyring.app.telecom.CallLifecycleListener
import com.notifyring.app.telecom.CallSessionState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Observable state of [CallSpeechEngine] across ringing warm-up, post-answer lead-in,
 * active TTS synthesis, cellular hold preemption, utterance completion, and errors.
 */
sealed interface SpeechEngineState {
    data object Idle : SpeechEngineState
    data class WarmingUp(val callId: UUID) : SpeechEngineState
    data class WaitingLeadIn(val callId: UUID) : SpeechEngineState
    data class Speaking(val callId: UUID, val script: String) : SpeechEngineState
    data class Paused(val callId: UUID) : SpeechEngineState
    data object Stopped : SpeechEngineState
    data class Error(val message: String) : SpeechEngineState
}

/**
 * Observable state of standalone voice previews triggered from the Voice & Speech screen,
 * strictly isolated from active Core-Telecom call speech state.
 */
sealed interface VoicePreviewState {
    data object Idle : VoicePreviewState
    data class Loading(val voiceName: String?) : VoicePreviewState
    data class Speaking(val voiceName: String?) : VoicePreviewState
    data class Error(val message: String) : VoicePreviewState
}

/**
 * Coordinates alert call speech synthesis over Core-Telecom voice channels:
 * - Warms up [TtsEngineAdapter] immediately on [onCallRinging] and gates playback behind a
 *   bounded [CompletableDeferred] (`initTimeoutMs = 3_000L`).
 * - Optionally switches earpiece audio to speaker on answer when `autoSpeakerEnabled == true`
 *   without ever overriding active Bluetooth or wired headsets.
 * - Inserts a cancellable `1_500L` ms post-answer lead-in pause before speaking with the saved
 *   engine, offline voice, `speechRate`, and `speechPitch`.
 * - Immediately stops playback on [onCallHeld] (`onSetInactive` cellular preemption).
 * - Supports [repeatMessage] replay, standalone isolated [previewVoice] / [stopPreview], and
 *   deterministic `stop()` + `shutdown()` cleanup on [onCallEnded].
 */
class CallSpeechEngine(
    private val ttsAdapter: TtsEngineAdapter,
    private val callAudioController: CallAudioController,
    private val settingsRepository: SettingsRepository,
    private val alertCallLogStore: AlertCallLogStore,
    private val formatter: SpeechScriptFormatter = SpeechScriptFormatter(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    val initTimeoutMs: Long = DEFAULT_INIT_TIMEOUT_MS,
    val leadInDelayMs: Long = DEFAULT_LEAD_IN_DELAY_MS,
    val endpointWaitTimeoutMs: Long = DEFAULT_ENDPOINT_WAIT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val previewAdapterFactory: (() -> TtsEngineAdapter)? = null
) : CallLifecycleListener {

    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _speechState = MutableStateFlow<SpeechEngineState>(SpeechEngineState.Idle)
    val speechState: StateFlow<SpeechEngineState> = _speechState.asStateFlow()

    private val _previewState = MutableStateFlow<VoicePreviewState>(VoicePreviewState.Idle)
    val previewState: StateFlow<VoicePreviewState> = _previewState.asStateFlow()

    private var activeCallId: UUID? = null
    private var activePayload: AlertPayload? = null
    private var activeScript: String? = null
    private var isAnswered: Boolean = false
    private var isHeld: Boolean = false
    private var hasRecordedSpokenAlert: Boolean = false

    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var warmedEnginePackage: String? = null
    private var playbackJob: Job? = null
    private var activeUtteranceId: String? = null
    private var utteranceSequence: Long = 0L

    private var previewJob: Job? = null
    private var previewAdapter: TtsEngineAdapter? = null
    private var previewGeneration: Long = 0L

    override fun onCallRinging(callId: UUID, payload: AlertPayload) {
        engineScope.launch {
            stopPreviewInternal()

            if (activeCallId != null && activeCallId != callId) {
                playbackJob?.cancel()
                playbackJob = null
                activeUtteranceId = null
                runCatching { ttsAdapter.stop() }
            }

            if (activeCallId != callId) {
                activeCallId = callId
                activePayload = payload
                activeScript = formatter.format(payload)
                isAnswered = false
                isHeld = false
                hasRecordedSpokenAlert = false
                activeUtteranceId = null
            }

            if (!isAnswered) {
                _speechState.value = SpeechEngineState.WarmingUp(callId)
            }
            val settings = readSettingsSafely()
            if (activeCallId == callId) {
                ensureWarmupStarted(settings.ttsEnginePackage)
            }
        }
    }

    override fun onCallAnswered(callId: UUID, payload: AlertPayload) {
        engineScope.launch {
            stopPreviewInternal()

            if (activeCallId != null && activeCallId != callId) {
                playbackJob?.cancel()
                playbackJob = null
                activeUtteranceId = null
                runCatching { ttsAdapter.stop() }
                hasRecordedSpokenAlert = false
            }

            if (activeCallId == callId && isAnswered) {
                return@launch
            }

            val script = formatter.format(payload)
            activeCallId = callId
            activePayload = payload
            activeScript = script
            isAnswered = true
            isHeld = (callAudioController.sessionState.value as? CallSessionState.Active)
                ?.takeIf { it.callId == callId }
                ?.isHeld
                ?: false
            activeUtteranceId = null

            if (isHeld) {
                _speechState.value = SpeechEngineState.Paused(callId)
                return@launch
            }

            playbackJob?.cancel()
            playbackJob = engineScope.launch {
                val initialSettings = readSettingsSafely()
                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                val deferred = ensureWarmupStarted(initialSettings.ttsEnginePackage)
                if (!deferred.isCompleted) {
                    _speechState.value = SpeechEngineState.WarmingUp(callId)
                }

                val initReady = withTimeoutOrNull(initTimeoutMs) {
                    deferred.await()
                }
                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                if (initReady != true) {
                    if (initDeferred === deferred) {
                        initDeferred = null
                        warmedEnginePackage = null
                    }
                    runCatching { ttsAdapter.stop() }
                    _speechState.value = SpeechEngineState.Error(
                        if (initReady == null) {
                            "TTS initialization timed out after ${initTimeoutMs}ms"
                        } else {
                            "TTS initialization failed"
                        }
                    )
                    return@launch
                }

                val settings = readSettingsSafely()
                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                _speechState.value = SpeechEngineState.WaitingLeadIn(callId)
                if (leadInDelayMs > 0L) {
                    coroutineScope {
                        if (settings.autoSpeakerEnabled) {
                            launch {
                                maybeRouteToSpeaker(
                                    callId = callId,
                                    waitTimeoutMs = minOf(endpointWaitTimeoutMs, leadInDelayMs)
                                )
                            }
                        }
                        delay(leadInDelayMs)
                    }
                } else if (settings.autoSpeakerEnabled) {
                    maybeRouteToSpeaker(callId = callId, waitTimeoutMs = endpointWaitTimeoutMs)
                }

                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                startUtterance(
                    callId = callId,
                    payload = payload,
                    script = script,
                    speechRate = settings.speechRate,
                    speechPitch = settings.speechPitch,
                    voiceName = settings.ttsVoiceName
                )
            }
        }
    }

    override fun onCallHeld(callId: UUID, payload: AlertPayload) {
        handleCallHeld(callId)
    }

    fun onCallHeld(callId: UUID) {
        handleCallHeld(callId)
    }

    private fun handleCallHeld(callId: UUID) {
        engineScope.launch {
            if (activeCallId != callId) {
                return@launch
            }
            isHeld = true
            activeUtteranceId = null
            playbackJob?.cancel()
            playbackJob = null
            runCatching { ttsAdapter.stop() }
            _speechState.value = SpeechEngineState.Paused(callId)
        }
    }

    override fun onCallResumed(callId: UUID, payload: AlertPayload) {
        handleCallResumed(callId)
    }

    fun onCallResumed(callId: UUID) {
        handleCallResumed(callId)
    }

    private fun handleCallResumed(callId: UUID) {
        engineScope.launch {
            if (activeCallId != callId) {
                return@launch
            }
            isHeld = false
            // Keep clean resume state ready for repeatMessage() without unexpectedly
            // restarting speech mid-conversation unless requested by the user.
        }
    }

    /**
     * Replays the currently active call's formatted script from the beginning at the
     * configured engine, voice, `speechRate`, and `speechPitch`. Ignored when no call
     * is active, unanswered, or held.
     */
    fun repeatMessage() {
        engineScope.launch {
            val callId = activeCallId ?: return@launch
            val payload = activePayload ?: return@launch
            val script = activeScript ?: return@launch
            if (!isAnswered || isHeld) {
                return@launch
            }

            activeUtteranceId = null
            playbackJob?.cancel()
            runCatching { ttsAdapter.stop() }

            playbackJob = engineScope.launch {
                val settings = readSettingsSafely()
                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                val deferred = ensureWarmupStarted(settings.ttsEnginePackage)
                if (!deferred.isCompleted) {
                    _speechState.value = SpeechEngineState.WarmingUp(callId)
                }

                val initReady = withTimeoutOrNull(initTimeoutMs) {
                    deferred.await()
                }
                if (activeCallId != callId || isHeld) {
                    return@launch
                }

                if (initReady != true) {
                    if (initDeferred === deferred) {
                        initDeferred = null
                        warmedEnginePackage = null
                    }
                    runCatching { ttsAdapter.stop() }
                    _speechState.value = SpeechEngineState.Error(
                        if (initReady == null) {
                            "TTS initialization timed out after ${initTimeoutMs}ms"
                        } else {
                            "TTS initialization failed"
                        }
                    )
                    return@launch
                }

                startUtterance(
                    callId = callId,
                    payload = payload,
                    script = script,
                    speechRate = settings.speechRate,
                    speechPitch = settings.speechPitch,
                    voiceName = settings.ttsVoiceName
                )
            }
        }
    }

    /**
     * Plays a standalone voice sample using an isolated preview [TtsEngineAdapter]
     * (never sharing or mutating active Core-Telecom call TTS state).
     */
    fun previewVoice(
        enginePackage: String?,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        sampleText: String = DEFAULT_PREVIEW_SAMPLE_TEXT
    ) {
        engineScope.launch {
            if (activeCallId != null) {
                stopPreviewInternal()
                return@launch
            }

            stopPreviewInternal()

            val factory = previewAdapterFactory
            if (factory == null) {
                _previewState.value = VoicePreviewState.Error("Voice preview unavailable")
                return@launch
            }

            val generation = ++previewGeneration
            val normalizedEngine = UserSettings.normalizeOptionalString(enginePackage)
            val normalizedVoice = UserSettings.normalizeOptionalString(voiceName)
            val effectiveRate = UserSettings.clampSpeechRate(rate)
            val effectivePitch = UserSettings.clampSpeechPitch(pitch)
            val textToSpeak = sampleText.trim().ifEmpty { DEFAULT_PREVIEW_SAMPLE_TEXT }

            _previewState.value = VoicePreviewState.Loading(normalizedVoice)

            val adapter = try {
                factory.invoke()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _previewState.value = VoicePreviewState.Error(
                    e.message ?: "Unable to start voice preview"
                )
                return@launch
            }
            previewAdapter = adapter

            previewJob = engineScope.launch {
                val initDeferredLocal = CompletableDeferred<Boolean>()
                try {
                    adapter.initialize(enginePackage = normalizedEngine) { ready ->
                        initDeferredLocal.complete(ready)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    initDeferredLocal.complete(false)
                }

                val ready = withTimeoutOrNull(initTimeoutMs) {
                    initDeferredLocal.await()
                }

                if (previewGeneration != generation || activeCallId != null) {
                    return@launch
                }

                if (ready != true) {
                    stopPreviewInternal()
                    _previewState.value = VoicePreviewState.Error(
                        if (ready == null) {
                            "Voice preview timed out"
                        } else {
                            "Voice preview initialization failed"
                        }
                    )
                    return@launch
                }

                runCatching { adapter.setVoice(normalizedVoice) }
                runCatching { adapter.setSpeechRate(effectiveRate) }
                runCatching { adapter.setSpeechPitch(effectivePitch) }

                val utteranceId = "preview-$generation"
                _previewState.value = VoicePreviewState.Speaking(normalizedVoice)

                val accepted = try {
                    adapter.speak(
                        utteranceId = utteranceId,
                        text = textToSpeak,
                        onStart = {
                            engineScope.launch {
                                if (previewGeneration == generation && activeCallId == null) {
                                    _previewState.value = VoicePreviewState.Speaking(normalizedVoice)
                                }
                            }
                        },
                        onDone = {
                            engineScope.launch {
                                if (previewGeneration == generation) {
                                    stopPreviewInternal()
                                }
                            }
                        },
                        onError = { detail ->
                            engineScope.launch {
                                if (previewGeneration == generation) {
                                    stopPreviewInternal()
                                    _previewState.value = VoicePreviewState.Error(
                                        detail ?: "Voice preview failed"
                                    )
                                }
                            }
                        }
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    stopPreviewInternal()
                    _previewState.value = VoicePreviewState.Error(
                        e.message ?: "Voice preview failed"
                    )
                    return@launch
                }

                if (!accepted) {
                    stopPreviewInternal()
                    _previewState.value = VoicePreviewState.Error("Voice preview rejected")
                }
            }
        }
    }

    /**
     * Immediately stops and releases any active voice preview without touching active call TTS.
     */
    fun stopPreview() {
        engineScope.launch {
            stopPreviewInternal()
        }
    }

    private fun stopPreviewInternal() {
        previewGeneration += 1L
        previewJob?.cancel()
        previewJob = null
        val adapter = previewAdapter
        previewAdapter = null
        if (adapter != null) {
            runCatching { adapter.stop() }
            runCatching { adapter.shutdown() }
        }
        _previewState.value = VoicePreviewState.Idle
    }

    override fun onCallEnded(
        callId: UUID,
        payload: AlertPayload,
        reason: CallDisconnectReason,
        answered: Boolean
    ) {
        handleCallEnded(callId)
    }

    fun onCallEnded(
        callId: UUID,
        reason: CallDisconnectReason = CallDisconnectReason.LOCAL
    ) {
        handleCallEnded(callId)
    }

    private fun handleCallEnded(callId: UUID) {
        engineScope.launch {
            if (activeCallId != callId) {
                return@launch
            }

            activeUtteranceId = null
            playbackJob?.cancel()
            playbackJob = null

            initDeferred?.cancel()
            initDeferred = null
            warmedEnginePackage = null

            activeCallId = null
            activePayload = null
            activeScript = null
            isAnswered = false
            isHeld = false
            hasRecordedSpokenAlert = false

            runCatching { ttsAdapter.stop() }
            runCatching { ttsAdapter.shutdown() }

            _speechState.value = SpeechEngineState.Idle
        }
    }

    fun close() {
        stopPreviewInternal()
        engineScope.cancel()
    }

    private fun ensureWarmupStarted(enginePackage: String? = null): CompletableDeferred<Boolean> {
        val normalizedEngine = UserSettings.normalizeOptionalString(enginePackage)
        val existing = initDeferred
        if (
            existing != null &&
            warmedEnginePackage == normalizedEngine &&
            (!existing.isCompleted || existing.completedSuccessfully())
        ) {
            return existing
        }

        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        warmedEnginePackage = normalizedEngine
        try {
            ttsAdapter.initialize(enginePackage = normalizedEngine) { ready ->
                deferred.complete(ready)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            deferred.complete(false)
        }
        return deferred
    }

    private fun CompletableDeferred<Boolean>.completedSuccessfully(): Boolean {
        if (!isCompleted || isCancelled) return false
        return runCatching {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            getCompleted()
        }.getOrDefault(false)
    }

    private suspend fun readSettingsSafely(): UserSettings {
        return try {
            settingsRepository.settings.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            UserSettings(
                autoSpeakerEnabled = false,
                speechRate = UserSettings.DEFAULT_SPEECH_RATE,
                speechPitch = UserSettings.DEFAULT_SPEECH_PITCH
            )
        }
    }

    private data class SessionEndpoints(
        val current: CallAudioEndpoint?,
        val available: List<CallAudioEndpoint>
    )

    private fun extractEndpointsForCall(
        state: CallSessionState,
        callId: UUID
    ): SessionEndpoints? = when (state) {
        is CallSessionState.Active ->
            if (state.callId == callId) {
                SessionEndpoints(state.currentEndpoint, state.availableEndpoints)
            } else {
                null
            }
        is CallSessionState.Ringing ->
            if (state.callId == callId) {
                SessionEndpoints(state.currentEndpoint, state.availableEndpoints)
            } else {
                null
            }
        CallSessionState.Idle, is CallSessionState.Ended -> null
    }

    private fun hasActionableEndpoints(endpoints: SessionEndpoints?): Boolean {
        val current = endpoints?.current ?: return false
        return current.type != AudioEndpointType.EARPIECE ||
            endpoints.available.any { it.type == AudioEndpointType.SPEAKER }
    }

    private suspend fun maybeRouteToSpeaker(callId: UUID, waitTimeoutMs: Long) {
        val immediate = extractEndpointsForCall(callAudioController.sessionState.value, callId)
        if (!hasActionableEndpoints(immediate) && waitTimeoutMs > 0L) {
            withTimeoutOrNull(waitTimeoutMs) {
                callAudioController.sessionState.first { state ->
                    hasActionableEndpoints(extractEndpointsForCall(state, callId))
                }
            }
        }

        val resolved = extractEndpointsForCall(callAudioController.sessionState.value, callId) ?: return
        val currentEndpoint = resolved.current ?: return

        // Switch to SPEAKER strictly when currently routed to EARPIECE;
        // NEVER override BLUETOOTH or WIRED_HEADSET!
        if (currentEndpoint.type != AudioEndpointType.EARPIECE) {
            return
        }

        val speakerEndpoint = resolved.available.firstOrNull {
            it.type == AudioEndpointType.SPEAKER
        } ?: return

        try {
            callAudioController.requestEndpointChange(callId, speakerEndpoint.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Continue speaking on the active route if Telecom endpoint switching fails.
        }
    }

    private fun startUtterance(
        callId: UUID,
        payload: AlertPayload,
        script: String,
        speechRate: Float,
        speechPitch: Float,
        voiceName: String?
    ) {
        val effectiveRate = UserSettings.clampSpeechRate(speechRate)
        val effectivePitch = UserSettings.clampSpeechPitch(speechPitch)
        runCatching { ttsAdapter.setVoice(voiceName) }
        runCatching { ttsAdapter.setSpeechRate(effectiveRate) }
        runCatching { ttsAdapter.setSpeechPitch(effectivePitch) }

        utteranceSequence += 1L
        val utteranceId = "call-$callId-$utteranceSequence"
        activeUtteranceId = utteranceId
        _speechState.value = SpeechEngineState.Speaking(callId, script)

        val accepted = try {
            ttsAdapter.speak(
                utteranceId = utteranceId,
                text = script,
                onStart = {
                    engineScope.launch {
                        if (activeCallId == callId && activeUtteranceId == utteranceId && !isHeld) {
                            _speechState.value = SpeechEngineState.Speaking(callId, script)
                        }
                    }
                },
                onDone = {
                    engineScope.launch {
                        if (activeCallId == callId && activeUtteranceId == utteranceId && !isHeld) {
                            activeUtteranceId = null
                            _speechState.value = SpeechEngineState.Stopped
                        }
                    }
                },
                onError = { detail ->
                    engineScope.launch {
                        if (activeCallId == callId && activeUtteranceId == utteranceId && !isHeld) {
                            activeUtteranceId = null
                            _speechState.value = SpeechEngineState.Error(
                                detail ?: "TTS playback failed"
                            )
                        }
                    }
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            activeUtteranceId = null
            _speechState.value = SpeechEngineState.Error(
                e.message ?: "TTS playback failed"
            )
            return
        }

        if (!accepted) {
            activeUtteranceId = null
            _speechState.value = SpeechEngineState.Error("TTS speak request rejected")
            return
        }

        if (!hasRecordedSpokenAlert) {
            hasRecordedSpokenAlert = true
            val entry = AlertCallLogEntry(
                id = callId.toString(),
                timestampMillis = clock(),
                packageName = payload.packageName,
                appLabel = payload.appName,
                sender = payload.sender,
                messagePreview = buildMessagePreview(payload.message, script),
                answered = true,
                disconnectReason = SPOKEN_LOG_DISCONNECT_REASON
            )
            engineScope.launch {
                recordSpokenAlertSafely(entry)
            }
        }
    }

    private suspend fun recordSpokenAlertSafely(entry: AlertCallLogEntry) {
        try {
            alertCallLogStore.record(entry)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Never fail active call speech if local log persistence fails.
        }
    }

    private fun buildMessagePreview(rawMessage: String, fallbackScript: String): String {
        val normalized = SpeechScriptFormatter.normalizeWhitespace(rawMessage)
        if (normalized.isEmpty()) {
            return SpeechScriptFormatter.truncateAtWordBoundary(fallbackScript, MAX_LOG_PREVIEW_CHARS)
        }
        return SpeechScriptFormatter.truncateAtWordBoundary(normalized, MAX_LOG_PREVIEW_CHARS)
    }

    companion object {
        const val DEFAULT_INIT_TIMEOUT_MS: Long = 3_000L
        const val DEFAULT_LEAD_IN_DELAY_MS: Long = 1_500L
        const val DEFAULT_ENDPOINT_WAIT_TIMEOUT_MS: Long = 500L
        const val DEFAULT_PREVIEW_SAMPLE_TEXT: String =
            "Hello. This is how NotifyRing will read your alert calls."

        private const val MAX_LOG_PREVIEW_CHARS: Int = 200
        private const val SPOKEN_LOG_DISCONNECT_REASON: String = "SPOKEN"
    }
}
