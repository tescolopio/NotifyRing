package com.notifyring.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlertCallLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.createStore(fileName: String = "alert_log.preferences_pb"): DataStoreAlertCallLogStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempFolder.root, fileName) }
        )
        return DataStoreAlertCallLogStore(dataStore)
    }

    private fun sampleEntry(index: Int): AlertCallLogEntry = AlertCallLogEntry(
        id = "alert-$index",
        timestampMillis = 1_700_000_000_000L + index,
        packageName = "com.example.messaging",
        appLabel = "Messages",
        sender = "Sender $index",
        messagePreview = "Preview $index",
        answered = index % 2 == 0,
        disconnectReason = if (index % 2 == 0) "LOCAL_HANGUP" else "TIMEOUT"
    )

    @Test
    fun storeStartsEmptyAndOrdersNewestFirst() = runTest {
        val store = createStore("ordering.preferences_pb")

        assertTrue(store.entries.first().isEmpty())

        val first = sampleEntry(1)
        val second = sampleEntry(2)
        val third = sampleEntry(3)

        store.record(first)
        store.record(second)
        store.record(third)

        assertEquals(listOf(third, second, first), store.entries.first())
    }

    @Test
    fun specialCharactersAndNewlinesRoundTripIntact() = runTest {
        val store = createStore("special_chars.preferences_pb")

        val tricky = AlertCallLogEntry(
            id = "id|with|pipes\nand\nnewlines",
            timestampMillis = 1_730_000_123_456L,
            packageName = "com.example.chat",
            appLabel = "Chat | Alerts\nPro",
            sender = "Dr. Smith | Clinic\r\nLine 2",
            messagePreview = "Reminder:\nTake meds at 8:00 PM | \"Urgent\" — \uD83D\uDC8A",
            answered = true,
            disconnectReason = "LOCAL|DONE\nOK"
        )

        store.record(tricky)

        val stored = store.entries.first()
        assertEquals(listOf(tricky), stored)
    }

    @Test
    fun storeCapsEntriesAtTwentyAndEvictsOldest() = runTest {
        val store = createStore("bounded_cap.preferences_pb")

        for (i in 1..25) {
            store.record(sampleEntry(i))
        }

        val stored = store.entries.first()
        assertEquals(AlertCallLogStore.MAX_ENTRIES, stored.size)
        assertEquals((25 downTo 6).map { "alert-$it" }, stored.map { it.id })
    }

    @Test
    fun clearRemovesAllEntries() = runTest {
        val store = createStore("clear.preferences_pb")

        store.record(sampleEntry(1))
        store.record(sampleEntry(2))
        assertEquals(2, store.entries.first().size)

        store.clear()

        assertTrue(store.entries.first().isEmpty())
    }
}
