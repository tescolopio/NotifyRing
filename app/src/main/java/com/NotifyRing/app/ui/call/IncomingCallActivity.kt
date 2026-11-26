package com.notifyring.app.ui.call

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.notifyring.app.NotifyRingApplication
import com.notifyring.app.speech.CallSpeechEngine
import com.notifyring.app.speech.SpeechEngineState
import com.notifyring.app.telecom.CallDisconnectReason
import com.notifyring.app.telecom.CallForegroundService
import com.notifyring.app.telecom.CallSessionState
import com.notifyring.app.telecom.LocalCallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Full-screen lockscreen call UI for incoming and connected NotifyRing alert calls.
 *
 * Lifecycle & security invariants:
 * - Renders over the lockscreen via `setShowWhenLocked(true)` + `setTurnScreenOn(true)` and
 *   `FLAG_KEEP_SCREEN_ON`, without ever calling `KeyguardManager.requestDismissKeyguard()` on
 *   launch or during call controls.
 * - Dismisses keyguard **only** when the user explicitly taps **"Open <Source App>"**.
 * - Observes [LocalCallManager.sessionState] from [Lifecycle.State.CREATED] so terminal
 *   [CallSessionState.Ended] (or [CallSessionState.Idle] after a call was observed) invokes
 *   `finishAndRemoveTask()` even while the screen is off.
 * - Intercepts Back press while ringing or connected so accidental Back cannot orphan a call.
 */
class IncomingCallActivity : ComponentActivity() {

    private lateinit var localCallManager: LocalCallManager
    private var callSpeechEngine: CallSpeechEngine? = null

    private val localMutedFlow = MutableStateFlow(false)
    private val fallbackSpeechStateFlow = MutableStateFlow<SpeechEngineState>(SpeechEngineState.Idle)

    private val autoAnswerGuard = AutoAnswerGuard()
    private val terminalFinishTracker = CallTerminalFinishTracker()

    private var pendingAutoAnswerRequested: Boolean = false
    private var pendingAutoAnswerCallId: UUID? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Intentionally blocked while Ringing or Active so accidental Back cannot orphan a call.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        resolveDependencies()
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        handleIncomingIntent(intent)
        observeSessionLifecycle()

        val speechFlow: StateFlow<SpeechEngineState> =
            callSpeechEngine?.speechState ?: fallbackSpeechStateFlow

        setContent {
            val sessionState by localCallManager.sessionState.collectAsStateWithLifecycle()
            val speechState by speechFlow.collectAsStateWithLifecycle()
            val isLocalMuted by localMutedFlow.collectAsStateWithLifecycle()

            val uiState = remember(sessionState, speechState, isLocalMuted) {
                mapToIncomingCallUiState(
                    sessionState = sessionState,
                    speechState = speechState,
                    isLocalMuted = isLocalMuted
                )
            }

            IncomingCallScreen(
                uiState = uiState,
                onAnswer = { callId ->
                    lifecycleScope.launch {
                        localCallManager.answer(callId)
                    }
                },
                onDecline = { callId ->
                    lifecycleScope.launch {
                        localCallManager.disconnect(callId, CallDisconnectReason.REJECTED)
                    }
                },
                onRepeatMessage = {
                    handleRepeatMessage()
                },
                onToggleSpeaker = { callId, targetEndpoint ->
                    lifecycleScope.launch {
                        localCallManager.requestEndpointChange(callId, targetEndpoint.id)
                    }
                },
                onToggleMute = { callId ->
                    handleToggleMute(callId)
                },
                onOpenSourceApp = { packageName ->
                    openSourceAppWithKeyguardDismiss(packageName)
                },
                onEndCall = { callId ->
                    lifecycleScope.launch {
                        localCallManager.disconnect(callId, CallDisconnectReason.LOCAL)
                    }
                },
                onCloseFallback = {
                    finishAndRemoveTask()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun resolveDependencies() {
        val app = application as? NotifyRingApplication
        if (app != null && app.isContainerInitialized) {
            localCallManager = app.container.localCallManager
            callSpeechEngine = app.container.callSpeechEngine
        } else {
            localCallManager = LocalCallManager.getInstance(applicationContext)
            callSpeechEngine = null
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val requestedCallId = parseCallIdOrNull(
            intent.getStringExtra(CallForegroundService.EXTRA_CALL_ID)
        )
        terminalFinishTracker.updateExpectedCallId(requestedCallId)

        val autoAnswer = intent.getBooleanExtra(CallForegroundService.EXTRA_AUTO_ANSWER, false)
        if (autoAnswer) {
            pendingAutoAnswerRequested = true
            pendingAutoAnswerCallId = requestedCallId
            maybeTriggerPendingAutoAnswer(localCallManager.sessionState.value)
        }
    }

    private fun observeSessionLifecycle() {
        val initialState = localCallManager.sessionState.value
        backPressedCallback.isEnabled = shouldInterceptBackPress(initialState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                localCallManager.sessionState.collect { state ->
                    backPressedCallback.isEnabled = shouldInterceptBackPress(state)

                    if (state !is CallSessionState.Active) {
                        localMutedFlow.value = false
                    }
                    if (state is CallSessionState.Active || state is CallSessionState.Ended) {
                        pendingAutoAnswerRequested = false
                        pendingAutoAnswerCallId = null
                    }

                    maybeTriggerPendingAutoAnswer(state)

                    if (terminalFinishTracker.shouldFinish(state)) {
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }

    private fun maybeTriggerPendingAutoAnswer(state: CallSessionState) {
        val targetCallId = autoAnswerGuard.consumeTargetCallId(
            autoAnswerRequested = pendingAutoAnswerRequested,
            requestedCallId = pendingAutoAnswerCallId,
            sessionState = state
        ) ?: return

        pendingAutoAnswerRequested = false
        pendingAutoAnswerCallId = null

        lifecycleScope.launch {
            val answered = localCallManager.answer(targetCallId)
            if (!answered) {
                autoAnswerGuard.releaseOnFailure(targetCallId)
            }
        }
    }

    private fun handleToggleMute(callId: UUID) {
        val activeState = localCallManager.sessionState.value as? CallSessionState.Active
        if (activeState == null || activeState.callId != callId) return

        val nextMuted = !localMutedFlow.value
        localMutedFlow.value = nextMuted
        if (nextMuted) {
            callSpeechEngine?.onCallHeld(callId)
        } else if (!activeState.isHeld) {
            callSpeechEngine?.onCallResumed(callId)
        }
    }

    private fun handleRepeatMessage() {
        val activeState = localCallManager.sessionState.value as? CallSessionState.Active ?: return
        if (activeState.isHeld) return

        if (localMutedFlow.value) {
            localMutedFlow.value = false
            callSpeechEngine?.onCallResumed(activeState.callId)
        }
        callSpeechEngine?.repeatMessage()
    }

    private fun openSourceAppWithKeyguardDismiss(packageName: String) {
        if (packageName.isBlank()) return
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager != null && keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        launchSourceAppSafely(packageName)
                    }
                }
            )
        } else {
            launchSourceAppSafely(packageName)
        }
    }

    private fun launchSourceAppSafely(packageName: String) {
        runCatching {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return
            startActivity(launchIntent)
        }
    }
}
