package com.notifyring.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.createRepository(fileName: String = "settings.preferences_pb"): DataStoreSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempFolder.root, fileName) }
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun defaultUserSettingsMatchExpectedValues() = runTest {
        val repository = createRepository("defaults.preferences_pb")

        val settings = repository.settings.first()

        assertEquals(emptySet<String>(), settings.monitoredPackages)
        assertEquals(2_000L, settings.debounceWindowMs)
        assertEquals(45, settings.ringTimeoutSeconds)
        assertFalse(settings.autoSpeakerEnabled)
        assertEquals(0.85f, settings.speechRate, 0.0001f)
        assertFalse(settings.onboardingCompleted)
        assertNull(settings.caregiverPinHash)
        assertFalse(settings.hasCaregiverPin)
    }

    @Test
    fun mutatorsUpdateAndPersistSettings() = runTest {
        val file = File(tempFolder.root, "mutators.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        val repository = DataStoreSettingsRepository(dataStore)

        repository.setMonitoredPackages(setOf("com.whatsapp", "  com.google.android.apps.messaging  ", ""))
        repository.addMonitoredPackage("org.thoughtcrime.securesms")
        repository.removeMonitoredPackage("com.whatsapp")
        repository.setDebounceWindowMs(3_500L)
        repository.setRingTimeoutSeconds(60)
        repository.setAutoSpeakerEnabled(true)
        repository.setSpeechRate(1.25f)
        repository.setOnboardingCompleted(true)

        val reloaded = DataStoreSettingsRepository(dataStore).settings.first()

        assertEquals(
            setOf("com.google.android.apps.messaging", "org.thoughtcrime.securesms"),
            reloaded.monitoredPackages
        )
        assertEquals(3_500L, reloaded.debounceWindowMs)
        assertEquals(60, reloaded.ringTimeoutSeconds)
        assertTrue(reloaded.autoSpeakerEnabled)
        assertEquals(1.25f, reloaded.speechRate, 0.0001f)
        assertTrue(reloaded.onboardingCompleted)
        assertTrue(file.exists() && file.length() > 0L)
    }

    @Test
    fun mutatorsClampOutOfRangeValues() = runTest {
        val repository = createRepository("clamping.preferences_pb")

        repository.setDebounceWindowMs(-500L)
        repository.setRingTimeoutSeconds(5)
        repository.setSpeechRate(0.1f)

        val lowerClamped = repository.settings.first()
        assertEquals(UserSettings.MIN_DEBOUNCE_WINDOW_MS, lowerClamped.debounceWindowMs)
        assertEquals(UserSettings.MIN_RING_TIMEOUT_SECONDS, lowerClamped.ringTimeoutSeconds)
        assertEquals(UserSettings.MIN_SPEECH_RATE, lowerClamped.speechRate, 0.0001f)

        repository.setDebounceWindowMs(60_000L)
        repository.setRingTimeoutSeconds(999)
        repository.setSpeechRate(4.5f)

        val upperClamped = repository.settings.first()
        assertEquals(UserSettings.MAX_DEBOUNCE_WINDOW_MS, upperClamped.debounceWindowMs)
        assertEquals(UserSettings.MAX_RING_TIMEOUT_SECONDS, upperClamped.ringTimeoutSeconds)
        assertEquals(UserSettings.MAX_SPEECH_RATE, upperClamped.speechRate, 0.0001f)

        repository.setSpeechRate(Float.NaN)
        assertEquals(UserSettings.DEFAULT_SPEECH_RATE, repository.settings.first().speechRate, 0.0001f)
    }

    @Test
    fun caregiverPinSetVerifyAndClearWorkEndToEnd() = runTest {
        val repository = createRepository("pin.preferences_pb")

        assertFalse(repository.verifyCaregiverPin("1234"))

        repository.setCaregiverPin("1234")
        val withPin = repository.settings.first()
        assertNotNull(withPin.caregiverPinHash)
        assertTrue(withPin.hasCaregiverPin)
        assertTrue(repository.verifyCaregiverPin("1234"))
        assertFalse(repository.verifyCaregiverPin("4321"))

        repository.clearCaregiverPin()
        val cleared = repository.settings.first()
        assertNull(cleared.caregiverPinHash)
        assertFalse(cleared.hasCaregiverPin)
        assertFalse(repository.verifyCaregiverPin("1234"))
    }
}
