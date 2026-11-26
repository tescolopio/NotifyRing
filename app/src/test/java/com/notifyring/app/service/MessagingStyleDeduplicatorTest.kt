package com.notifyring.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingStyleDeduplicatorTest {

    @Test
    fun emitsInitialMessagingStyleMessagesAndStoresMaxTimestamp() {
        val dedup = MessagingStyleDeduplicator()
        val initial = listOf(
            MessagingMessageCandidate(sender = "Mom", text = "Dinner is at 6", timestamp = 1_000L)
        )

        val result = dedup.filterNewMessagingMessages("0|com.whatsapp|1", initial)

        assertEquals(initial, result)
        assertEquals(1, dedup.size)
    }

    @Test
    fun emitsOnlyStrictlyNewerTimestampMessagesInCumulativeGroupChat() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.whatsapp|group-42"

        val post1 = listOf(
            MessagingMessageCandidate(sender = "Alice", text = "Hi everyone", timestamp = 1_000L),
            MessagingMessageCandidate(sender = "Bob", text = "On my way", timestamp = 1_500L)
        )
        assertEquals(2, dedup.filterNewMessagingMessages(key, post1).size)

        // Cumulative update carries Alice + Bob again plus Carol's new message:
        val post2 = post1 + MessagingMessageCandidate(
            sender = "Carol",
            text = "See you soon!",
            timestamp = 2_200L
        )
        val delta = dedup.filterNewMessagingMessages(key, post2)

        assertEquals(
            listOf(MessagingMessageCandidate(sender = "Carol", text = "See you soon!", timestamp = 2_200L)),
            delta
        )
    }

    @Test
    fun emitsMultipleNewMessagesSharingHigherTimestampInSingleUpdate() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.google.android.apps.messaging|7"

        dedup.filterNewMessagingMessages(
            key,
            listOf(MessagingMessageCandidate(sender = "Dad", text = "First", timestamp = 1_000L))
        )

        val burstUpdate = listOf(
            MessagingMessageCandidate(sender = "Dad", text = "First", timestamp = 1_000L),
            MessagingMessageCandidate(sender = "Dad", text = "Second", timestamp = 2_000L),
            MessagingMessageCandidate(sender = "Dad", text = "Third", timestamp = 2_000L)
        )
        val delta = dedup.filterNewMessagingMessages(key, burstUpdate)

        assertEquals(2, delta.size)
        assertEquals(listOf("Second", "Third"), delta.map { it.text })
    }

    @Test
    fun suppressesDuplicateMessagingStyleRepostWithSameOrOlderTimestamps() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.whatsapp|99"
        val messages = listOf(
            MessagingMessageCandidate(sender = "Doctor", text = "Appointment confirmed", timestamp = 5_000L)
        )

        assertEquals(1, dedup.filterNewMessagingMessages(key, messages).size)
        assertTrue(dedup.filterNewMessagingMessages(key, messages).isEmpty())

        val olderBackfill = listOf(
            MessagingMessageCandidate(sender = "Doctor", text = "Earlier note", timestamp = 4_000L),
            MessagingMessageCandidate(sender = "Doctor", text = "Appointment confirmed", timestamp = 5_000L)
        )
        assertTrue(dedup.filterNewMessagingMessages(key, olderBackfill).isEmpty())
    }

    @Test
    fun ignoresBlankMessagingStyleMessagesAndNormalizesWhitespace() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.whatsapp|blank-check"
        val raw = listOf(
            MessagingMessageCandidate(sender = "  Sister \n ", text = "   ", timestamp = 1_000L),
            MessagingMessageCandidate(sender = "  Sister  ", text = "  Call   me\nplease ", timestamp = 1_100L)
        )

        val result = dedup.filterNewMessagingMessages(key, raw)

        assertEquals(
            listOf(MessagingMessageCandidate(sender = "Sister", text = "Call me please", timestamp = 1_100L)),
            result
        )
    }

    @Test
    fun diffsCumulativeInboxStyleAppendedLines() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.google.android.gm|inbox"

        assertEquals(
            listOf("Prescription ready"),
            dedup.filterNewLines(key, listOf("Prescription ready"))
        )
        assertEquals(
            listOf("Pharmacy closes at 7 PM", "Bring ID card"),
            dedup.filterNewLines(
                key,
                listOf("Prescription ready", "Pharmacy closes at 7 PM", "Bring ID card")
            )
        )
    }

    @Test
    fun suppressesIdenticalInboxStyleRepost() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.google.android.gm|dup"
        val lines = listOf("Line A", "Line B")

        assertEquals(lines, dedup.filterNewLines(key, lines))
        assertTrue(dedup.filterNewLines(key, lines).isEmpty())
    }

    @Test
    fun preservesLegitimateRepeatedLineAppendedAtEnd() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|org.telegram.messenger|ping"

        assertEquals(listOf("Ring!"), dedup.filterNewLines(key, listOf("Ring!")))
        assertEquals(listOf("Ring!"), dedup.filterNewLines(key, listOf("Ring!", "Ring!")))
        assertTrue(dedup.filterNewLines(key, listOf("Ring!", "Ring!")).isEmpty())
    }

    @Test
    fun diffsSlidingWindowInboxStyleWhenOldestLineDropsOff() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.email|window"

        val window1 = listOf("Msg 1", "Msg 2", "Msg 3", "Msg 4", "Msg 5")
        val window2 = listOf("Msg 2", "Msg 3", "Msg 4", "Msg 5", "Msg 6")

        assertEquals(window1, dedup.filterNewLines(key, window1))
        assertEquals(listOf("Msg 6"), dedup.filterNewLines(key, window2))
    }

    @Test
    fun diffsTopPrependedInboxStyleLines() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.email|prepend"

        assertEquals(listOf("Older 1", "Older 2"), dedup.filterNewLines(key, listOf("Older 1", "Older 2")))
        assertEquals(
            listOf("Newest Alert"),
            dedup.filterNewLines(key, listOf("Newest Alert", "Older 1", "Older 2"))
        )
    }

    @Test
    fun diffsMultiLineExtraBigTextAndSingleLineReplacement() {
        val dedup = MessagingStyleDeduplicator()
        val key = "0|com.delivery|order"

        assertEquals(
            listOf("Order confirmed", "Preparing meal"),
            dedup.filterNewTextBlock(key, "Order confirmed\nPreparing meal")
        )
        assertEquals(
            listOf("Driver arriving now"),
            dedup.filterNewTextBlock(key, "Order confirmed\nPreparing meal\nDriver arriving now")
        )

        val singleLineKey = "0|com.ride|status"
        assertEquals(
            listOf("Arriving in 5 minutes"),
            dedup.filterNewTextBlock(singleLineKey, "Arriving in 5 minutes")
        )
        assertEquals(
            listOf("Your driver is outside"),
            dedup.filterNewTextBlock(singleLineKey, "Your driver is outside")
        )
    }

    @Test
    fun evictsLeastRecentlyUsedEntryAtMaxCapacity256() {
        val dedup = MessagingStyleDeduplicator()
        assertEquals(256, dedup.maxEntries)

        for (i in 0..256) {
            dedup.filterNewLines("key-$i", listOf("Alert $i"))
        }

        // Strict bound at 256 entries; key-0 was evicted while key-1..key-256 remain cached.
        assertEquals(256, dedup.size)
        assertTrue(dedup.filterNewLines("key-256", listOf("Alert 256")).isEmpty())
        assertTrue(dedup.filterNewLines("key-1", listOf("Alert 1")).isEmpty())
        assertEquals(listOf("Alert 0"), dedup.filterNewLines("key-0", listOf("Alert 0")))
    }

    @Test
    fun promotesAccessedKeyInAccessOrderLruBeforeEviction() {
        val dedup = MessagingStyleDeduplicator(maxEntries = 3)

        dedup.filterNewMessagingMessages(
            "k1",
            listOf(MessagingMessageCandidate(sender = "A", text = "One", timestamp = 100L))
        )
        dedup.filterNewMessagingMessages(
            "k2",
            listOf(MessagingMessageCandidate(sender = "B", text = "Two", timestamp = 200L))
        )
        dedup.filterNewMessagingMessages(
            "k3",
            listOf(MessagingMessageCandidate(sender = "C", text = "Three", timestamp = 300L))
        )

        // Touch k1 so k2 becomes least-recently-used.
        assertTrue(
            dedup.filterNewMessagingMessages(
                "k1",
                listOf(MessagingMessageCandidate(sender = "A", text = "One", timestamp = 100L))
            ).isEmpty()
        )

        // Inserting k4 must evict k2, keeping k1 cached!
        dedup.filterNewMessagingMessages(
            "k4",
            listOf(MessagingMessageCandidate(sender = "D", text = "Four", timestamp = 400L))
        )
        assertEquals(3, dedup.size)

        // k1 is still deduplicated:
        assertTrue(
            dedup.filterNewMessagingMessages(
                "k1",
                listOf(MessagingMessageCandidate(sender = "A", text = "One", timestamp = 100L))
            ).isEmpty()
        )
        // k2 was evicted so re-posting emits again:
        assertEquals(
            1,
            dedup.filterNewMessagingMessages(
                "k2",
                listOf(MessagingMessageCandidate(sender = "B", text = "Two", timestamp = 200L))
            ).size
        )
    }

    @Test
    fun removesIndividualKeyOnNotificationRemovedAndClearsAllOnReconnect() {
        val dedup = MessagingStyleDeduplicator()
        dedup.filterNewLines("chat-a", listOf("Hello"))
        dedup.filterNewLines("chat-b", listOf("World"))
        assertEquals(2, dedup.size)

        dedup.onNotificationRemoved("chat-a")
        assertEquals(1, dedup.size)
        assertEquals(listOf("Hello"), dedup.filterNewLines("chat-a", listOf("Hello")))
        assertTrue(dedup.filterNewLines("chat-b", listOf("World")).isEmpty())

        dedup.clear()
        assertEquals(0, dedup.size)
        assertEquals(listOf("World"), dedup.filterNewLines("chat-b", listOf("World")))
    }
}
