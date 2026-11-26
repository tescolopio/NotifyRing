package com.notifyring.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.notifyring.app.data.UserSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure-Kotlin abstraction over text-to-speech synthesis so [CallSpeechEngine] can run
 * deterministically in pure-JVM unit tests without Android framework dependencies.
 */
interface TtsEngineAdapter {
    fun initialize(enginePackage: String? = null, onReady: (Boolean) -> Unit)
    fun setVoice(voiceName: String?): Boolean = false
    fun setSpeechRate(rate: Float)
    fun setSpeechPitch(pitch: Float) {}
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
 * Routes call speech strictly through Core-Telecom's active voice communication channel via
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION] and [AudioAttributes.CONTENT_TYPE_SPEECH]
 * without ever mutating `AudioManager` speakerphone or communication device state directly.
 * Preview instances may pass [AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY] or
 * [AudioAttributes.USAGE_MEDIA] so previews never disturb active Telecom call audio routing.
 */
class AndroidTextToSpeechAdapter(
    context: Context,
    private val audioUsage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION
) : TtsEngineAdapter {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var initState: InitState = InitState.UNINITIALIZED
    private var engineGeneration: Long = 0L
    private var activeEnginePackage: String? = null
    private var pendingVoiceName: String? = null
    private var pendingSpeechRate: Float? = null
    private var pendingSpeechPitch: Float? = null

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

    override fun initialize(enginePackage: String?, onReady: (Boolean) -> Unit) {
        val requestedPackage = UserSettings.normalizeOptionalString(enginePackage)
        var immediateReady = false
        var shouldConstructEngine = false
        val generationSnapshot: Long
        var previousEngineToShutdown: TextToSpeech? = null
        var supersededCallbacks: List<(Boolean) -> Unit> = emptyList()

        synchronized(lock) {
            val sameEngine = activeEnginePackage == requestedPackage
            when (initState) {
                InitState.READY -> {
                    if (tts != null && sameEngine) {
                        immediateReady = true
                        generationSnapshot = engineGeneration
                    } else {
                        previousEngineToShutdown = tts
                        tts = null
                        activeEnginePackage = requestedPackage
                        initState = InitState.INITIALIZING
                        engineGeneration += 1L
                        generationSnapshot = engineGeneration
                        pendingInitCallbacks.add(onReady)
                        shouldConstructEngine = true
                    }
                }
                InitState.INITIALIZING -> {
                    if (sameEngine) {
                        generationSnapshot = engineGeneration
                        pendingInitCallbacks.add(onReady)
                    } else {
                        previousEngineToShutdown = tts
                        tts = null
                        supersededCallbacks = pendingInitCallbacks.toList()
                        pendingInitCallbacks.clear()
                        activeEnginePackage = requestedPackage
                        engineGeneration += 1L
                        generationSnapshot = engineGeneration
                        pendingInitCallbacks.add(onReady)
                        shouldConstructEngine = true
                    }
                }
                InitState.UNINITIALIZED -> {
                    activeEnginePackage = requestedPackage
                    initState = InitState.INITIALIZING
                    engineGeneration += 1L
                    generationSnapshot = engineGeneration
                    pendingInitCallbacks.add(onReady)
                    shouldConstructEngine = true
                }
            }
        }

        previousEngineToShutdown?.let { old ->
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
        if (supersededCallbacks.isNotEmpty()) {
            dispatchNonBlocking {
                for (callback in supersededCallbacks) {
                    runCatching { callback(false) }
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

        constructEngine(
            generationSnapshot = generationSnapshot,
            enginePackage = requestedPackage,
            allowFallbackToDefault = requestedPackage != null
        )
    }

    private fun constructEngine(
        generationSnapshot: Long,
        enginePackage: String?,
        allowFallbackToDefault: Boolean
    ) {
        try {
            val listener = TextToSpeech.OnInitListener { status ->
                onEngineInitCompleted(
                    generation = generationSnapshot,
                    success = status == TextToSpeech.SUCCESS,
                    allowFallbackToDefault = allowFallbackToDefault
                )
            }
            val createdEngine = if (enginePackage != null) {
                TextToSpeech(appContext, listener, enginePackage)
            } else {
                TextToSpeech(appContext, listener)
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
            onEngineInitCompleted(
                generation = generationSnapshot,
                success = false,
                allowFallbackToDefault = allowFallbackToDefault
            )
        }
    }

    override fun setVoice(voiceName: String?): Boolean {
        synchronized(lock) {
            pendingVoiceName = UserSettings.normalizeOptionalString(voiceName)
            val engine = tts
            return if (initState == InitState.READY && engine != null) {
                applyVoiceLocked(engine)
            } else {
                true
            }
        }
    }

    override fun setSpeechRate(rate: Float) {
        val clamped = UserSettings.clampSpeechRate(rate)
        synchronized(lock) {
            pendingSpeechRate = clamped
            val engine = tts
            if (initState == InitState.READY && engine != null) {
                runCatching { engine.setSpeechRate(clamped) }
            }
        }
    }

    override fun setSpeechPitch(pitch: Float) {
        val clamped = UserSettings.clampSpeechPitch(pitch)
        synchronized(lock) {
            pendingSpeechPitch = clamped
            val engine = tts
            if (initState == InitState.READY && engine != null) {
                runCatching { engine.setPitch(clamped) }
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
            activeEnginePackage = null
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

    private fun onEngineInitCompleted(
        generation: Long,
        success: Boolean,
        allowFallbackToDefault: Boolean
    ) {
        if (!success && allowFallbackToDefault) {
            var failedEngineToShutdown: TextToSpeech? = null
            var shouldRetryDefault = false
            synchronized(lock) {
                if (engineGeneration == generation && initState == InitState.INITIALIZING) {
                    failedEngineToShutdown = tts
                    tts = null
                    activeEnginePackage = null
                    shouldRetryDefault = true
                }
            }
            failedEngineToShutdown?.let { failed ->
                runCatching { failed.stop() }
                runCatching { failed.shutdown() }
            }
            if (shouldRetryDefault) {
                constructEngine(
                    generationSnapshot = generation,
                    enginePackage = null,
                    allowFallbackToDefault = false
                )
            }
            return
        }

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
                activeEnginePackage = null
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
            .setUsage(audioUsage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        runCatching { engine.setAudioAttributes(voiceAttributes) }
        pendingSpeechRate?.let { rate ->
            runCatching { engine.setSpeechRate(rate) }
        }
        pendingSpeechPitch?.let { pitch ->
            runCatching { engine.setPitch(pitch) }
        }
        applyVoiceLocked(engine)
    }

    private fun applyVoiceLocked(engine: TextToSpeech): Boolean {
        val androidVoices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptyList())
        if (androidVoices.isEmpty()) {
            return false
        }
        val rawDescriptors = androidVoices.map { voice ->
            RawVoiceDescriptor(
                name = voice.name.orEmpty(),
                localeTag = voice.locale?.toLanguageTag().orEmpty(),
                quality = voice.quality,
                isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                features = voice.features?.toSet().orEmpty()
            )
        }
        val offlineOptions = VoiceCatalogFilter.filterAndSort(rawDescriptors)
        val resolvedOption = VoiceCatalogFilter.resolveBestVoice(pendingVoiceName, offlineOptions)
            ?: return false
        val targetVoice = androidVoices.firstOrNull { it.name == resolvedOption.name }
            ?: return false
        return runCatching {
            engine.voice = targetVoice
            true
        }.getOrDefault(false)
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
