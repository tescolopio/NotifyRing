package com.notifyring.app.ui.home

import com.notifyring.app.data.AlertCallLogEntry
import com.notifyring.app.ui.onboarding.DegradedPermissionIssue
import com.notifyring.app.ui.onboarding.PermissionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeReplayHelperTest {

    @Test
    fun buildReplayPayload_preservesAndTrimsEntryFields() {
        val entry = AlertCallLogEntry(
            id = "log-1",
            timestampMillis = 1_700_000_000_000L,
            packageName = "  epic.mychart.android  ",
            appLabel = "  MyChart  ",
            sender = "  Dr. Patel  ",
            messagePreview = "  Prescription ready for pickup.  ",
            answered = true,
            disconnectReason = "LOCAL"
        )

        val payload = HomeReplayHelper.buildReplayPayload(entry)

        assertEquals("MyChart", payload.appName)
        assertEquals("epic.mychart.android", payload.packageName)
        assertEquals("Dr. Patel", payload.sender)
        assertEquals("Prescription ready for pickup.", payload.message)
    }

    @Test
    fun buildReplayPayload_appliesSafeFallbacksForBlankFields() {
        val blankSenderEntry = AlertCallLogEntry(
            id = "log-2",
            timestampMillis = 1_700_000_000_000L,
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            sender = "   ",
            messagePreview = "",
            answered = false,
            disconnectReason = "MISSED"
        )

        val payload1 = HomeReplayHelper.buildReplayPayload(blankSenderEntry)
        assertEquals("WhatsApp", payload1.appName)
        assertEquals("com.whatsapp", payload1.packageName)
        assertEquals("WhatsApp", payload1.sender)
        assertEquals(HomeReplayHelper.DEFAULT_REPLAY_MESSAGE, payload1.message)

        val allBlankEntry = blankSenderEntry.copy(appLabel = "  ", packageName = "  ")
        val payload2 = HomeReplayHelper.buildReplayPayload(allBlankEntry)
        assertEquals(HomeReplayHelper.DEFAULT_APP_NAME, payload2.appName)
        assertEquals("", payload2.packageName)
        assertEquals(HomeReplayHelper.DEFAULT_APP_NAME, payload2.sender)
        assertEquals(HomeReplayHelper.DEFAULT_REPLAY_MESSAGE, payload2.message)
    }

    @Test
    fun buildTestCallPayload_usesDefaultsAndPreservesCustomValues() {
        val defaultPayload = HomeReplayHelper.buildTestCallPayload(packageName = "  com.notifyring.app  ")
        assertEquals(HomeReplayHelper.DEFAULT_APP_NAME, defaultPayload.appName)
        assertEquals("com.notifyring.app", defaultPayload.packageName)
        assertEquals(HomeReplayHelper.DEFAULT_TEST_SENDER, defaultPayload.sender)
        assertEquals(HomeReplayHelper.DEFAULT_TEST_MESSAGE, defaultPayload.message)

        val customPayload = HomeReplayHelper.buildTestCallPayload(
            packageName = "com.notifyring.app",
            appName = "  Pharmacy Alert  ",
            sender = "  CVS  ",
            message = "  Refill ready  "
        )
        assertEquals("Pharmacy Alert", customPayload.appName)
        assertEquals("com.notifyring.app", customPayload.packageName)
        assertEquals("CVS", customPayload.sender)
        assertEquals("Refill ready", customPayload.message)

        val blankOverrides = HomeReplayHelper.buildTestCallPayload(
            packageName = "com.notifyring.app",
            appName = " ",
            sender = "",
            message = "   "
        )
        assertEquals(HomeReplayHelper.DEFAULT_APP_NAME, blankOverrides.appName)
        assertEquals(HomeReplayHelper.DEFAULT_TEST_SENDER, blankOverrides.sender)
        assertEquals(HomeReplayHelper.DEFAULT_TEST_MESSAGE, blankOverrides.message)
    }

    @Test
    fun resolvePrimaryDegradedIssue_returnsNullWhenHealthyAndHighestPriorityWhenDegraded() {
        val healthy = PermissionSnapshot(
            sdkInt = 35,
            notificationListenerEnabled = true,
            postNotificationsGranted = true,
            fullScreenIntentGranted = true,
            bluetoothConnectGranted = false,
            alertChannelImportanceHigh = true
        )
        assertNull(HomeReplayHelper.resolvePrimaryDegradedIssue(healthy))

        val listenerDisabled = healthy.copy(
            notificationListenerEnabled = false,
            fullScreenIntentGranted = false,
            alertChannelImportanceHigh = false
        )
        assertEquals(
            DegradedPermissionIssue.NOTIFICATION_LISTENER_DISABLED,
            HomeReplayHelper.resolvePrimaryDegradedIssue(listenerDisabled)
        )

        val fsiDenied = healthy.copy(
            fullScreenIntentGranted = false,
            alertChannelImportanceHigh = false
        )
        assertEquals(
            DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED,
            HomeReplayHelper.resolvePrimaryDegradedIssue(fsiDenied)
        )

        val channelDegraded = healthy.copy(alertChannelImportanceHigh = false)
        assertEquals(
            DegradedPermissionIssue.ALERT_CHANNEL_DEGRADED,
            HomeReplayHelper.resolvePrimaryDegradedIssue(channelDegraded)
        )
    }
}
