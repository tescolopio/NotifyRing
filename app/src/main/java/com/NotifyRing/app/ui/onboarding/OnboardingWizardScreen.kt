package com.notifyring.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifyring.app.R
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.data.UserSettings
import com.notifyring.app.telecom.LocalCallManager
import com.notifyring.app.ui.home.HomeReplayHelper
import com.notifyring.app.ui.theme.LocalNotifyRingStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingWizardScreen(
    settingsRepository: SettingsRepository,
    localCallManager: LocalCallManager,
    onOpenVoiceSpeech: () -> Unit = {},
    onOnboardingComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = UserSettings())

    var snapshot by remember { mutableStateOf(AndroidPermissionInspector.inspect(context)) }
    var wizardState by remember { mutableStateOf(OnboardingWizardState()) }
    var initializedFromSettings by remember { mutableStateOf(false) }

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var appSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(settings.monitoredPackages) {
        if (!initializedFromSettings && settings.monitoredPackages.isNotEmpty()) {
            wizardState = OnboardingStateMachine.withSelectedPackages(
                wizardState,
                settings.monitoredPackages
            )
            initializedFromSettings = true
        }
    }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            LauncherAppQuery.queryLaunchableApps(context)
        }
    }

    // Re-evaluate ALL permissions whenever the user returns from Android system Settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        snapshot = AndroidPermissionInspector.inspect(context)
    }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val updatedSnapshot = AndroidPermissionInspector.inspect(context)
        snapshot = updatedSnapshot
        val shouldShowRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.findActivity()?.let { activity ->
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } ?: false
        } else {
            false
        }
        wizardState = OnboardingStateMachine.markPostNotificationsRequested(
            state = wizardState,
            shouldShowRationale = shouldShowRationale
        )
    }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        snapshot = AndroidPermissionInspector.inspect(context)
    }

    val currentStep = wizardState.currentStep
    val progress = currentStep.stepNumber.toFloat() / OnboardingStep.totalSteps.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(
                                R.string.onboarding_step_progress,
                                currentStep.stepNumber,
                                OnboardingStep.totalSteps
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            OnboardingBottomBar(
                wizardState = wizardState,
                snapshot = snapshot,
                onBack = { wizardState = OnboardingStateMachine.goBack(wizardState) },
                onContinue = {
                    wizardState = OnboardingStateMachine.advance(wizardState, snapshot)
                },
                onSkip = {
                    wizardState = when (currentStep) {
                        OnboardingStep.BLUETOOTH_CONNECT ->
                            OnboardingStateMachine.skipBluetooth(wizardState)
                        OnboardingStep.BATTERY_OPTIMIZATION ->
                            OnboardingStateMachine.acknowledgeBatteryGuidance(wizardState)
                        else -> wizardState
                    }
                },
                onFinish = {
                    scope.launch {
                        settingsRepository.setMonitoredPackages(wizardState.selectedPackages)
                        settingsRepository.setOnboardingCompleted(true)
                        onOnboardingComplete()
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            if (currentStep == OnboardingStep.APP_PICKER) {
                AppPickerStepContent(
                    apps = installedApps,
                    searchQuery = appSearchQuery,
                    selectedPackages = wizardState.selectedPackages,
                    onSearchQueryChange = { appSearchQuery = it },
                    onTogglePackage = { pkg ->
                        val updated = OnboardingStateMachine.togglePackage(wizardState, pkg)
                        wizardState = updated
                        scope.launch {
                            settingsRepository.setMonitoredPackages(updated.selectedPackages)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (currentStep) {
                        OnboardingStep.PROMINENT_DISCLOSURE -> ProminentDisclosureStepContent(
                            accepted = wizardState.disclosureAccepted,
                            onAccept = {
                                wizardState = OnboardingStateMachine.acceptDisclosure(wizardState)
                            }
                        )

                        OnboardingStep.NOTIFICATION_LISTENER -> NotificationListenerStepContent(
                            granted = snapshot.notificationListenerEnabled,
                            showRestrictedSettingsHint = snapshot.shouldShowRestrictedSettingsHint(
                                wizardState.hasAttemptedListenerSettings
                            ),
                            onOpenListenerSettings = {
                                wizardState = OnboardingStateMachine.markListenerSettingsOpened(
                                    wizardState
                                )
                                AndroidPermissionInspector.openNotificationListenerSettings(context)
                            },
                            onOpenAppInfo = {
                                AndroidPermissionInspector.openAppDetailsSettings(context)
                            }
                        )

                        OnboardingStep.POST_NOTIFICATIONS -> PostNotificationsStepContent(
                            snapshot = snapshot,
                            wizardState = wizardState,
                            onRequestPermission = {
                                if (snapshot.shouldFallbackToAppNotificationSettings(
                                        hasRequestedPostNotificationsOnce = wizardState.hasRequestedPostNotificationsOnce,
                                        shouldShowRationale = wizardState.postNotificationsShouldShowRationale
                                    )
                                ) {
                                    AndroidPermissionInspector.openAppNotificationSettings(context)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    postNotificationsLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    AndroidPermissionInspector.openAppNotificationSettings(context)
                                }
                            }
                        )

                        OnboardingStep.FULL_SCREEN_INTENT -> FullScreenIntentStepContent(
                            snapshot = snapshot,
                            onOpenFullScreenSettings = {
                                AndroidPermissionInspector.openFullScreenIntentSettings(context)
                            }
                        )

                        OnboardingStep.BLUETOOTH_CONNECT -> BluetoothConnectStepContent(
                            snapshot = snapshot,
                            onRequestBluetooth = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    bluetoothConnectLauncher.launch(
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                }
                            }
                        )

                        OnboardingStep.BATTERY_OPTIMIZATION -> BatteryGuidanceStepContent(
                            ignoringBatteryOptimizations = snapshot.ignoringBatteryOptimizations,
                            onOpenBatterySettings = {
                                wizardState = wizardState.copy(batteryGuidanceAcknowledged = true)
                                AndroidPermissionInspector.openBatteryOptimizationSettings(context)
                            }
                        )

                        OnboardingStep.TEST_CALL -> {
                            val testSender = stringResource(R.string.onboarding_test_sender)
                            val testMessage = stringResource(R.string.onboarding_test_message)
                            TestCallStepContent(
                                triggered = wizardState.testCallTriggered,
                                onTriggerTestCall = {
                                    val payload = HomeReplayHelper.buildTestCallPayload(
                                        packageName = context.packageName,
                                        sender = testSender,
                                        message = testMessage
                                    )
                                    localCallManager.enqueueCall(payload)
                                    wizardState = OnboardingStateMachine.markTestCallTriggered(
                                        wizardState
                                    )
                                },
                                onOpenVoiceSpeech = onOpenVoiceSpeech
                            )
                        }

                        OnboardingStep.APP_PICKER -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ProminentDisclosureStepContent(
    accepted: Boolean,
    onAccept: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_disclosure_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = stringResource(R.string.onboarding_disclosure_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    if (accepted) {
        StatusPill(
            text = stringResource(R.string.common_granted),
            containerColor = statusColors.successContainer,
            contentColor = statusColors.onSuccessContainer
        )
    }

    Button(
        onClick = onAccept,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_disclosure_accept),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun NotificationListenerStepContent(
    granted: Boolean,
    showRestrictedSettingsHint: Boolean,
    onOpenListenerSettings: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    Text(
        text = stringResource(R.string.onboarding_listener_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        text = stringResource(R.string.onboarding_listener_body),
        style = MaterialTheme.typography.bodyLarge
    )
    PermissionStatusCard(granted = granted)

    if (!granted) {
        Button(
            onClick = onOpenListenerSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_listener_action),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    if (showRestrictedSettingsHint) {
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColors.warningContainer),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = statusColors.onWarningContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.onboarding_listener_restricted_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColors.onWarningContainer
                    )
                }
                Text(
                    text = stringResource(R.string.onboarding_listener_restricted_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColors.onWarningContainer
                )
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_listener_open_app_info),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun PostNotificationsStepContent(
    snapshot: PermissionSnapshot,
    wizardState: OnboardingWizardState,
    onRequestPermission: () -> Unit
) {
    Text(
        text = stringResource(R.string.onboarding_post_notif_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        text = stringResource(R.string.onboarding_post_notif_body),
        style = MaterialTheme.typography.bodyLarge
    )

    if (!snapshot.isPostNotificationsRequired && snapshot.notificationsEnabledInSystem) {
        Text(
            text = stringResource(R.string.onboarding_post_notif_auto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PermissionStatusCard(granted = snapshot.isPostNotificationsEffectivelyGranted)

    if (!snapshot.isPostNotificationsEffectivelyGranted) {
        val useSettingsFallback = snapshot.shouldFallbackToAppNotificationSettings(
            hasRequestedPostNotificationsOnce = wizardState.hasRequestedPostNotificationsOnce,
            shouldShowRationale = wizardState.postNotificationsShouldShowRationale
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (useSettingsFallback) {
                    stringResource(R.string.onboarding_post_notif_settings_action)
                } else {
                    stringResource(R.string.onboarding_post_notif_action)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun FullScreenIntentStepContent(
    snapshot: PermissionSnapshot,
    onOpenFullScreenSettings: () -> Unit
) {
    Text(
        text = stringResource(R.string.onboarding_fsi_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        text = stringResource(R.string.onboarding_fsi_body),
        style = MaterialTheme.typography.bodyLarge
    )
    if (!snapshot.isFullScreenIntentRequired) {
        Text(
            text = stringResource(R.string.onboarding_fsi_auto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PermissionStatusCard(granted = snapshot.isFullScreenIntentEffectivelyGranted)

    if (!snapshot.isFullScreenIntentEffectivelyGranted) {
        Button(
            onClick = onOpenFullScreenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_fsi_action),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun BluetoothConnectStepContent(
    snapshot: PermissionSnapshot,
    onRequestBluetooth: () -> Unit
) {
    Text(
        text = stringResource(R.string.onboarding_bt_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        text = stringResource(R.string.onboarding_bt_body),
        style = MaterialTheme.typography.bodyLarge
    )
    if (!snapshot.isBluetoothConnectRequired) {
        Text(
            text = stringResource(R.string.onboarding_bt_auto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PermissionStatusCard(
        granted = snapshot.isBluetoothConnectEffectivelyGranted,
        optional = true
    )

    if (snapshot.isBluetoothConnectRequired && !snapshot.bluetoothConnectGranted) {
        Button(
            onClick = onRequestBluetooth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_bt_action),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun BatteryGuidanceStepContent(
    ignoringBatteryOptimizations: Boolean,
    onOpenBatterySettings: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    Text(
        text = stringResource(R.string.onboarding_battery_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.onboarding_battery_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(18.dp)
        )
    }

    if (ignoringBatteryOptimizations) {
        StatusPill(
            text = stringResource(R.string.onboarding_battery_already_unrestricted),
            containerColor = statusColors.successContainer,
            contentColor = statusColors.onSuccessContainer
        )
    }

    Button(
        onClick = onOpenBatterySettings,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_battery_action),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun AppPickerStepContent(
    apps: List<InstalledAppInfo>,
    searchQuery: String,
    selectedPackages: Set<String>,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredApps = remember(apps, searchQuery, selectedPackages) {
        LauncherAppQuery.filterAndSortApps(apps, searchQuery, selectedPackages)
    }

    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_apps_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.onboarding_apps_body),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(R.string.onboarding_apps_selected_count, selectedPackages.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.common_search_apps)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        if (filteredApps.isEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_apps_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    val checked = app.packageName in selectedPackages
                    AppToggleRow(
                        app = app,
                        checked = checked,
                        onToggle = { onTogglePackage(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppToggleRow(
    app: InstalledAppInfo,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (checked) 2.dp else 1.dp,
            color = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TestCallStepContent(
    triggered: Boolean,
    onTriggerTestCall: () -> Unit,
    onOpenVoiceSpeech: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    Text(
        text = stringResource(R.string.onboarding_test_title),
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        text = stringResource(R.string.onboarding_test_body),
        style = MaterialTheme.typography.bodyLarge
    )
    Button(
        onClick = onTriggerTestCall,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(imageVector = Icons.Rounded.Phone, contentDescription = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.onboarding_test_action),
            style = MaterialTheme.typography.labelLarge
        )
    }

    OutlinedButton(
        onClick = onOpenVoiceSpeech,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.onboarding_test_voice_button),
            style = MaterialTheme.typography.labelLarge
        )
    }

    if (triggered) {
        StatusPill(
            text = stringResource(R.string.onboarding_test_triggered),
            containerColor = statusColors.successContainer,
            contentColor = statusColors.onSuccessContainer
        )
    }
}

@Composable
private fun PermissionStatusCard(
    granted: Boolean,
    optional: Boolean = false
) {
    val statusColors = LocalNotifyRingStatusColors.current
    val (bg, fg, label) = when {
        granted -> Triple(
            statusColors.successContainer,
            statusColors.onSuccessContainer,
            stringResource(R.string.common_granted)
        )
        optional -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.common_optional)
        )
        else -> Triple(
            statusColors.warningContainer,
            statusColors.onWarningContainer,
            stringResource(R.string.common_not_granted)
        )
    }
    StatusPill(text = label, containerColor = bg, contentColor = fg, isSuccess = granted)
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    isSuccess: Boolean = true
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = contentColor
            )
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    wizardState: OnboardingWizardState,
    snapshot: PermissionSnapshot,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val step = wizardState.currentStep
    val isOptionalStep = step == OnboardingStep.BLUETOOTH_CONNECT ||
        step == OnboardingStep.BATTERY_OPTIMIZATION
    val canAdvance = OnboardingStateMachine.canAdvance(wizardState, snapshot)
    val canFinish = OnboardingStateMachine.canFinish(wizardState, snapshot)

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (OnboardingStateMachine.previousStep(step) != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_back),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            when {
                step == OnboardingStep.TEST_CALL -> {
                    Button(
                        onClick = onFinish,
                        enabled = canFinish,
                        modifier = Modifier
                            .weight(1.5f)
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_done),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                isOptionalStep && !canAdvance -> {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .weight(1.5f)
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_skip),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onContinue,
                        enabled = canAdvance,
                        modifier = Modifier
                            .weight(1.5f)
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_continue),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
