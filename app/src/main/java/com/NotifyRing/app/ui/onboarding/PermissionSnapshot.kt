package com.notifyring.app.ui.onboarding

/**
 * Ordered 8-step onboarding wizard sequence.
 * Pure Kotlin (zero Android framework imports) for JVM unit testing.
 */
enum class OnboardingStep(val stepNumber: Int) {
    PROMINENT_DISCLOSURE(1),
    NOTIFICATION_LISTENER(2),
    POST_NOTIFICATIONS(3),
    FULL_SCREEN_INTENT(4),
    BLUETOOTH_CONNECT(5),
    BATTERY_OPTIMIZATION(6),
    APP_PICKER(7),
    TEST_CALL(8);

    companion object {
        val totalSteps: Int = entries.size
    }
}

/**
 * Degraded permission or channel states surfaced on the Senior Home screen.
 */
enum class DegradedPermissionIssue {
    NOTIFICATION_LISTENER_DISABLED,
    POST_NOTIFICATIONS_DENIED,
    FULL_SCREEN_INTENT_DENIED,
    ALERT_CHANNEL_DEGRADED
}

/**
 * Immutable snapshot of Android runtime permissions, special access grants, and channel health.
 * Pure Kotlin so every API 29-35 gating branch can be unit-tested on the JVM.
 */
data class PermissionSnapshot(
    val sdkInt: Int,
    val notificationListenerEnabled: Boolean,
    val postNotificationsGranted: Boolean,
    val notificationsEnabledInSystem: Boolean = true,
    val fullScreenIntentGranted: Boolean,
    val bluetoothConnectGranted: Boolean,
    val alertChannelImportanceHigh: Boolean = true,
    val ignoringBatteryOptimizations: Boolean = false
) {
    val isBluetoothConnectRequired: Boolean
        get() = sdkInt >= API_31

    val isBluetoothConnectEffectivelyGranted: Boolean
        get() = !isBluetoothConnectRequired || bluetoothConnectGranted

    val isPostNotificationsRequired: Boolean
        get() = sdkInt >= API_33

    val isPostNotificationsEffectivelyGranted: Boolean
        get() = (!isPostNotificationsRequired || postNotificationsGranted) && notificationsEnabledInSystem

    val isFullScreenIntentRequired: Boolean
        get() = sdkInt >= API_34

    val isFullScreenIntentEffectivelyGranted: Boolean
        get() = !isFullScreenIntentRequired || fullScreenIntentGranted

    /**
     * Ordered list of active degraded permission/channel issues that impair alert ringing.
     */
    val degradedIssues: List<DegradedPermissionIssue>
        get() = buildList {
            if (!notificationListenerEnabled) {
                add(DegradedPermissionIssue.NOTIFICATION_LISTENER_DISABLED)
            }
            if (!isPostNotificationsEffectivelyGranted) {
                add(DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED)
            }
            if (!isFullScreenIntentEffectivelyGranted) {
                add(DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED)
            }
            if (!alertChannelImportanceHigh) {
                add(DegradedPermissionIssue.ALERT_CHANNEL_DEGRADED)
            }
        }

    val isFullyProtected: Boolean
        get() = degradedIssues.isEmpty()

    val hasDegradedPermissions: Boolean
        get() = degradedIssues.isNotEmpty()

    /**
     * On Android 13+ (API 33+), sideloaded APKs have Notification Listener blocked behind
     * "Restricted settings" until the user enables it in App Info -> 3-dots -> Allow restricted settings.
     * Show this callout once the user has visited Notification Access settings and returned with access still off.
     */
    fun shouldShowRestrictedSettingsHint(hasAttemptedListenerSettings: Boolean): Boolean =
        sdkInt >= API_33 && hasAttemptedListenerSettings && !notificationListenerEnabled

    /**
     * On Android 13+ (API 33+), if POST_NOTIFICATIONS was already requested once and the system
     * no longer recommends showing a rationale, the user permanently denied it (or disabled it in
     * system settings), so we should open `ACTION_APP_NOTIFICATION_SETTINGS` directly.
     */
    fun shouldFallbackToAppNotificationSettings(
        hasRequestedPostNotificationsOnce: Boolean,
        shouldShowRationale: Boolean
    ): Boolean {
        if (!notificationsEnabledInSystem) return true
        return isPostNotificationsRequired &&
            !postNotificationsGranted &&
            hasRequestedPostNotificationsOnce &&
            !shouldShowRationale
    }

    companion object {
        const val API_31 = 31
        const val API_33 = 33
        const val API_34 = 34
    }
}

/**
 * Pure-Kotlin state model for the 8-step onboarding wizard.
 */
data class OnboardingWizardState(
    val currentStep: OnboardingStep = OnboardingStep.PROMINENT_DISCLOSURE,
    val disclosureAccepted: Boolean = false,
    val hasAttemptedListenerSettings: Boolean = false,
    val hasRequestedPostNotificationsOnce: Boolean = false,
    val postNotificationsShouldShowRationale: Boolean = false,
    val bluetoothSkipped: Boolean = false,
    val batteryGuidanceAcknowledged: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val testCallTriggered: Boolean = false
)

/**
 * Pure-Kotlin state machine driving step satisfaction, advancement, and finish eligibility
 * for [OnboardingWizardScreen].
 */
object OnboardingStateMachine {

    fun isStepSatisfied(
        step: OnboardingStep,
        state: OnboardingWizardState,
        snapshot: PermissionSnapshot
    ): Boolean = when (step) {
        OnboardingStep.PROMINENT_DISCLOSURE -> state.disclosureAccepted
        OnboardingStep.NOTIFICATION_LISTENER -> snapshot.notificationListenerEnabled
        OnboardingStep.POST_NOTIFICATIONS -> snapshot.isPostNotificationsEffectivelyGranted
        OnboardingStep.FULL_SCREEN_INTENT -> snapshot.isFullScreenIntentEffectivelyGranted
        OnboardingStep.BLUETOOTH_CONNECT ->
            snapshot.isBluetoothConnectEffectivelyGranted || state.bluetoothSkipped
        OnboardingStep.BATTERY_OPTIMIZATION ->
            snapshot.ignoringBatteryOptimizations || state.batteryGuidanceAcknowledged
        OnboardingStep.APP_PICKER -> state.selectedPackages.isNotEmpty()
        OnboardingStep.TEST_CALL -> state.testCallTriggered
    }

    fun canAdvance(
        state: OnboardingWizardState,
        snapshot: PermissionSnapshot
    ): Boolean = isStepSatisfied(state.currentStep, state, snapshot)

    fun canFinish(
        state: OnboardingWizardState,
        snapshot: PermissionSnapshot
    ): Boolean = state.disclosureAccepted &&
        snapshot.notificationListenerEnabled &&
        snapshot.isPostNotificationsEffectivelyGranted &&
        snapshot.isFullScreenIntentEffectivelyGranted &&
        state.selectedPackages.isNotEmpty() &&
        state.testCallTriggered

    fun nextStep(current: OnboardingStep): OnboardingStep? {
        val nextOrdinal = current.ordinal + 1
        return OnboardingStep.entries.getOrNull(nextOrdinal)
    }

    fun previousStep(current: OnboardingStep): OnboardingStep? {
        val prevOrdinal = current.ordinal - 1
        return OnboardingStep.entries.getOrNull(prevOrdinal)
    }

    fun advance(
        state: OnboardingWizardState,
        snapshot: PermissionSnapshot
    ): OnboardingWizardState {
        if (!canAdvance(state, snapshot)) return state
        val next = nextStep(state.currentStep) ?: return state
        return state.copy(currentStep = next)
    }

    fun goBack(state: OnboardingWizardState): OnboardingWizardState {
        val prev = previousStep(state.currentStep) ?: return state
        return state.copy(currentStep = prev)
    }

    fun acceptDisclosure(state: OnboardingWizardState): OnboardingWizardState =
        state.copy(
            disclosureAccepted = true,
            currentStep = if (state.currentStep == OnboardingStep.PROMINENT_DISCLOSURE) {
                OnboardingStep.NOTIFICATION_LISTENER
            } else {
                state.currentStep
            }
        )

    fun markListenerSettingsOpened(state: OnboardingWizardState): OnboardingWizardState =
        state.copy(hasAttemptedListenerSettings = true)

    fun markPostNotificationsRequested(
        state: OnboardingWizardState,
        shouldShowRationale: Boolean
    ): OnboardingWizardState = state.copy(
        hasRequestedPostNotificationsOnce = true,
        postNotificationsShouldShowRationale = shouldShowRationale
    )

    fun skipBluetooth(state: OnboardingWizardState): OnboardingWizardState =
        state.copy(
            bluetoothSkipped = true,
            currentStep = if (state.currentStep == OnboardingStep.BLUETOOTH_CONNECT) {
                OnboardingStep.BATTERY_OPTIMIZATION
            } else {
                state.currentStep
            }
        )

    fun acknowledgeBatteryGuidance(state: OnboardingWizardState): OnboardingWizardState =
        state.copy(
            batteryGuidanceAcknowledged = true,
            currentStep = if (state.currentStep == OnboardingStep.BATTERY_OPTIMIZATION) {
                OnboardingStep.APP_PICKER
            } else {
                state.currentStep
            }
        )

    fun togglePackage(state: OnboardingWizardState, packageName: String): OnboardingWizardState {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return state
        val updated = if (normalized in state.selectedPackages) {
            state.selectedPackages - normalized
        } else {
            state.selectedPackages + normalized
        }
        return state.copy(selectedPackages = updated)
    }

    fun withSelectedPackages(
        state: OnboardingWizardState,
        packages: Set<String>
    ): OnboardingWizardState = state.copy(selectedPackages = packages)

    fun markTestCallTriggered(state: OnboardingWizardState): OnboardingWizardState =
        state.copy(testCallTriggered = true)
}
