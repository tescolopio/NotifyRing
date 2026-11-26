package com.notifyring.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setMonitoredPackages(packages: Set<String>)
    suspend fun addMonitoredPackage(packageName: String)
    suspend fun removeMonitoredPackage(packageName: String)
    suspend fun setDebounceWindowMs(debounceWindowMs: Long)
    suspend fun setRingTimeoutSeconds(ringTimeoutSeconds: Int)
    suspend fun setAutoSpeakerEnabled(enabled: Boolean)
    suspend fun setSpeechRate(speechRate: Float)
    suspend fun setSpeechPitch(pitch: Float)
    suspend fun setTtsEnginePackage(enginePackage: String?)
    suspend fun setTtsVoiceName(voiceName: String?)
    suspend fun setTtsSelection(enginePackage: String?, voiceName: String?)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setCaregiverPin(pin: String)
    suspend fun clearCaregiverPin()
    suspend fun verifyCaregiverPin(pin: String): Boolean
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            UserSettings(
                monitoredPackages = preferences[Keys.MONITORED_PACKAGES]
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet(),
                debounceWindowMs = UserSettings.clampDebounceWindowMs(
                    preferences[Keys.DEBOUNCE_WINDOW_MS] ?: UserSettings.DEFAULT_DEBOUNCE_WINDOW_MS
                ),
                ringTimeoutSeconds = UserSettings.clampRingTimeoutSeconds(
                    preferences[Keys.RING_TIMEOUT_SECONDS] ?: UserSettings.DEFAULT_RING_TIMEOUT_SECONDS
                ),
                autoSpeakerEnabled = preferences[Keys.AUTO_SPEAKER_ENABLED] ?: false,
                speechRate = UserSettings.clampSpeechRate(
                    preferences[Keys.SPEECH_RATE] ?: UserSettings.DEFAULT_SPEECH_RATE
                ),
                speechPitch = UserSettings.clampSpeechPitch(
                    preferences[Keys.SPEECH_PITCH] ?: UserSettings.DEFAULT_SPEECH_PITCH
                ),
                ttsEnginePackage = UserSettings.normalizeOptionalString(
                    preferences[Keys.TTS_ENGINE_PACKAGE]
                ),
                ttsVoiceName = UserSettings.normalizeOptionalString(
                    preferences[Keys.TTS_VOICE_NAME]
                ),
                onboardingCompleted = preferences[Keys.ONBOARDING_COMPLETED] ?: false,
                caregiverPinHash = preferences[Keys.CAREGIVER_PIN_HASH]
            )
        }

    override suspend fun setMonitoredPackages(packages: Set<String>) {
        val sanitized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        dataStore.edit { preferences ->
            preferences[Keys.MONITORED_PACKAGES] = sanitized
        }
    }

    override suspend fun addMonitoredPackage(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) {
            return
        }
        dataStore.edit { preferences ->
            val current = preferences[Keys.MONITORED_PACKAGES] ?: emptySet()
            preferences[Keys.MONITORED_PACKAGES] = current + normalized
        }
    }

    override suspend fun removeMonitoredPackage(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) {
            return
        }
        dataStore.edit { preferences ->
            val current = preferences[Keys.MONITORED_PACKAGES] ?: emptySet()
            preferences[Keys.MONITORED_PACKAGES] = current - normalized
        }
    }

    override suspend fun setDebounceWindowMs(debounceWindowMs: Long) {
        dataStore.edit { preferences ->
            preferences[Keys.DEBOUNCE_WINDOW_MS] = UserSettings.clampDebounceWindowMs(debounceWindowMs)
        }
    }

    override suspend fun setRingTimeoutSeconds(ringTimeoutSeconds: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.RING_TIMEOUT_SECONDS] = UserSettings.clampRingTimeoutSeconds(ringTimeoutSeconds)
        }
    }

    override suspend fun setAutoSpeakerEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.AUTO_SPEAKER_ENABLED] = enabled
        }
    }

    override suspend fun setSpeechRate(speechRate: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.SPEECH_RATE] = UserSettings.clampSpeechRate(speechRate)
        }
    }

    override suspend fun setSpeechPitch(pitch: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.SPEECH_PITCH] = UserSettings.clampSpeechPitch(pitch)
        }
    }

    override suspend fun setTtsEnginePackage(enginePackage: String?) {
        val normalized = UserSettings.normalizeOptionalString(enginePackage)
        dataStore.edit { preferences ->
            if (normalized == null) {
                preferences.remove(Keys.TTS_ENGINE_PACKAGE)
            } else {
                preferences[Keys.TTS_ENGINE_PACKAGE] = normalized
            }
        }
    }

    override suspend fun setTtsVoiceName(voiceName: String?) {
        val normalized = UserSettings.normalizeOptionalString(voiceName)
        dataStore.edit { preferences ->
            if (normalized == null) {
                preferences.remove(Keys.TTS_VOICE_NAME)
            } else {
                preferences[Keys.TTS_VOICE_NAME] = normalized
            }
        }
    }

    override suspend fun setTtsSelection(enginePackage: String?, voiceName: String?) {
        val normalizedEngine = UserSettings.normalizeOptionalString(enginePackage)
        val normalizedVoice = UserSettings.normalizeOptionalString(voiceName)
        dataStore.edit { preferences ->
            if (normalizedEngine == null) {
                preferences.remove(Keys.TTS_ENGINE_PACKAGE)
            } else {
                preferences[Keys.TTS_ENGINE_PACKAGE] = normalizedEngine
            }
            if (normalizedVoice == null) {
                preferences.remove(Keys.TTS_VOICE_NAME)
            } else {
                preferences[Keys.TTS_VOICE_NAME] = normalizedVoice
            }
        }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    override suspend fun setCaregiverPin(pin: String) {
        val hash = PinHasher.hash(pin)
        dataStore.edit { preferences ->
            preferences[Keys.CAREGIVER_PIN_HASH] = hash
        }
    }

    override suspend fun clearCaregiverPin() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.CAREGIVER_PIN_HASH)
        }
    }

    override suspend fun verifyCaregiverPin(pin: String): Boolean {
        val storedHash = settings.first().caregiverPinHash
        return PinHasher.verify(pin, storedHash)
    }

    private object Keys {
        val MONITORED_PACKAGES = stringSetPreferencesKey("monitored_packages")
        val DEBOUNCE_WINDOW_MS = longPreferencesKey("debounce_window_ms")
        val RING_TIMEOUT_SECONDS = intPreferencesKey("ring_timeout_seconds")
        val AUTO_SPEAKER_ENABLED = booleanPreferencesKey("auto_speaker_enabled")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val CAREGIVER_PIN_HASH = stringPreferencesKey("caregiver_pin_hash")
    }
}
