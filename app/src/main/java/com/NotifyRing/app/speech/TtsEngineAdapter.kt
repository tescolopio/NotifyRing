package com.notifyring.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure-Kotlin abstraction over text-to-speech synthesis so [CallSpeechEngine] can run
 * deterministically in pure-JVM unit tests without Android framework dependencies.
 */
interface TtsEngineAdapter {
    fun initialize(onReady: (Boolean) -> Unit)
    fun setSpeechRate(rate: Float)
    fun speak(
        utteranceId: String,
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String?) -> Unit = {}
    ): Boolean
    fun stop()
    fun shutdown()
}

/**
 * Default Android [TextToSpeech] implementation of [TtsEngineAdapter].
 *
 * Routes speech strictly through Core-Telecom's active voice communication channel via
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION] and [AudioAttributes.CONTENT_TYPE_SPEECH]
 * without ever mutating `AudioManager` speakerphone or communication device state directly.
 */
class AndroidTextToSpeechAdapter(
    context: Context
) : TtsEngineAdapter {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var initState: InitState = InitState.UNINITIALIZED
    private var engineGeneration: Long = 0L
    private var pendingSpeechRate: Float? = null

    private val pendingInitCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val utteranceCallbacks = ConcurrentHashMap<String, UtteranceCallbacks>()

    private data class UtteranceCallbacks(
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: (String?) -> Unit
    )

    private enum class InitState {
        UNINITIALIZED,
        INITIALIZING,
        READY
    }

    override fun initialize(onReady: (Boolean) -> Unit) {
        var immediateReady = false
        var shouldConstructEngine = false
        val generationSnapshot: Long

        synchronized(lock) {
            when (initState) {
                InitState.READY -> {
                    if (tts != null) {
                        immediateReady = true
                        generationSnapshot = engineGeneration
                    } else {
                        initState = InitState.INITIALIZING
                        engineGeneration += 1L
                        generationSnapshot = engineGeneration
                        pendingInitCallbacks.add(onReady)
                        shouldConstructEngine = true
                    }
                }
                InitState.INITIALIZING -> {
                    generationSnapshot = engineGeneration
                    pendingInitCallbacks.add(onReady)
                }
                InitState.UNINITIALIZED -> {
                    initState = InitState.INITIALIZING
                    engineGeneration += 1L
                    generationSnapshot = engineGeneration
                    pendingInitCallbacks.add(onReady)
                    shouldConstructEngine = true
                }
            }
        }

        if (immediateReady) {
            dispatchNonBlocking { onReady(true) }
            return
        }

        if (!shouldConstructEngine) {
            return
        }

        try {
            val createdEngine = TextToSpeech(appContext) { status ->
                onEngineInitCompleted(generationSnapshot, status == TextToSpeech.SUCCESS)
            }
            createdEngine.setOnUtteranceProgressListener(createProgressListener())
            var staleEngineToShutdown: TextToSpeech? = null
            synchronized(lock) {
                if (engineGeneration == generationSnapshot && initState != InitState.UNINITIALIZED) {
                    tts = createdEngine
                    if (initState == InitState.READY) {
                        configureEngineLocked(createdEngine)
                    }
                } else {
                    staleEngineToShutdown = createdEngine
                }
            }
            staleEngineToShutdown?.let { stale ->
                runCatching { stale.shutdown() }
            }
        } catch (_: RuntimeException) {
            onEngineInitCompleted(generationSnapshot, false)
        }
    }

    override fun setSpeechRate(rate: Float) {
        synchronized(lock) {
            pendingSpeechRate = rate
            val engine = tts
            if (initState == InitState.READY && engine != null) {
                runCatching { engine.setSpeechRate(rate) }
            }
        }
    }

    override fun speak(
        utteranceId: String,
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String?) -> Unit
    ): Boolean {
        val engine = synchronized(lock) {
            val current = tts
            if (initState != InitState.READY || current == null) {
                return false
            }
            configureEngineLocked(current)
            current
        }

        utteranceCallbacks[utteranceId] = UtteranceCallbacks(
            onStart = onStart,
            onDone = onDone,
            onError = onError
        )

        val speakResult = try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: RuntimeException) {
            TextToSpeech.ERROR
        }

        if (speakResult != TextToSpeech.SUCCESS) {
            utteranceCallbacks.remove(utteranceId)
            return false
        }
        return true
    }

    override fun stop() {
        utteranceCallbacks.clear()
        val engine = synchronized(lock) { tts }
        if (engine != null) {
            runCatching { engine.stop() }
        }
    }

    override fun shutdown() {
        utteranceCallbacks.clear()
        val callbacksToFail: List<(Boolean) -> Unit>
        val engineToShutdown: TextToSpeech?

        synchronized(lock) {
            engineGeneration += 1L
            initState = InitState.UNINITIALIZED
            engineToShutdown = tts
            tts = null
            callbacksToFail = pendingInitCallbacks.toList()
            pendingInitCallbacks.clear()
        }

        if (engineToShutdown != null) {
            runCatching { engineToShutdown.stop() }
            runCatching { engineToShutdown.shutdown() }
        }

        if (callbacksToFail.isNotEmpty()) {
            dispatchNonBlocking {
                for (callback in callbacksToFail) {
                    runCatching { callback(false) }
                }
            }
        }
    }

    private fun onEngineInitCompleted(generation: Long, success: Boolean) {
        val callbacksToNotify: List<(Boolean) -> Unit>
        var failedEngineToShutdown: TextToSpeech? = null

        synchronized(lock) {
            if (engineGeneration != generation) {
                return
            }
            if (success) {
                initState = InitState.READY
                tts?.let { configureEngineLocked(it) }
            } else {
                initState = InitState.UNINITIALIZED
                failedEngineToShutdown = tts
                tts = null
            }
            callbacksToNotify = pendingInitCallbacks.toList()
            pendingInitCallbacks.clear()
        }

        failedEngineToShutdown?.let { failed ->
            runCatching { failed.shutdown() }
        }

        if (callbacksToNotify.isNotEmpty()) {
            dispatchNonBlocking {
                for (callback in callbacksToNotify) {
                    runCatching { callback(success) }
                }
            }
        }
    }

    private fun configureEngineLocked(engine: TextToSpeech) {
        val voiceAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        runCatching { engine.setAudioAttributes(voiceAttributes) }
        pendingSpeechRate?.let { rate ->
            runCatching { engine.setSpeechRate(rate) }
        }
    }

    private fun createProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId ?: return
                val callbacks = utteranceCallbacks[id] ?: return
                // Marshal off the TTS Binder thread immediately without blocking.
                dispatchNonBlocking { callbacks.onStart() }
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                val callbacks = utteranceCallbacks.remove(id) ?: return
                dispatchNonBlocking { callbacks.onDone() }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                val id = utteranceId ?: return
                val callbacks = utteranceCallbacks.remove(id) ?: return
                dispatchNonBlocking { callbacks.onError(null) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val id = utteranceId ?: return
                val callbacks = utteranceCallbacks.remove(id) ?: return
                dispatchNonBlocking { callbacks.onError("TTS error code $errorCode") }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null) {
                    utteranceCallbacks.remove(utteranceId)
                }
            }
        }
    }

    private fun dispatchNonBlocking(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
