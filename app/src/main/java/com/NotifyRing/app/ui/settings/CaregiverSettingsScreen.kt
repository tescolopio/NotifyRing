package com.notifyring.app.ui.settings

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifyring.app.R
import com.notifyring.app.data.AlertCallLogStore
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.data.UserSettings
import com.notifyring.app.ui.onboarding.AppToggleRow
import com.notifyring.app.ui.onboarding.InstalledAppInfo
import com.notifyring.app.ui.onboarding.LauncherAppQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class PinSetupStage {
    HIDDEN,
    ENTER_FIRST,
    CONFIRM_SECOND
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaregiverSettingsScreen(
    settingsRepository: SettingsRepository,
    alertCallLogStore: AlertCallLogStore,
    onNavigateBack: () -> Unit,
    onRerunOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = UserSettings())

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    var debounceSeconds by remember(settings.debounceWindowMs) {
        mutableFloatStateOf(settings.debounceWindowMs / 1000f)
    }
    var ringTimeoutSeconds by remember(settings.ringTimeoutSeconds) {
        mutableFloatStateOf(settings.ringTimeoutSeconds.toFloat())
    }
    var speechRate by remember(settings.speechRate) {
        mutableFloatStateOf(settings.speechRate)
    }

    var pinSetupStage by remember { mutableStateOf(PinSetupStage.HIDDEN) }
    var firstPin by remember { mutableStateOf("") }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(PinGateError.NONE) }

    var showClearPinConfirm by remember { mutableStateOf(false) }
    var showClearLogConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            LauncherAppQuery.queryLaunchableApps(context)
        }
    }

    val filteredApps = remember(installedApps, searchQuery, settings.monitoredPackages) {
        LauncherAppQuery.filterAndSortApps(installedApps, searchQuery, settings.monitoredPackages)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
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
            item(key = "timing_section") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_section_timing),
                            style = MaterialTheme.typography.titleLarge
                        )

                        Column {
                            Text(
                                text = stringResource(
                                    R.string.settings_debounce_label,
                                    debounceSeconds
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = debounceSeconds,
                                onValueChange = { raw ->
                                    debounceSeconds = (raw * 2f).roundToInt() / 2f
                                },
                                onValueChangeFinished = {
                                    val ms = (debounceSeconds * 1000f).roundToInt().toLong()
                                    scope.launch { settingsRepository.setDebounceWindowMs(ms) }
                                },
                                valueRange = 0f..5f,
                                steps = 9
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(
                                    R.string.settings_ring_timeout_label,
                                    ringTimeoutSeconds.roundToInt()
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = ringTimeoutSeconds,
                                onValueChange = { raw ->
                                    ringTimeoutSeconds = ((raw / 5f).roundToInt() * 5).toFloat()
                                        .coerceIn(
                                            UserSettings.MIN_RING_TIMEOUT_SECONDS.toFloat(),
                                            UserSettings.MAX_RING_TIMEOUT_SECONDS.toFloat()
                                        )
                                },
                                onValueChangeFinished = {
                                    val secs = ringTimeoutSeconds.roundToInt()
                                    scope.launch { settingsRepository.setRingTimeoutSeconds(secs) }
                                },
                                valueRange = UserSettings.MIN_RING_TIMEOUT_SECONDS.toFloat()..
                                    UserSettings.MAX_RING_TIMEOUT_SECONDS.toFloat(),
                                steps = 20
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_auto_speaker_label),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.settings_auto_speaker_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = settings.autoSpeakerEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { settingsRepository.setAutoSpeakerEnabled(enabled) }
                                }
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(
                                    R.string.settings_speech_rate_label,
                                    speechRate
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = speechRate,
                                onValueChange = { raw ->
                                    speechRate = (raw * 20f).roundToInt() / 20f
                                },
                                onValueChangeFinished = {
                                    val rate = speechRate
                                    scope.launch { settingsRepository.setSpeechRate(rate) }
                                },
                                valueRange = UserSettings.MIN_SPEECH_RATE..UserSettings.MAX_SPEECH_RATE,
                                steps = 29
                            )
                        }
                    }
                }
            }

            item(key = "pin_section") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_section_pin),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (settings.hasCaregiverPin) {
                                stringResource(R.string.settings_pin_status_set)
                            } else {
                                stringResource(R.string.settings_pin_status_unset)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                firstPin = ""
                                pinEntry = ""
                                pinError = PinGateError.NONE
                                pinSetupStage = PinSetupStage.ENTER_FIRST
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (settings.hasCaregiverPin) {
                                    stringResource(R.string.settings_pin_change_button)
                                } else {
                                    stringResource(R.string.settings_pin_set_button)
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (settings.hasCaregiverPin) {
                            OutlinedButton(
                                onClick = { showClearPinConfirm = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_pin_clear_button),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item(key = "maintenance_section") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_section_maintenance),
                            style = MaterialTheme.typography.titleLarge
                        )
                        OutlinedButton(
                            onClick = onRerunOnboarding,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.settings_rerun_onboarding),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        OutlinedButton(
                            onClick = { showClearLogConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(imageVector = Icons.Rounded.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.settings_clear_log),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item(key = "apps_header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_section_apps,
                            settings.monitoredPackages.size
                        ),
                        style = MaterialTheme.typography.titleLarge
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.common_search_apps)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            items(
                items = filteredApps,
                key = { it.packageName }
            ) { app ->
                val checked = app.packageName in settings.monitoredPackages
                AppToggleRow(
                    app = app,
                    checked = checked,
                    onToggle = {
                        scope.launch {
                            if (checked) {
                                settingsRepository.removeMonitoredPackage(app.packageName)
                            } else {
                                settingsRepository.addMonitoredPackage(app.packageName)
                            }
                        }
                    }
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (pinSetupStage != PinSetupStage.HIDDEN) {
        val isConfirm = pinSetupStage == PinSetupStage.CONFIRM_SECOND
        CaregiverPinPadDialog(
            title = if (isConfirm) {
                stringResource(R.string.settings_pin_confirm_title)
            } else {
                stringResource(R.string.settings_pin_create_title)
            },
            subtitle = stringResource(R.string.pin_dialog_subtitle),
            enteredCount = pinEntry.length,
            error = pinError,
            onDigit = { digit ->
                if (pinEntry.length < CAREGIVER_PIN_LENGTH) {
                    val next = pinEntry + digit
                    pinEntry = next
                    pinError = PinGateError.NONE
                    if (next.length == CAREGIVER_PIN_LENGTH) {
                        if (!isConfirm) {
                            firstPin = next
                            pinEntry = ""
                            pinSetupStage = PinSetupStage.CONFIRM_SECOND
                        } else {
                            val validation = CaregiverPinGateMachine.validateNewPinPair(firstPin, next)
                            if (validation == PinGateError.NONE) {
                                val pinToSave = next
                                pinSetupStage = PinSetupStage.HIDDEN
                                firstPin = ""
                                pinEntry = ""
                                scope.launch { settingsRepository.setCaregiverPin(pinToSave) }
                            } else {
                                pinError = validation
                                firstPin = ""
                                pinEntry = ""
                                pinSetupStage = PinSetupStage.ENTER_FIRST
                            }
                        }
                    }
                }
            },
            onBackspace = {
                if (pinEntry.isNotEmpty()) {
                    pinEntry = pinEntry.dropLast(1)
                    pinError = PinGateError.NONE
                }
            },
            onClear = {
                pinEntry = ""
                pinError = PinGateError.NONE
            },
            onDismiss = {
                pinSetupStage = PinSetupStage.HIDDEN
                firstPin = ""
                pinEntry = ""
                pinError = PinGateError.NONE
            }
        )
    }

    if (showClearPinConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPinConfirm = false },
            title = { Text(stringResource(R.string.settings_pin_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_pin_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearPinConfirm = false
                        scope.launch { settingsRepository.clearCaregiverPin() }
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearPinConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showClearLogConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLogConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_log_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_log_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLogConfirm = false
                        scope.launch { alertCallLogStore.clear() }
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
