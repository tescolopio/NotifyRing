package com.notifyring.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.notifyring.app.data.AlertCallLogStore
import com.notifyring.app.data.DataStoreAlertCallLogStore
import com.notifyring.app.data.DataStoreSettingsRepository
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.speech.AndroidTextToSpeechAdapter
import com.notifyring.app.speech.CallSpeechEngine
import com.notifyring.app.telecom.AndroidCallForegroundUiBridge
import com.notifyring.app.telecom.AndroidCoreTelecomDriver
import com.notifyring.app.telecom.LocalCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class AppContainer(context: Context) {
    protected val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataStoreScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settingsDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { appContext.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) }
        )
    }

    private val alertCallLogDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { appContext.preferencesDataStoreFile(ALERT_CALL_LOG_DATASTORE_NAME) }
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(settingsDataStore)
    }

    val alertCallLogStore: AlertCallLogStore by lazy {
        DataStoreAlertCallLogStore(alertCallLogDataStore)
    }

    private val rawLocalCallManager: LocalCallManager by lazy {
        LocalCallManager(
            telecomDriver = AndroidCoreTelecomDriver(appContext),
            uiBridge = AndroidCallForegroundUiBridge(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ).also { manager ->
            LocalCallManager.setSharedInstance(manager)
        }
    }

    val callSpeechEngine: CallSpeechEngine by lazy {
        CallSpeechEngine(
            ttsAdapter = AndroidTextToSpeechAdapter(appContext),
            callAudioController = rawLocalCallManager,
            settingsRepository = settingsRepository,
            alertCallLogStore = alertCallLogStore
        ).also { engine ->
            rawLocalCallManager.addLifecycleListener(engine)
        }
    }

    val localCallManager: LocalCallManager by lazy {
        rawLocalCallManager.also {
            callSpeechEngine
        }
    }

    private companion object {
        const val SETTINGS_DATASTORE_NAME = "notifyring_settings"
        const val ALERT_CALL_LOG_DATASTORE_NAME = "notifyring_alert_call_log"
    }
}
