package com.notifyring.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateMachineTest {

    private fun snapshot(
        sdkInt: Int,
        notificationListenerEnabled: Boolean = true,
        postNotificationsGranted: Boolean = true,
        notificationsEnabledInSystem: Boolean = true,
        fullScreenIntentGranted: Boolean = true,
        bluetoothConnectGranted: Boolean = true,
        alertChannelImportanceHigh: Boolean = true,
        ignoringBatteryOptimizations: Boolean = false
    ): PermissionSnapshot = PermissionSnapshot(
        sdkInt = sdkInt,
        notificationListenerEnabled = notificationListenerEnabled,
        postNotificationsGranted = postNotificationsGranted,
        notificationsEnabledInSystem = notificationsEnabledInSystem,
        fullScreenIntentGranted = fullScreenIntentGranted,
        bluetoothConnectGranted = bluetoothConnectGranted,
        alertChannelImportanceHigh = alertChannelImportanceHigh,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations
    )

    @Test
    fun permissionSnapshot_api29_autoGrantsPermissionsBelowApiThresholds() {
        val snap = snapshot(
            sdkInt = 29,
            notificationListenerEnabled = true,
            postNotificationsGranted = false,
            notificationsEnabledInSystem = true,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false
        )

        assertFalse(snap.isBluetoothConnectRequired)
        assertTrue(snap.isBluetoothConnectEffectivelyGranted)
        assertFalse(snap.isPostNotificationsRequired)
        assertTrue(snap.isPostNotificationsEffectivelyGranted)
        assertFalse(snap.isFullScreenIntentRequired)
        assertTrue(snap.isFullScreenIntentEffectivelyGranted)
        assertTrue(snap.isFullyProtected)
        assertFalse(snap.hasDegradedPermissions)
        assertTrue(snap.degradedIssues.isEmpty())
    }

    @Test
    fun permissionSnapshot_api31_requiresBluetoothOnly() {
        val snap = snapshot(
            sdkInt = 31,
            postNotificationsGranted = false,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false
        )

        assertTrue(snap.isBluetoothConnectRequired)
        assertFalse(snap.isBluetoothConnectEffectivelyGranted)
        assertFalse(snap.isPostNotificationsRequired)
        assertTrue(snap.isPostNotificationsEffectivelyGranted)
        assertFalse(snap.isFullScreenIntentRequired)
        assertTrue(snap.isFullScreenIntentEffectivelyGranted)
        // Bluetooth is optional and does not trigger degraded alert banners.
        assertTrue(snap.isFullyProtected)
    }

    @Test
    fun permissionSnapshot_api33_requiresPostNotificationsAndBluetoothNotFsi() {
        val snap = snapshot(
            sdkInt = 33,
            postNotificationsGranted = false,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false
        )

        assertTrue(snap.isBluetoothConnectRequired)
        assertTrue(snap.isPostNotificationsRequired)
        assertFalse(snap.isPostNotificationsEffectivelyGranted)
        assertFalse(snap.isFullScreenIntentRequired)
        assertTrue(snap.isFullScreenIntentEffectivelyGranted)
        assertEquals(listOf(DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED), snap.degradedIssues)
    }

    @Test
    fun permissionSnapshot_api34And35_requireAllRuntimeAndSpecialPermissions() {
        for (api in listOf(34, 35)) {
            val denied = snapshot(
                sdkInt = api,
                notificationListenerEnabled = true,
                postNotificationsGranted = true,
                fullScreenIntentGranted = false,
                bluetoothConnectGranted = false
            )
            assertTrue("API $api requires FSI", denied.isFullScreenIntentRequired)
            assertFalse("API $api FSI denied", denied.isFullScreenIntentEffectivelyGranted)
            assertEquals(
                listOf(DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED),
                denied.degradedIssues
            )

            val granted = denied.copy(fullScreenIntentGranted = true)
            assertTrue("API $api FSI granted", granted.isFullScreenIntentEffectivelyGranted)
            assertTrue("API $api fully protected", granted.isFullyProtected)
        }
    }

    @Test
    fun permissionSnapshot_degradedIssues_detectsAllIssuesInPriorityOrder() {
        val snap = snapshot(
            sdkInt = 35,
            notificationListenerEnabled = false,
            postNotificationsGranted = false,
            notificationsEnabledInSystem = true,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false,
            alertChannelImportanceHigh = false,
            ignoringBatteryOptimizations = false
        )

        assertTrue(snap.hasDegradedPermissions)
        assertFalse(snap.isFullyProtected)
        assertEquals(
            listOf(
                DegradedPermissionIssue.NOTIFICATION_LISTENER_DISABLED,
                DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED,
                DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED,
                DegradedPermissionIssue.ALERT_CHANNEL_DEGRADED
            ),
            snap.degradedIssues
        )
    }

    @Test
    fun permissionSnapshot_systemNotificationsDisabled_triggersPostNotificationsDegradedEvenOnApi29() {
        val snap = snapshot(
            sdkInt = 29,
            notificationsEnabledInSystem = false
        )

        assertFalse(snap.isPostNotificationsEffectivelyGranted)
        assertEquals(listOf(DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED), snap.degradedIssues)
        assertTrue(
            snap.shouldFallbackToAppNotificationSettings(
                hasRequestedPostNotificationsOnce = false,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun permissionSnapshot_restrictedSettingsHint_onlyTriggersOnApi33PlusAfterAttemptWhileDisabled() {
        val api31Disabled = snapshot(sdkInt = 31, notificationListenerEnabled = false)
        assertFalse(api31Disabled.shouldShowRestrictedSettingsHint(hasAttemptedListenerSettings = true))

        val api33Disabled = snapshot(sdkInt = 33, notificationListenerEnabled = false)
        assertFalse(api33Disabled.shouldShowRestrictedSettingsHint(hasAttemptedListenerSettings = false))
        assertTrue(api33Disabled.shouldShowRestrictedSettingsHint(hasAttemptedListenerSettings = true))

        val api35Enabled = snapshot(sdkInt = 35, notificationListenerEnabled = true)
        assertFalse(api35Enabled.shouldShowRestrictedSettingsHint(hasAttemptedListenerSettings = true))
    }

    @Test
    fun permissionSnapshot_shouldFallbackToAppNotificationSettings_detectsPermanentDenialOnApi33Plus() {
        val api33Denied = snapshot(sdkInt = 33, postNotificationsGranted = false)
        assertFalse(
            api33Denied.shouldFallbackToAppNotificationSettings(
                hasRequestedPostNotificationsOnce = false,
                shouldShowRationale = false
            )
        )
        assertFalse(
            api33Denied.shouldFallbackToAppNotificationSettings(
                hasRequestedPostNotificationsOnce = true,
                shouldShowRationale = true
            )
        )
        assertTrue(
            api33Denied.shouldFallbackToAppNotificationSettings(
                hasRequestedPostNotificationsOnce = true,
                shouldShowRationale = false
            )
        )

        val api33Granted = snapshot(sdkInt = 33, postNotificationsGranted = true)
        assertFalse(
            api33Granted.shouldFallbackToAppNotificationSettings(
                hasRequestedPostNotificationsOnce = true,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun onboardingStateMachine_appPickerRequiresAtLeastOneSelectedPackage() {
        val snap = snapshot(sdkInt = 35)
        var state = OnboardingWizardState(currentStep = OnboardingStep.APP_PICKER)

        assertFalse(OnboardingStateMachine.canAdvance(state, snap))

        // Blank package names are ignored.
        state = OnboardingStateMachine.togglePackage(state, "   ")
        assertTrue(state.selectedPackages.isEmpty())
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))

        // Selecting a package enables advancement.
        state = OnboardingStateMachine.togglePackage(state, "  com.whatsapp  ")
        assertEquals(setOf("com.whatsapp"), state.selectedPackages)
        assertTrue(OnboardingStateMachine.canAdvance(state, snap))

        // Deselecting the last package re-blocks advancement.
        state = OnboardingStateMachine.togglePackage(state, "com.whatsapp")
        assertTrue(state.selectedPackages.isEmpty())
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))

        // Bulk setting packages works as expected.
        state = OnboardingStateMachine.withSelectedPackages(state, setOf("epic.mychart.android"))
        assertTrue(OnboardingStateMachine.canAdvance(state, snap))
    }

    @Test
    fun onboardingStateMachine_fullWalkthroughAndFinishGatingOnApi35() {
        var snap = snapshot(
            sdkInt = 35,
            notificationListenerEnabled = false,
            postNotificationsGranted = false,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false,
            ignoringBatteryOptimizations = false
        )
        var state = OnboardingWizardState()

        // Step 1: Prominent disclosure blocks advance until accepted.
        assertEquals(OnboardingStep.PROMINENT_DISCLOSURE, state.currentStep)
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))
        assertEquals(state, OnboardingStateMachine.advance(state, snap))
        assertEquals(state, OnboardingStateMachine.goBack(state))

        state = OnboardingStateMachine.acceptDisclosure(state)
        assertTrue(state.disclosureAccepted)
        assertEquals(OnboardingStep.NOTIFICATION_LISTENER, state.currentStep)

        // Step 2: Notification Listener.
        state = OnboardingStateMachine.markListenerSettingsOpened(state)
        assertTrue(state.hasAttemptedListenerSettings)
        assertTrue(snap.shouldShowRestrictedSettingsHint(state.hasAttemptedListenerSettings))
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))

        snap = snap.copy(notificationListenerEnabled = true)
        assertTrue(OnboardingStateMachine.canAdvance(state, snap))
        state = OnboardingStateMachine.advance(state, snap)
        assertEquals(OnboardingStep.POST_NOTIFICATIONS, state.currentStep)

        // Step 3: POST_NOTIFICATIONS.
        state = OnboardingStateMachine.markPostNotificationsRequested(state, shouldShowRationale = false)
        assertTrue(state.hasRequestedPostNotificationsOnce)
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))

        snap = snap.copy(postNotificationsGranted = true)
        state = OnboardingStateMachine.advance(state, snap)
        assertEquals(OnboardingStep.FULL_SCREEN_INTENT, state.currentStep)

        // Step 4: FULL_SCREEN_INTENT.
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))
        snap = snap.copy(fullScreenIntentGranted = true)
        state = OnboardingStateMachine.advance(state, snap)
        assertEquals(OnboardingStep.BLUETOOTH_CONNECT, state.currentStep)

        // Step 5: Optional BLUETOOTH_CONNECT can be skipped.
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))
        state = OnboardingStateMachine.skipBluetooth(state)
        assertTrue(state.bluetoothSkipped)
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, state.currentStep)

        // Step 6: Optional BATTERY_OPTIMIZATION can be acknowledged without ignoring optimizations.
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))
        state = OnboardingStateMachine.acknowledgeBatteryGuidance(state)
        assertTrue(state.batteryGuidanceAcknowledged)
        assertEquals(OnboardingStep.APP_PICKER, state.currentStep)

        // Step 7: App picker requires >= 1 app.
        assertFalse(OnboardingStateMachine.canAdvance(state, snap))
        state = OnboardingStateMachine.togglePackage(state, "com.google.android.apps.messaging")
        state = OnboardingStateMachine.advance(state, snap)
        assertEquals(OnboardingStep.TEST_CALL, state.currentStep)

        // Step 8: Test call required before Finish.
        assertFalse(OnboardingStateMachine.canFinish(state, snap))
        state = OnboardingStateMachine.markTestCallTriggered(state)
        assertTrue(OnboardingStateMachine.canFinish(state, snap))

        // Advancing past the last step stays on TEST_CALL; goBack returns to APP_PICKER.
        assertNull(OnboardingStateMachine.nextStep(OnboardingStep.TEST_CALL))
        assertEquals(OnboardingStep.TEST_CALL, OnboardingStateMachine.advance(state, snap).currentStep)
        assertEquals(OnboardingStep.APP_PICKER, OnboardingStateMachine.goBack(state).currentStep)

        // Revoking a required permission blocks Finish even after testCallTriggered.
        assertFalse(
            OnboardingStateMachine.canFinish(
                state,
                snap.copy(fullScreenIntentGranted = false)
            )
        )
    }

    @Test
    fun onboardingStateMachine_api29_allowsFinishingWithoutApi31PlusGrants() {
        val snap = snapshot(
            sdkInt = 29,
            notificationListenerEnabled = true,
            postNotificationsGranted = false,
            notificationsEnabledInSystem = true,
            fullScreenIntentGranted = false,
            bluetoothConnectGranted = false
        )
        val state = OnboardingWizardState(
            currentStep = OnboardingStep.TEST_CALL,
            disclosureAccepted = true,
            selectedPackages = setOf("com.whatsapp"),
            testCallTriggered = true
        )

        assertTrue(OnboardingStateMachine.isStepSatisfied(OnboardingStep.POST_NOTIFICATIONS, state, snap))
        assertTrue(OnboardingStateMachine.isStepSatisfied(OnboardingStep.FULL_SCREEN_INTENT, state, snap))
        assertTrue(OnboardingStateMachine.isStepSatisfied(OnboardingStep.BLUETOOTH_CONNECT, state, snap))
        assertTrue(OnboardingStateMachine.canFinish(state, snap))
    }
}
