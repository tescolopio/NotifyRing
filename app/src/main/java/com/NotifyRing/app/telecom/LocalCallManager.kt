package com.notifyring.app.telecom

import android.content.Context
import com.notifyring.app.NotifyRingApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single-flight alert call engine backed by `androidx.core:core-telecom` (with automatic fallback
 * to local FGS + `IncomingCallActivity` on devices lacking `FEATURE_TELECOM`).
 *
 * Processes queued [AlertCallRequest]s strictly one at a time via a bounded [Channel] actor,
 * assigns a unique [UUID] per call, enforces a configurable ring timeout (default 45s -> `MISSED`),
 * validates `callId` on all control operations, and exposes [sessionState] end-to-end.
 */
class LocalCallManager(
    private val telecomDriver: TelecomCallDriver,
    private val uiBridge: CallForegroundUiBridge,
    private val scope: CoroutineScope,
    val defaultRingTimeoutMs: Long = DEFAULT_RING_TIMEOUT_MS,
    maxQueueCapacity: Int = DEFAULT_MAX_QUEUE_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: CallDiagnosticLogger = AndroidCallDiagnosticLogger
) : CallAudioController {

    private val requestChannel = Channel<AlertCallRequest>(
        capacity = maxQueueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _sessionState = MutableStateFlow<CallSessionState>(CallSessionState.Idle)
    override val sessionState: StateFlow<CallSessionState> = _sessionState.asStateFlow()

    private val registrationMutex = Mutex()
    private var isTelecomRegistered = false
    private var useLocalFallback = false

    private val stateMutex = Mutex()
    private var activeSession: ActiveCallContext? = null

    private val listeners = CopyOnWriteArraySet<CallLifecycleListener>()

    private val actorJob: Job = scope.launch {
        for (request in requestChannel) {
            processCallRequest(request)
        }
    }

    private class ActiveCallContext(
        val callId: UUID,
        val payload: AlertPayload,
        val ringTimeoutMs: Long,
        val isFallback: Boolean
    ) {
        var handle: TelecomCallHandle? = null
        var ringTimeoutJob: Job? = null
        var currentEndpoint: CallAudioEndpoint? = null
        var availableEndpoints: List<CallAudioEndpoint> = emptyList()
        var isMuted: Boolean = false
        var wasAnswered: Boolean = false
        var terminalReason: CallDisconnectReason? = null
        var errorMessage: String? = null
        val completionSignal = CompletableDeferred<CallDisconnectReason>()
    }

    /**
     * Enqueues an incoming alert call for serial single-flight execution and returns its unique [UUID].
     */
    fun enqueueCall(payload: AlertPayload, ringTimeoutMs: Long? = null): UUID {
        val callId = UUID.randomUUID()
        enqueueRequest(
            AlertCallRequest(
                callId = callId,
                payload = payload,
                ringTimeoutMs = ringTimeoutMs
            )
        )
        return callId
    }

    fun enqueueRequest(request: AlertCallRequest) {
        requestChannel.trySend(request)
    }

    fun hasActiveCall(callId: UUID): Boolean {
        return _sessionState.value.activeCallIdOrNull == callId
    }

    internal fun launchExternalAction(block: suspend () -> Unit) {
        scope.launch {
            block()
        }
    }

    override fun addLifecycleListener(listener: CallLifecycleListener) {
        listeners.add(listener)
    }

    override fun removeLifecycleListener(listener: CallLifecycleListener) {
        listeners.remove(listener)
    }

    /**
     * Answers the currently ringing call matching [callId].
     * Ignores stale/unknown [callId]s and leaves state uncorrupted if Telecom returns an error.
     */
    suspend fun answer(callId: UUID): Boolean {
        val (ctx, handle) = stateMutex.withLock {
            val current = activeSession
            if (current == null || current.callId != callId || current.terminalReason != null) {
                return false
            }
            if (_sessionState.value is CallSessionState.Active) {
                return true
            }
            if (_sessionState.value !is CallSessionState.Ringing) {
                return false
            }
            val h = current.handle ?: return false
            current to h
        }

        val opResult = try {
            handle.answer()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CallOpResult.Error(-1, e.javaClass.simpleName)
        }

        return when (opResult) {
            CallOpResult.Success -> {
                transitionToActive(ctx)
                true
            }
            is CallOpResult.Error -> {
                logger.warn("Answer failed with code=${opResult.errorCode}")
                false
            }
        }
    }

    /**
     * Disconnects the active or ringing call matching [callId] with explicit [reason].
     * Ignores stale/unknown [callId]s.
     */
    suspend fun disconnect(
        callId: UUID,
        reason: CallDisconnectReason = CallDisconnectReason.LOCAL
    ): Boolean {
        val (ctx, handle) = stateMutex.withLock {
            val current = activeSession
            if (current == null || current.callId != callId) {
                return false
            }
            if (current.terminalReason == null) {
                current.terminalReason = reason
            }
            current.ringTimeoutJob?.cancel()
            current.ringTimeoutJob = null
            current to current.handle
        }

        if (handle != null) {
            val opResult = try {
                handle.disconnect(reason)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                CallOpResult.Error(-1, e.javaClass.simpleName)
            }
            if (opResult is CallOpResult.Error) {
                logger.warn("Disconnect reported error code=${opResult.errorCode}")
            }
        }

        ctx.completionSignal.complete(reason)
        return true
    }

    override suspend fun requestEndpointChange(callId: UUID, endpointId: String): Boolean {
        val handle = stateMutex.withLock {
            val current = activeSession
            if (current == null || current.callId != callId || current.terminalReason != null) {
                return false
            }
            current.handle ?: return false
        }

        val opResult = try {
            handle.requestEndpointChange(endpointId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CallOpResult.Error(-1, e.javaClass.simpleName)
        }

        return when (opResult) {
            CallOpResult.Success -> true
            is CallOpResult.Error -> {
                logger.warn("Endpoint switch failed with code=${opResult.errorCode}")
                false
            }
        }
    }

    fun shutdown() {
        requestChannel.close()
        actorJob.cancel()
    }

    private suspend fun ensureTelecomRegistered(): Boolean {
        return registrationMutex.withLock {
            if (useLocalFallback) return@withLock false
            if (isTelecomRegistered) return@withLock true
            try {
                telecomDriver.registerApp()
                isTelecomRegistered = true
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: UnsupportedOperationException) {
                logger.warn("Telecom unsupported; switching to local FGS fallback")
                useLocalFallback = true
                false
            } catch (e: SecurityException) {
                logger.warn("Telecom registration denied; switching to local FGS fallback")
                useLocalFallback = true
                false
            }
        }
    }

    private suspend fun processCallRequest(request: AlertCallRequest) {
        val telecomAvailable = ensureTelecomRegistered()
        val effectiveTimeoutMs = (request.ringTimeoutMs ?: defaultRingTimeoutMs).coerceAtLeast(1L)
        val initialCtx = ActiveCallContext(
            callId = request.callId,
            payload = request.payload,
            ringTimeoutMs = effectiveTimeoutMs,
            isFallback = !telecomAvailable
        )

        stateMutex.withLock {
            activeSession = initialCtx
        }

        var fgsStarted = false
        var activeCtx = initialCtx

        try {
            if (telecomAvailable) {
                try {
                    runTelecomSessionWithScope(
                        request = request,
                        ctx = activeCtx,
                        onForegroundStarted = { fgsStarted = true }
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: UnsupportedOperationException) {
                    if (!fgsStarted) {
                        logger.warn("addCall unsupported; retrying via local FGS fallback")
                        registrationMutex.withLock { useLocalFallback = true }
                        val fallbackCtx = ActiveCallContext(
                            callId = request.callId,
                            payload = request.payload,
                            ringTimeoutMs = effectiveTimeoutMs,
                            isFallback = true
                        )
                        stateMutex.withLock { activeSession = fallbackCtx }
                        activeCtx = fallbackCtx
                        runFallbackSessionWithScope(
                            ctx = fallbackCtx,
                            onForegroundStarted = { fgsStarted = true }
                        )
                    } else {
                        recordFailure(activeCtx, "Telecom unsupported during call")
                    }
                } catch (e: SecurityException) {
                    if (!fgsStarted) {
                        logger.warn("addCall SecurityException; retrying via local FGS fallback")
                        registrationMutex.withLock { useLocalFallback = true }
                        val fallbackCtx = ActiveCallContext(
                            callId = request.callId,
                            payload = request.payload,
                            ringTimeoutMs = effectiveTimeoutMs,
                            isFallback = true
                        )
                        stateMutex.withLock { activeSession = fallbackCtx }
                        activeCtx = fallbackCtx
                        runFallbackSessionWithScope(
                            ctx = fallbackCtx,
                            onForegroundStarted = { fgsStarted = true }
                        )
                    } else {
                        recordFailure(activeCtx, "Telecom security failure")
                    }
                }
            } else {
                runFallbackSessionWithScope(
                    ctx = activeCtx,
                    onForegroundStarted = { fgsStarted = true }
                )
            }
        } catch (ce: CancellationException) {
            // Clean up active session & FGS under NonCancellable and ALWAYS rethrow CancellationException!
            activeCtx.ringTimeoutJob?.cancel()
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (activeSession?.callId == request.callId) {
                        activeSession = null
                    }
                }
                runCatching { uiBridge.stopForegroundService(request.callId) }
                _sessionState.value = CallSessionState.Idle
            }
            throw ce
        } catch (e: Exception) {
            logger.warn("Call session failed: ${e.javaClass.simpleName}")
            recordFailure(activeCtx, e.javaClass.simpleName)
        } finally {
            activeCtx.ringTimeoutJob?.cancel()
            activeCtx.ringTimeoutJob = null
        }

        // Normal/error call termination teardown (not reached on CancellationException).
        val (finalReason, finalError, wasAnswered) = stateMutex.withLock {
            val reason = activeCtx.terminalReason ?: CallDisconnectReason.REMOTE
            val err = activeCtx.errorMessage
            val answered = activeCtx.wasAnswered
            if (activeSession?.callId == request.callId) {
                activeSession = null
            }
            Triple(reason, err, answered)
        }

        runCatching { uiBridge.stopForegroundService(request.callId) }

        _sessionState.value = CallSessionState.Ended(
            callId = request.callId,
            payload = request.payload,
            reason = finalReason,
            errorMessage = finalError
        )
        notifyListenersSafely {
            it.onCallEnded(request.callId, request.payload, finalReason, wasAnswered)
        }
    }

    private suspend fun runTelecomSessionWithScope(
        request: AlertCallRequest,
        ctx: ActiveCallContext,
        onForegroundStarted: () -> Unit
    ) = coroutineScope {
        val callbacks = createSessionCallbacks(
            sessionScope = this,
            getContext = { ctx },
            onForegroundStarted = onForegroundStarted
        )
        val driverJob = launch {
            telecomDriver.runIncomingCall(
                callId = request.callId,
                payload = request.payload,
                callbacks = callbacks
            )
        }
        val completionWatcher = launch {
            val reason = ctx.completionSignal.await()
            if (driverJob.isActive) {
                driverJob.joinOrCancelSafely()
            }
            if (ctx.terminalReason == null) {
                ctx.terminalReason = reason
            }
        }
        try {
            driverJob.join()
        } finally {
            completionWatcher.cancel()
            ctx.ringTimeoutJob?.cancel()
        }
    }

    private suspend fun runFallbackSessionWithScope(
        ctx: ActiveCallContext,
        onForegroundStarted: () -> Unit
    ) = coroutineScope {
        val callbacks = createSessionCallbacks(
            sessionScope = this,
            getContext = { ctx },
            onForegroundStarted = onForegroundStarted
        )
        try {
            runLocalFallbackSession(ctx, callbacks)
        } finally {
            ctx.ringTimeoutJob?.cancel()
        }
    }

    private suspend fun Job.joinOrCancelSafely() {
        // Allow structured coroutines inside driver to finish naturally; cancel only if hung.
        for (attempt in 0 until 3) {
            if (!isActive) return
            kotlinx.coroutines.yield()
        }
        if (isActive) {
            cancel()
        }
    }

    private suspend fun recordFailure(ctx: ActiveCallContext, message: String) {
        stateMutex.withLock {
            if (ctx.terminalReason == null) {
                ctx.terminalReason = CallDisconnectReason.ERROR
            }
            ctx.errorMessage = message
        }
    }

    private fun createSessionCallbacks(
        sessionScope: CoroutineScope,
        getContext: () -> ActiveCallContext,
        onForegroundStarted: () -> Unit
    ): TelecomCallCallbacks {
        return object : TelecomCallCallbacks {
            override suspend fun onCallRegistered(handle: TelecomCallHandle) {
                val ctx = getContext()
                val shouldStart = stateMutex.withLock {
                    if (activeSession?.callId != ctx.callId || ctx.terminalReason != null) {
                        false
                    } else {
                        ctx.handle = handle
                        _sessionState.value = CallSessionState.Ringing(
                            callId = ctx.callId,
                            payload = ctx.payload,
                            startedAtMs = clock(),
                            currentEndpoint = ctx.currentEndpoint,
                            availableEndpoints = ctx.availableEndpoints,
                            isMuted = ctx.isMuted
                        )
                        true
                    }
                }
                if (!shouldStart) {
                    runCatching { handle.disconnect(CallDisconnectReason.LOCAL) }
                    return
                }

                notifyListenersSafely { it.onCallRinging(ctx.callId, ctx.payload) }

                // Start CallForegroundService AFTER CallsManager.addCall registers the call.
                try {
                    uiBridge.startRingingForegroundService(
                        callId = ctx.callId,
                        payload = ctx.payload,
                        launchFallbackActivity = ctx.isFallback
                    )
                    onForegroundStarted()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: RuntimeException) {
                    logger.warn("Unable to start CallForegroundService: ${e.javaClass.simpleName}")
                    stateMutex.withLock {
                        ctx.errorMessage = e.javaClass.simpleName
                    }
                    disconnect(ctx.callId, CallDisconnectReason.ERROR)
                    return
                }

                // Start configurable ring timeout (cancelled on Answer or Disconnect).
                val timeoutJob = sessionScope.launch {
                    delay(ctx.ringTimeoutMs)
                    disconnect(ctx.callId, CallDisconnectReason.MISSED)
                }
                stateMutex.withLock {
                    if (ctx.wasAnswered || ctx.terminalReason != null) {
                        timeoutJob.cancel()
                    } else {
                        ctx.ringTimeoutJob = timeoutJob
                    }
                }
            }

            override suspend fun onRemoteAnswer() {
                transitionToActive(getContext())
            }

            override suspend fun onRemoteDisconnect(reason: CallDisconnectReason) {
                val ctx = getContext()
                stateMutex.withLock {
                    ctx.ringTimeoutJob?.cancel()
                    ctx.ringTimeoutJob = null
                    if (ctx.terminalReason == null) {
                        ctx.terminalReason = reason
                    }
                }
                ctx.completionSignal.complete(reason)
            }

            override suspend fun onSetActive() {
                val ctx = getContext()
                var resumed = false
                stateMutex.withLock {
                    if (activeSession?.callId != ctx.callId || ctx.terminalReason != null) return
                    val current = _sessionState.value
                    if (current is CallSessionState.Active && current.callId == ctx.callId && current.isHeld) {
                        _sessionState.value = current.copy(isHeld = false)
                        resumed = true
                    }
                }
                if (resumed) {
                    notifyListenersSafely { it.onCallResumed(ctx.callId, ctx.payload) }
                }
            }

            override suspend fun onSetInactive() {
                val ctx = getContext()
                var held = false
                stateMutex.withLock {
                    if (activeSession?.callId != ctx.callId || ctx.terminalReason != null) return
                    val current = _sessionState.value
                    if (current is CallSessionState.Active && current.callId == ctx.callId && !current.isHeld) {
                        _sessionState.value = current.copy(isHeld = true)
                        held = true
                    }
                }
                if (held) {
                    notifyListenersSafely { it.onCallHeld(ctx.callId, ctx.payload) }
                }
            }

            override fun onCurrentEndpointChanged(endpoint: CallAudioEndpoint?) {
                val ctx = getContext()
                ctx.currentEndpoint = endpoint
                val current = _sessionState.value
                when {
                    current is CallSessionState.Ringing && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(currentEndpoint = endpoint)
                    current is CallSessionState.Active && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(currentEndpoint = endpoint)
                }
            }

            override fun onAvailableEndpointsChanged(endpoints: List<CallAudioEndpoint>) {
                val ctx = getContext()
                ctx.availableEndpoints = endpoints
                val current = _sessionState.value
                when {
                    current is CallSessionState.Ringing && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(availableEndpoints = endpoints)
                    current is CallSessionState.Active && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(availableEndpoints = endpoints)
                }
            }

            override fun onMutedChanged(isMuted: Boolean) {
                val ctx = getContext()
                ctx.isMuted = isMuted
                val current = _sessionState.value
                when {
                    current is CallSessionState.Ringing && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(isMuted = isMuted)
                    current is CallSessionState.Active && current.callId == ctx.callId ->
                        _sessionState.value = current.copy(isMuted = isMuted)
                }
            }
        }
    }

    private suspend fun transitionToActive(ctx: ActiveCallContext) {
        val transitioned = stateMutex.withLock {
            if (activeSession?.callId != ctx.callId || ctx.terminalReason != null) {
                return
            }
            ctx.ringTimeoutJob?.cancel()
            ctx.ringTimeoutJob = null
            if (_sessionState.value is CallSessionState.Active) {
                false
            } else {
                ctx.wasAnswered = true
                _sessionState.value = CallSessionState.Active(
                    callId = ctx.callId,
                    payload = ctx.payload,
                    answeredAtMs = clock(),
                    currentEndpoint = ctx.currentEndpoint,
                    availableEndpoints = ctx.availableEndpoints,
                    isMuted = ctx.isMuted,
                    isHeld = false
                )
                true
            }
        }

        if (transitioned) {
            runCatching { uiBridge.showConnectedNotification(ctx.callId, ctx.payload) }
            notifyListenersSafely { it.onCallAnswered(ctx.callId, ctx.payload) }
        }
    }

    /**
     * Pure-Kotlin local call session used when the device lacks `FEATURE_TELECOM` or denies
     * `CallsManager.registerAppWithTelecom`. Coordinates FGS + `IncomingCallActivity` + timeout.
     */
    private suspend fun runLocalFallbackSession(
        ctx: ActiveCallContext,
        callbacks: TelecomCallCallbacks
    ) {
        val earpiece = CallAudioEndpoint(
            id = FALLBACK_EARPIECE_ID,
            name = "Earpiece",
            type = AudioEndpointType.EARPIECE
        )
        val speaker = CallAudioEndpoint(
            id = FALLBACK_SPEAKER_ID,
            name = "Speaker",
            type = AudioEndpointType.SPEAKER
        )
        val endpoints = listOf(earpiece, speaker)

        callbacks.onAvailableEndpointsChanged(endpoints)
        callbacks.onCurrentEndpointChanged(earpiece)

        val fallbackHandle = object : TelecomCallHandle {
            override suspend fun answer(): CallOpResult = CallOpResult.Success

            override suspend fun disconnect(reason: CallDisconnectReason): CallOpResult {
                ctx.completionSignal.complete(reason)
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
                    ?: return CallOpResult.Error(-1, "Unknown fallback endpoint")
                callbacks.onCurrentEndpointChanged(target)
                return CallOpResult.Success
            }
        }

        callbacks.onCallRegistered(fallbackHandle)
        val reason = ctx.completionSignal.await()
        callbacks.onRemoteDisconnect(reason)
    }

    private inline fun notifyListenersSafely(action: (CallLifecycleListener) -> Unit) {
        for (listener in listeners) {
            runCatching { action(listener) }
        }
    }

    companion object {
        const val DEFAULT_RING_TIMEOUT_MS: Long = 45_000L
        const val DEFAULT_MAX_QUEUE_CAPACITY: Int = 32

        internal const val FALLBACK_EARPIECE_ID = "fallback-earpiece"
        internal const val FALLBACK_SPEAKER_ID = "fallback-speaker"

        @Volatile
        private var sharedInstance: LocalCallManager? = null

        val instanceOrNull: LocalCallManager?
            get() = sharedInstance

        /**
         * Returns the process-wide [LocalCallManager], lazily creating a default Android-backed
         * instance until `AppContainer` registers a DI-managed instance via [setSharedInstance].
         */
        fun getInstance(
            context: Context,
            dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
        ): LocalCallManager {
            sharedInstance?.let { return it }
            val app = context.applicationContext as? NotifyRingApplication
            if (app != null && app.isContainerInitialized) {
                return app.container.localCallManager
            }
            return synchronized(this) {
                sharedInstance ?: run {
                    val appContext = context.applicationContext
                    LocalCallManager(
                        telecomDriver = AndroidCoreTelecomDriver(appContext),
                        uiBridge = AndroidCallForegroundUiBridge(appContext),
                        scope = CoroutineScope(SupervisorJob() + dispatcher)
                    ).also { sharedInstance = it }
                }
            }
        }

        /**
         * Allows `AppContainer` to install its configured singleton instance cleanly.
         */
        fun setSharedInstance(manager: LocalCallManager) {
            synchronized(this) {
                sharedInstance = manager
            }
        }

        /**
         * Backward-compatible entry point used by `AlertNotificationListenerService` and test triggers.
         */
        fun startIncomingCall(context: Context, payload: AlertPayload): UUID {
            return getInstance(context).enqueueCall(payload)
        }
    }
}
