package com.notifyring.app.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech

/**
 * Pure-Kotlin representation of an Android intent candidate for the TTS system settings bridge.
 */
data class BridgeIntentCandidate(
    val action: String,
    val packageName: String? = null
)

/**
 * Pure-Kotlin intent candidate builder and fallback runner so bridge resolution logic
 * can be unit-tested deterministically on the JVM.
 */
object TtsBridgeIntentPlanner {
    const val ACTION_INSTALL_TTS_DATA: String = "android.speech.tts.engine.INSTALL_TTS_DATA"
    const val ACTION_SYSTEM_TTS_SETTINGS: String = "com.android.settings.TTS_SETTINGS"
    const val ACTION_ACCESSIBILITY_SETTINGS: String = "android.settings.ACCESSIBILITY_SETTINGS"

    fun buildInstallVoiceDataCandidates(enginePackage: String?): List<BridgeIntentCandidate> {
        val normalized = enginePackage?.trim()?.takeIf { it.isNotEmpty() }
        return buildList {
            if (normalized != null) {
                add(
                    BridgeIntentCandidate(
                        action = ACTION_INSTALL_TTS_DATA,
                        packageName = normalized
                    )
                )
            }
            add(BridgeIntentCandidate(action = ACTION_INSTALL_TTS_DATA, packageName = null))
            add(BridgeIntentCandidate(action = ACTION_SYSTEM_TTS_SETTINGS, packageName = null))
            add(BridgeIntentCandidate(action = ACTION_ACCESSIBILITY_SETTINGS, packageName = null))
        }
    }

    fun buildSystemTtsSettingsCandidates(): List<BridgeIntentCandidate> = listOf(
        BridgeIntentCandidate(action = ACTION_SYSTEM_TTS_SETTINGS, packageName = null),
        BridgeIntentCandidate(action = ACTION_ACCESSIBILITY_SETTINGS, packageName = null)
    )

    fun launchFirstResolvable(
        candidates: List<BridgeIntentCandidate>,
        canResolve: (BridgeIntentCandidate) -> Boolean,
        launch: (BridgeIntentCandidate) -> Unit
    ): Boolean {
        for (candidate in candidates) {
            if (!canResolve(candidate)) continue
            val started = runCatching { launch(candidate) }.isSuccess
            if (started) {
                return true
            }
        }
        return false
    }
}

interface TtsSettingsBridge {
    fun openInstallVoiceData(context: Context, enginePackage: String?): Boolean
    fun openSystemTtsSettings(context: Context): Boolean
}

/**
 * Seamless bridge to Android's native TTS voice downloader and system TTS settings,
 * with safe `resolveActivity` + `runCatching` fallback chains across OEM devices.
 */
class AndroidTtsSettingsBridge : TtsSettingsBridge {

    override fun openInstallVoiceData(context: Context, enginePackage: String?): Boolean {
        val candidates = TtsBridgeIntentPlanner.buildInstallVoiceDataCandidates(enginePackage)
        return launchCandidates(context, candidates)
    }

    override fun openSystemTtsSettings(context: Context): Boolean {
        val candidates = TtsBridgeIntentPlanner.buildSystemTtsSettingsCandidates()
        return launchCandidates(context, candidates)
    }

    private fun launchCandidates(
        context: Context,
        candidates: List<BridgeIntentCandidate>
    ): Boolean {
        return TtsBridgeIntentPlanner.launchFirstResolvable(
            candidates = candidates,
            canResolve = { candidate ->
                val intent = toAndroidIntent(context, candidate)
                runCatching { intent.resolveActivity(context.packageManager) != null }
                    .getOrDefault(false)
            },
            launch = { candidate ->
                context.startActivity(toAndroidIntent(context, candidate))
            }
        )
    }

    private fun toAndroidIntent(
        context: Context,
        candidate: BridgeIntentCandidate
    ): Intent {
        val resolvedAction = when (candidate.action) {
            TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA ->
                TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            TtsBridgeIntentPlanner.ACTION_ACCESSIBILITY_SETTINGS ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> candidate.action
        }
        return Intent(resolvedAction).apply {
            candidate.packageName?.let { setPackage(it) }
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
