package com.notifyring.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface AlertCallLogStore {
    val entries: Flow<List<AlertCallLogEntry>>

    suspend fun record(entry: AlertCallLogEntry)
    suspend fun clear()

    companion object {
        const val MAX_ENTRIES: Int = 20
    }
}

class DataStoreAlertCallLogStore(
    private val dataStore: DataStore<Preferences>
) : AlertCallLogStore {

    override val entries: Flow<List<AlertCallLogEntry>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeEntries(preferences[Keys.ENTRIES])
        }

    override suspend fun record(entry: AlertCallLogEntry) {
        dataStore.edit { preferences ->
            val existing = decodeEntries(preferences[Keys.ENTRIES])
            val updated = (listOf(entry) + existing).take(AlertCallLogStore.MAX_ENTRIES)
            preferences[Keys.ENTRIES] = encodeEntries(updated)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.ENTRIES)
        }
    }

    private fun encodeEntries(items: List<AlertCallLogEntry>): String =
        items.joinToString(separator = RECORD_SEPARATOR) { encodeEntry(it) }

    private fun decodeEntries(raw: String?): List<AlertCallLogEntry> {
        if (raw.isNullOrEmpty()) {
            return emptyList()
        }
        return raw
            .split(RECORD_SEPARATOR)
            .asSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { decodeEntry(it) }
            .take(AlertCallLogStore.MAX_ENTRIES)
            .toList()
    }

    private fun encodeEntry(entry: AlertCallLogEntry): String = listOf(
        FORMAT_VERSION,
        encodeField(entry.id),
        entry.timestampMillis.toString(),
        encodeField(entry.packageName),
        encodeField(entry.appLabel),
        encodeField(entry.sender),
        encodeField(entry.messagePreview),
        entry.answered.toString(),
        encodeField(entry.disconnectReason)
    ).joinToString(separator = FIELD_SEPARATOR)

    private fun decodeEntry(line: String): AlertCallLogEntry? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != EXPECTED_FIELD_COUNT || parts[0] != FORMAT_VERSION) {
            return null
        }
        val id = decodeField(parts[1]) ?: return null
        val timestampMillis = parts[2].toLongOrNull() ?: return null
        val packageName = decodeField(parts[3]) ?: return null
        val appLabel = decodeField(parts[4]) ?: return null
        val sender = decodeField(parts[5]) ?: return null
        val messagePreview = decodeField(parts[6]) ?: return null
        val answered = parts[7].toBooleanStrictOrNull() ?: return null
        val disconnectReason = decodeField(parts[8]) ?: return null

        return AlertCallLogEntry(
            id = id,
            timestampMillis = timestampMillis,
            packageName = packageName,
            appLabel = appLabel,
            sender = sender,
            messagePreview = messagePreview,
            answered = answered,
            disconnectReason = disconnectReason
        )
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(encoded: String): String? =
        try {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }

    private object Keys {
        val ENTRIES = stringPreferencesKey("alert_call_log_entries")
    }

    private companion object {
        const val FORMAT_VERSION = "v1"
        const val RECORD_SEPARATOR = "\n"
        const val FIELD_SEPARATOR = "|"
        const val EXPECTED_FIELD_COUNT = 9
    }
}
