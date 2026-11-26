package com.notifyring.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Installed TTS engine metadata returned by [VoiceCatalogProvider].
 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean
)

/**
 * Pure-Kotlin representation of an Android `android.speech.tts.Voice` for deterministic
 * JVM filtering, sorting, and display-label generation.
 */
data class RawVoiceDescriptor(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val isNetworkConnectionRequired: Boolean,
    val features: Set<String> = emptySet()
)

enum class VoiceQualityTier(val label: String) {
    VERY_HIGH("Very High Quality"),
    HIGH("High Quality"),
    NORMAL("Normal Quality"),
    LOW("Low Quality")
}

/**
 * Human-friendly offline voice option ready for display in the Voice & Speech picker.
 */
data class OfflineVoiceOption(
    val name: String,
    val localeTag: String,
    val localeDisplayName: String,
    val quality: Int,
    val qualityTier: VoiceQualityTier,
    val displayLabel: String
)

/**
 * Snapshot of installed TTS engines and installed offline voices for a queried engine.
 */
data class VoiceCatalogSnapshot(
    val engines: List<TtsEngineInfo> = emptyList(),
    val defaultEnginePackage: String? = null,
    val activeEnginePackage: String? = null,
    val offlineVoices: List<OfflineVoiceOption> = emptyList(),
    val isLoaded: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Pure-Kotlin filter and resolver for installed offline TTS voices.
 */
object VoiceCatalogFilter {
    /**
     * Matches `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED` without an Android framework import.
     */
    const val FEATURE_NOT_INSTALLED: String = "notInstalled"

    const val QUALITY_VERY_HIGH_THRESHOLD: Int = 500
    const val QUALITY_HIGH_THRESHOLD: Int = 400
    const val QUALITY_NORMAL_THRESHOLD: Int = 300

    fun isInstalledOfflineVoice(voice: RawVoiceDescriptor): Boolean {
        if (voice.name.isBlank()) return false
        if (voice.isNetworkConnectionRequired) return false
        return voice.features.none { feature ->
            feature.equals(FEATURE_NOT_INSTALLED, ignoreCase = true)
        }
    }

    fun qualityTierFor(quality: Int): VoiceQualityTier = when {
        quality >= QUALITY_VERY_HIGH_THRESHOLD -> VoiceQualityTier.VERY_HIGH
        quality >= QUALITY_HIGH_THRESHOLD -> VoiceQualityTier.HIGH
        quality >= QUALITY_NORMAL_THRESHOLD -> VoiceQualityTier.NORMAL
        else -> VoiceQualityTier.LOW
    }

    fun filterAndSort(
        rawVoices: Collection<RawVoiceDescriptor>,
        deviceLocale: Locale = Locale.getDefault()
    ): List<OfflineVoiceOption> {
        val eligible = rawVoices
            .asSequence()
            .filter(::isInstalledOfflineVoice)
            .distinctBy { it.name }
            .toList()

        val sorted = eligible.sortedWith(
            compareBy<RawVoiceDescriptor>(
                { localeMatchRank(it.localeTag, deviceLocale) },
                { -it.quality },
                { it.name }
            )
        )

        return sorted.mapIndexed { index, raw ->
            val tier = qualityTierFor(raw.quality)
            val parsedLocale = Locale.forLanguageTag(raw.localeTag)
            val localeDisplay = parsedLocale.getDisplayName(deviceLocale)
                .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?: raw.localeTag
            OfflineVoiceOption(
                name = raw.name,
                localeTag = raw.localeTag,
                localeDisplayName = localeDisplay,
                quality = raw.quality,
                qualityTier = tier,
                displayLabel = "Voice ${index + 1} — ${tier.label}"
            )
        }
    }

    fun resolveBestVoice(
        savedVoiceName: String?,
        availableVoices: List<OfflineVoiceOption>
    ): OfflineVoiceOption? {
        if (availableVoices.isEmpty()) return null
        val normalized = savedVoiceName?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized != null) {
            availableVoices.firstOrNull { it.name == normalized }?.let { return it }
        }
        return availableVoices.firstOrNull()
    }

    private fun localeMatchRank(localeTag: String, deviceLocale: Locale): Int {
        val voiceLocale = Locale.forLanguageTag(localeTag)
        val deviceLang = deviceLocale.language
        if (deviceLang.isBlank() || !voiceLocale.language.equals(deviceLang, ignoreCase = true)) {
            return 2
        }
        val deviceCountry = deviceLocale.country
        if (deviceCountry.isBlank() || voiceLocale.country.equals(deviceCountry, ignoreCase = true)) {
            return 0
        }
        return 1
    }
}

interface VoiceCatalogProvider {
    suspend fun refresh(enginePackage: String? = null): VoiceCatalogSnapshot
}

/**
 * Probes Android [TextToSpeech] using a fresh instance per refresh so newly downloaded
 * offline voice packs or newly installed TTS engines appear immediately without restarting the app.
 */
class AndroidVoiceCatalogProvider(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS
) : VoiceCatalogProvider {

    private val appContext: Context = context.applicationContext

    override suspend fun refresh(enginePackage: String?): VoiceCatalogSnapshot =
        withContext(ioDispatcher) {
            val requestedPackage = enginePackage?.trim()?.takeIf { it.isNotEmpty() }
            val primaryAttempt = probeEngine(requestedPackage)
            if (primaryAttempt != null) {
                return@withContext primaryAttempt
            }

            if (requestedPackage != null) {
                val fallbackAttempt = probeEngine(null)
                if (fallbackAttempt != null) {
                    return@withContext fallbackAttempt
                }
            }

            VoiceCatalogSnapshot(
                isLoaded = true,
                errorMessage = "Unable to query text-to-speech engine"
            )
        }

    private suspend fun probeEngine(enginePackage: String?): VoiceCatalogSnapshot? {
        var probe: TextToSpeech? = null
        try {
            val initResult = CompletableDeferred<Boolean>()
            val listener = TextToSpeech.OnInitListener { status ->
                initResult.complete(status == TextToSpeech.SUCCESS)
            }
            probe = if (enginePackage != null) {
                TextToSpeech(appContext, listener, enginePackage)
            } else {
                TextToSpeech(appContext, listener)
            }

            val ready = withTimeoutOrNull(initTimeoutMs) {
                initResult.await()
            } ?: false

            if (!ready) {
                return null
            }

            val defaultPkg = runCatching { probe.defaultEngine }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val engines = runCatching { probe.engines.orEmpty() }
                .getOrDefault(emptyList())
                .mapNotNull { info ->
                    val pkg = info.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val label = info.label?.trim()?.takeIf { it.isNotEmpty() } ?: pkg
                    TtsEngineInfo(
                        packageName = pkg,
                        label = label,
                        isDefault = pkg == defaultPkg
                    )
                }
                .distinctBy { it.packageName }
                .sortedWith(
                    compareBy<TtsEngineInfo>(
                        { !it.isDefault },
                        { it.label.lowercase(Locale.getDefault()) },
                        { it.packageName }
                    )
                )

            val rawVoices = runCatching { probe.voices.orEmpty() }
                .getOrDefault(emptyList())
                .map { voice ->
                    RawVoiceDescriptor(
                        name = voice.name.orEmpty(),
                        localeTag = voice.locale?.toLanguageTag().orEmpty(),
                        quality = voice.quality,
                        isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                        features = voice.features?.toSet().orEmpty()
                    )
                }

            val offlineVoices = VoiceCatalogFilter.filterAndSort(rawVoices)
            val effectiveActivePackage = enginePackage ?: defaultPkg

            return VoiceCatalogSnapshot(
                engines = engines,
                defaultEnginePackage = defaultPkg,
                activeEnginePackage = effectiveActivePackage,
                offlineVoices = offlineVoices,
                isLoaded = true,
                errorMessage = null
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: RuntimeException) {
            return null
        } finally {
            probe?.let { engine ->
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS: Long = 3_000L
    }
}
