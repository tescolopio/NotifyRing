package com.notifyring.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterEngineTest {

    private val engine = NotificationFilterEngine()

    private fun validCandidate(
        packageName: String = "com.whatsapp",
        ownPackageName: String = "com.notifyring.app",
        monitoredPackages: Set<String> = setOf("com.whatsapp", "org.thoughtcrime.securesms"),
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
        isForegroundService: Boolean = false,
        isClearable: Boolean = true,
        category: String? = "msg",
        importance: Int = NotificationFilterEngine.IMPORTANCE_DEFAULT,
        isAmbient: Boolean = false,
        matchesInterruptionFilter: Boolean = true,
        isMutedOrSuppressed: Boolean = false
    ) = NotificationCandidate(
        packageName = packageName,
        ownPackageName = ownPackageName,
        monitoredPackages = monitoredPackages,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
        isForegroundService = isForegroundService,
        isClearable = isClearable,
        category = category,
        importance = importance,
        isAmbient = isAmbient,
        matchesInterruptionFilter = matchesInterruptionFilter,
        isMutedOrSuppressed = isMutedOrSuppressed
    )

    @Test
    fun acceptsMonitoredClearableMessagingNotification() {
        val decision = engine.evaluate(validCandidate())
        assertEquals(FilterDecision.Accepted, decision)
        assertTrue(engine.shouldProcess(validCandidate()))
    }

    @Test
    fun acceptsMonitoredNotificationWithNullCategoryAndHighImportance() {
        val candidate = validCandidate(
            category = null,
            importance = NotificationFilterEngine.IMPORTANCE_HIGH
        )
        assertEquals(FilterDecision.Accepted, engine.evaluate(candidate))
    }

    @Test
    fun rejectsBlankPackageName() {
        val decision = engine.evaluate(validCandidate(packageName = "   "))
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.BLANK_PACKAGE),
            decision
        )
        assertFalse(decision.isAccepted)
    }

    @Test
    fun rejectsSelfPackageEvenWhenPresentInMonitoredSet() {
        val candidate = validCandidate(
            packageName = "com.notifyring.app",
            ownPackageName = "com.notifyring.app",
            monitoredPackages = setOf("com.notifyring.app", "com.whatsapp")
        )
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.SELF_PACKAGE),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsUnmonitoredPackage() {
        val candidate = validCandidate(packageName = "com.instagram.android")
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.NOT_MONITORED),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsWhenMonitoredPackagesSetIsEmpty() {
        val candidate = validCandidate(monitoredPackages = emptySet())
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.NOT_MONITORED),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsGroupSummaryNotification() {
        val candidate = validCandidate(isGroupSummary = true)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.GROUP_SUMMARY),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsOngoingNotification() {
        val candidate = validCandidate(isOngoing = true)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.ONGOING),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsForegroundServiceNotification() {
        val candidate = validCandidate(isForegroundService = true)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.FOREGROUND_SERVICE),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsNonClearableNotification() {
        val candidate = validCandidate(isClearable = false)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.NON_CLEARABLE),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsAllBlockedSystemAndCallCategoriesCaseInsensitively() {
        val blocked = listOf(
            NotificationFilterEngine.CATEGORY_CALL,
            "CALL",
            NotificationFilterEngine.CATEGORY_VOIP,
            NotificationFilterEngine.CATEGORY_PROGRESS,
            NotificationFilterEngine.CATEGORY_SERVICE,
            NotificationFilterEngine.CATEGORY_TRANSPORT,
            NotificationFilterEngine.CATEGORY_SYSTEM,
            "system",
            NotificationFilterEngine.CATEGORY_STATUS
        )
        for (cat in blocked) {
            val decision = engine.evaluate(validCandidate(category = cat))
            assertEquals(
                "Expected category '$cat' to be blocked",
                FilterDecision.Rejected(FilterRejectionReason.BLOCKED_CATEGORY),
                decision
            )
        }
    }

    @Test
    fun allowsUserFacingCategories() {
        val allowed = listOf("msg", "social", "email", "event", "reminder", "alarm")
        for (cat in allowed) {
            assertTrue(
                "Expected category '$cat' to be accepted",
                engine.shouldProcess(validCandidate(category = cat))
            )
        }
    }

    @Test
    fun rejectsSilentAndLowImportanceNotifications() {
        val silentLevels = listOf(
            NotificationFilterEngine.IMPORTANCE_NONE,
            NotificationFilterEngine.IMPORTANCE_MIN,
            NotificationFilterEngine.IMPORTANCE_LOW
        )
        for (level in silentLevels) {
            assertEquals(
                FilterDecision.Rejected(FilterRejectionReason.LOW_IMPORTANCE),
                engine.evaluate(validCandidate(importance = level))
            )
        }
    }

    @Test
    fun rejectsAmbientNotification() {
        val candidate = validCandidate(isAmbient = true)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.AMBIENT),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsDndSuppressedNotification() {
        val candidate = validCandidate(matchesInterruptionFilter = false)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.INTERRUPTION_FILTER_SUPPRESSED),
            engine.evaluate(candidate)
        )
    }

    @Test
    fun rejectsMutedOrSuspendedNotification() {
        val candidate = validCandidate(isMutedOrSuppressed = true)
        assertEquals(
            FilterDecision.Rejected(FilterRejectionReason.MUTED_OR_SUPPRESSED),
            engine.evaluate(candidate)
        )
    }
}
