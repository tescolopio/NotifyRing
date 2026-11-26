package com.notifyring.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSettingsBridgeTest {

    @Test
    fun buildInstallVoiceDataCandidatesIncludesScopedEngineFirstWhenProvided() {
        val candidates = TtsBridgeIntentPlanner.buildInstallVoiceDataCandidates(
            "  com.google.android.tts  "
        )

        assertEquals(
            listOf(
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA,
                    packageName = "com.google.android.tts"
                ),
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA,
                    packageName = null
                ),
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_SYSTEM_TTS_SETTINGS,
                    packageName = null
                ),
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_ACCESSIBILITY_SETTINGS,
                    packageName = null
                )
            ),
            candidates
        )
    }

    @Test
    fun buildInstallVoiceDataCandidatesOmitsScopedCandidateWhenBlankOrNull() {
        val expected = listOf(
            BridgeIntentCandidate(
                action = TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA,
                packageName = null
            ),
            BridgeIntentCandidate(
                action = TtsBridgeIntentPlanner.ACTION_SYSTEM_TTS_SETTINGS,
                packageName = null
            ),
            BridgeIntentCandidate(
                action = TtsBridgeIntentPlanner.ACTION_ACCESSIBILITY_SETTINGS,
                packageName = null
            )
        )

        assertEquals(expected, TtsBridgeIntentPlanner.buildInstallVoiceDataCandidates(null))
        assertEquals(expected, TtsBridgeIntentPlanner.buildInstallVoiceDataCandidates("   "))
    }

    @Test
    fun buildSystemTtsSettingsCandidatesFallsBackToAccessibilitySettings() {
        val candidates = TtsBridgeIntentPlanner.buildSystemTtsSettingsCandidates()

        assertEquals(
            listOf(
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_SYSTEM_TTS_SETTINGS,
                    packageName = null
                ),
                BridgeIntentCandidate(
                    action = TtsBridgeIntentPlanner.ACTION_ACCESSIBILITY_SETTINGS,
                    packageName = null
                )
            ),
            candidates
        )
    }

    @Test
    fun launchFirstResolvableFallsBackAcrossUnresolvableAndThrowingCandidates() {
        val candidates = TtsBridgeIntentPlanner.buildInstallVoiceDataCandidates("com.samsung.SMT")
        val attemptedLaunches = mutableListOf<BridgeIntentCandidate>()

        val result = TtsBridgeIntentPlanner.launchFirstResolvable(
            candidates = candidates,
            canResolve = { candidate ->
                // Scoped intent does not resolve; unscoped INSTALL_TTS_DATA throws on launch;
                // TTS_SETTINGS succeeds.
                candidate.packageName == null
            },
            launch = { candidate ->
                attemptedLaunches += candidate
                if (candidate.action == TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA) {
                    throw SecurityException("OEM blocked direct install activity")
                }
            }
        )

        assertTrue(result)
        assertEquals(
            listOf(
                BridgeIntentCandidate(TtsBridgeIntentPlanner.ACTION_INSTALL_TTS_DATA, null),
                BridgeIntentCandidate(TtsBridgeIntentPlanner.ACTION_SYSTEM_TTS_SETTINGS, null)
            ),
            attemptedLaunches
        )
    }

    @Test
    fun launchFirstResolvableReturnsFalseWhenNoCandidateResolves() {
        val candidates = TtsBridgeIntentPlanner.buildSystemTtsSettingsCandidates()
        var launchCount = 0

        val result = TtsBridgeIntentPlanner.launchFirstResolvable(
            candidates = candidates,
            canResolve = { false },
            launch = { launchCount += 1 }
        )

        assertFalse(result)
        assertEquals(0, launchCount)
    }
}
