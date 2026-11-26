package com.notifyring.app.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifyring.app.R
import com.notifyring.app.data.AlertCallLogEntry
import com.notifyring.app.data.AlertCallLogStore
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.data.UserSettings
import com.notifyring.app.telecom.LocalCallManager
import com.notifyring.app.ui.onboarding.AndroidPermissionInspector
import com.notifyring.app.ui.onboarding.DegradedPermissionIssue
import com.notifyring.app.ui.onboarding.PermissionSnapshot
import com.notifyring.app.ui.settings.CaregiverPinGateMachine
import com.notifyring.app.ui.settings.CaregiverPinUnlockDialog
import com.notifyring.app.ui.theme.LocalNotifyRingStatusColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeniorHomeScreen(
    settingsRepository: SettingsRepository,
    alertCallLogStore: AlertCallLogStore,
    localCallManager: LocalCallManager,
    onOpenCaregiverSettings: () -> Unit
) {
    val context = LocalContext.current
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
    val recentAlerts by alertCallLogStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())

    var snapshot by remember { mutableStateOf(AndroidPermissionInspector.inspect(context)) }
    var showPinDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        snapshot = AndroidPermissionInspector.inspect(context)
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        snapshot = AndroidPermissionInspector.inspect(context)
    }

    val primaryDegradedIssue = remember(snapshot) {
        HomeReplayHelper.resolvePrimaryDegradedIssue(snapshot)
    }

    val testSender = stringResource(R.string.onboarding_test_sender)
    val testMessage = stringResource(R.string.onboarding_test_message)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "protection_status") {
                ProtectionStatusCard(
                    snapshot = snapshot,
                    monitoredAppCount = settings.monitoredPackages.size
                )
            }

            if (primaryDegradedIssue != null) {
                item(key = "degraded_banner") {
                    DegradedPermissionBanner(
                        issue = primaryDegradedIssue,
                        onFix = {
                            when (primaryDegradedIssue) {
                                DegradedPermissionIssue.NOTIFICATION_LISTENER_DISABLED ->
                                    AndroidPermissionInspector.openNotificationListenerSettings(context)
                                DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        snapshot.notificationsEnabledInSystem
                                    ) {
                                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        AndroidPermissionInspector.openAppNotificationSettings(context)
                                    }
                                }
                                DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED ->
                                    AndroidPermissionInspector.openFullScreenIntentSettings(context)
                                DegradedPermissionIssue.ALERT_CHANNEL_DEGRADED ->
                                    AndroidPermissionInspector.openAlertChannelSettings(context)
                            }
                        }
                    )
                }
            }

            item(key = "primary_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            localCallManager.enqueueCall(
                                HomeReplayHelper.buildTestCallPayload(
                                    packageName = context.packageName,
                                    sender = testSender,
                                    message = testMessage
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.Phone, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.home_test_call_button),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (CaregiverPinGateMachine.shouldBypassGate(settings.hasCaregiverPin)) {
                                onOpenCaregiverSettings()
                            } else {
                                showPinDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            imageVector = if (settings.hasCaregiverPin) Icons.Rounded.Lock
                            else Icons.Rounded.Settings,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.home_caregiver_settings_button),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            item(key = "recent_alerts_header") {
                Text(
                    text = stringResource(R.string.home_recent_alerts_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (recentAlerts.isEmpty()) {
                item(key = "recent_alerts_empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.home_recent_alerts_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else {
                items(
                    items = recentAlerts,
                    key = { it.id }
                ) { entry ->
                    RecentAlertCard(
                        entry = entry,
                        onReplay = {
                            localCallManager.enqueueCall(HomeReplayHelper.buildReplayPayload(entry))
                        }
                    )
                }
            }
        }
    }

    if (showPinDialog) {
        CaregiverPinUnlockDialog(
            onVerifyPin = { pin -> settingsRepository.verifyCaregiverPin(pin) },
            onUnlocked = {
                showPinDialog = false
                onOpenCaregiverSettings()
            },
            onDismiss = { showPinDialog = false }
        )
    }
}

@Composable
private fun ProtectionStatusCard(
    snapshot: PermissionSnapshot,
    monitoredAppCount: Int
) {
    val statusColors = LocalNotifyRingStatusColors.current
    val protected = snapshot.isFullyProtected
    val containerColor = if (protected) statusColors.successContainer else statusColors.dangerContainer
    val contentColor = if (protected) statusColors.onSuccessContainer else statusColors.onDangerContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (protected) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (protected) {
                        stringResource(R.string.home_status_protected_title)
                    } else {
                        stringResource(R.string.home_status_degraded_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (protected) {
                        stringResource(R.string.home_status_protected_subtitle, monitoredAppCount)
                    } else {
                        stringResource(R.string.home_status_degraded_subtitle)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun DegradedPermissionBanner(
    issue: DegradedPermissionIssue,
    onFix: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    val message = when (issue) {
        DegradedPermissionIssue.NOTIFICATION_LISTENER_DISABLED ->
            stringResource(R.string.home_degraded_listener)
        DegradedPermissionIssue.POST_NOTIFICATIONS_DENIED ->
            stringResource(R.string.home_degraded_post_notif)
        DegradedPermissionIssue.FULL_SCREEN_INTENT_DENIED ->
            stringResource(R.string.home_degraded_fsi)
        DegradedPermissionIssue.ALERT_CHANNEL_DEGRADED ->
            stringResource(R.string.home_degraded_channel)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = statusColors.warningContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = statusColors.onWarningContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColors.onWarningContainer
                )
            }
            Button(
                onClick = onFix,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(
                    text = stringResource(R.string.home_fix_now),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun RecentAlertCard(
    entry: AlertCallLogEntry,
    onReplay: () -> Unit
) {
    val statusColors = LocalNotifyRingStatusColors.current
    val formattedTime = remember(entry.timestampMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.timestampMillis))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.appLabel.ifBlank { HomeReplayHelper.DEFAULT_APP_NAME },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (entry.answered) statusColors.successContainer
                    else statusColors.warningContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (entry.answered) {
                            stringResource(R.string.home_alert_answered)
                        } else {
                            stringResource(R.string.home_alert_missed)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.answered) statusColors.onSuccessContainer
                        else statusColors.onWarningContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (entry.sender.isNotBlank()) {
                Text(
                    text = entry.sender,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (entry.messagePreview.isNotBlank()) {
                Text(
                    text = entry.messagePreview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onReplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_call_replay_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
