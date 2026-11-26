package com.notifyring.app.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifyring.app.R
import com.notifyring.app.data.SettingsRepository
import com.notifyring.app.data.UserSettings
import com.notifyring.app.speech.CallSpeechEngine
import com.notifyring.app.speech.OfflineVoiceOption
import com.notifyring.app.speech.TtsEngineInfo
import com.notifyring.app.speech.TtsSettingsBridge
import com.notifyring.app.speech.VoiceCatalogFilter
import com.notifyring.app.speech.VoiceCatalogProvider
import com.notifyring.app.speech.VoiceCatalogSnapshot
import com.notifyring.app.speech.VoicePreviewState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSpeechScreen(
    settingsRepository: SettingsRepository,
    voiceCatalogProvider: VoiceCatalogProvider,
    callSpeechEngine: CallSpeechEngine,
    ttsSettingsBridge: TtsSettingsBridge,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
    val previewState by callSpeechEngine.previewState.collectAsStateWithLifecycle()

    var catalog by remember { mutableStateOf(VoiceCatalogSnapshot()) }
    var isRefreshing by remember { mutableStateOf(true) }
    var bridgeUnavailable by remember { mutableStateOf(false) }

    var speechRate by remember(settings.speechRate) {
        mutableFloatStateOf(settings.speechRate)
    }
    var speechPitch by remember(settings.speechPitch) {
        mutableFloatStateOf(settings.speechPitch)
    }

    val sampleText = stringResource(R.string.voice_preview_sample)

    fun triggerRefresh(enginePackage: String? = settings.ttsEnginePackage) {
        scope.launch {
            isRefreshing = true
            catalog = voiceCatalogProvider.refresh(enginePackage)
            isRefreshing = false
        }
    }

    LaunchedEffect(settings.ttsEnginePackage) {
        isRefreshing = true
        catalog = voiceCatalogProvider.refresh(settings.ttsEnginePackage)
        isRefreshing = false
    }

    // Auto-refresh immediately upon returning from Android's voice downloader or TTS settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        triggerRefresh(settings.ttsEnginePackage)
    }

    // Stop any active preview when leaving this screen.
    DisposableEffect(Unit) {
        onDispose {
            callSpeechEngine.stopPreview()
        }
    }

    val resolvedVoice = remember(settings.ttsVoiceName, catalog.offlineVoices) {
        VoiceCatalogFilter.resolveBestVoice(settings.ttsVoiceName, catalog.offlineVoices)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.voice_screen_title),
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
                },
                actions = {
                    IconButton(onClick = { triggerRefresh(settings.ttsEnginePackage) }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.voice_refresh_content_desc)
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
            item(key = "subtitle") {
                Text(
                    text = stringResource(R.string.voice_screen_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "engine_section") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.voice_section_engine),
                            style = MaterialTheme.typography.titleLarge
                        )

                        EngineOptionRow(
                            label = stringResource(R.string.voice_engine_system_default),
                            subtitle = catalog.defaultEnginePackage,
                            isDefaultBadge = false,
                            selected = settings.ttsEnginePackage == null,
                            onSelect = {
                                callSpeechEngine.stopPreview()
                                scope.launch {
                                    settingsRepository.setTtsSelection(
                                        enginePackage = null,
                                        voiceName = null
                                    )
                                }
                            }
                        )

                        catalog.engines.forEach { engine: TtsEngineInfo ->
                            EngineOptionRow(
                                label = engine.label,
                                subtitle = engine.packageName,
                                isDefaultBadge = engine.isDefault,
                                selected = settings.ttsEnginePackage == engine.packageName,
                                onSelect = {
                                    callSpeechEngine.stopPreview()
                                    scope.launch {
                                        settingsRepository.setTtsSelection(
                                            enginePackage = engine.packageName,
                                            voiceName = null
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item(key = "tuning_section") {
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
                            text = stringResource(R.string.voice_section_tuning),
                            style = MaterialTheme.typography.titleLarge
                        )

                        Column {
                            Text(
                                text = stringResource(R.string.voice_rate_label, speechRate),
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
                                steps = 29,
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.voice_pitch_label, speechPitch),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Slider(
                                value = speechPitch,
                                onValueChange = { raw ->
                                    speechPitch = (raw * 20f).roundToInt() / 20f
                                },
                                onValueChangeFinished = {
                                    val pitch = speechPitch
                                    scope.launch { settingsRepository.setSpeechPitch(pitch) }
                                },
                                valueRange = UserSettings.MIN_SPEECH_PITCH..UserSettings.MAX_SPEECH_PITCH,
                                steps = 11,
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }

                        val isAnyPreviewActive = previewState is VoicePreviewState.Loading ||
                            previewState is VoicePreviewState.Speaking

                        Button(
                            onClick = {
                                if (isAnyPreviewActive) {
                                    callSpeechEngine.stopPreview()
                                } else {
                                    callSpeechEngine.previewVoice(
                                        enginePackage = settings.ttsEnginePackage,
                                        voiceName = resolvedVoice?.name ?: settings.ttsVoiceName,
                                        rate = speechRate,
                                        pitch = speechPitch,
                                        sampleText = sampleText
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = if (isAnyPreviewActive) {
                                    Icons.Rounded.Close
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isAnyPreviewActive) {
                                    stringResource(R.string.voice_stop_button)
                                } else {
                                    stringResource(R.string.voice_preview_current_button)
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item(key = "voices_header") {
                Text(
                    text = stringResource(R.string.voice_section_voices),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (isRefreshing && !catalog.isLoaded) {
                item(key = "voices_loading") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.voice_loading),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            } else if (catalog.offlineVoices.isEmpty()) {
                item(key = "voices_empty") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.voice_empty_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = stringResource(R.string.voice_empty_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = catalog.offlineVoices,
                    key = { it.name }
                ) { voice ->
                    val isSelected = resolvedVoice?.name == voice.name
                    val isPreviewingThis = when (val state = previewState) {
                        is VoicePreviewState.Loading -> state.voiceName == voice.name
                        is VoicePreviewState.Speaking -> state.voiceName == voice.name
                        else -> false
                    }
                    OfflineVoiceRow(
                        voice = voice,
                        selected = isSelected,
                        isPreviewing = isPreviewingThis,
                        onSelect = {
                            scope.launch {
                                settingsRepository.setTtsVoiceName(voice.name)
                            }
                        },
                        onTogglePreview = {
                            if (isPreviewingThis) {
                                callSpeechEngine.stopPreview()
                            } else {
                                callSpeechEngine.previewVoice(
                                    enginePackage = settings.ttsEnginePackage,
                                    voiceName = voice.name,
                                    rate = speechRate,
                                    pitch = speechPitch,
                                    sampleText = sampleText
                                )
                            }
                        }
                    )
                }
            }

            item(key = "bridge_section") {
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
                            text = stringResource(R.string.voice_section_bridge),
                            style = MaterialTheme.typography.titleLarge
                        )

                        Button(
                            onClick = {
                                callSpeechEngine.stopPreview()
                                val opened = ttsSettingsBridge.openInstallVoiceData(
                                    context = context,
                                    enginePackage = settings.ttsEnginePackage
                                )
                                bridgeUnavailable = !opened
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.voice_download_button),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                callSpeechEngine.stopPreview()
                                val opened = ttsSettingsBridge.openSystemTtsSettings(context)
                                bridgeUnavailable = !opened
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.voice_system_settings_button),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        if (bridgeUnavailable) {
                            Text(
                                text = stringResource(R.string.voice_bridge_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun EngineOptionRow(
    label: String,
    subtitle: String?,
    isDefaultBadge: Boolean,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (isDefaultBadge) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.voice_engine_default_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineVoiceRow(
    voice: OfflineVoiceOption,
    selected: Boolean,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onTogglePreview: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = stringResource(R.string.voice_selected_badge),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = voice.displayLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = voice.localeDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onTogglePreview,
                modifier = Modifier.heightIn(min = 64.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (isPreviewing) Icons.Rounded.Close else Icons.Rounded.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPreviewing) {
                        stringResource(R.string.voice_stop_button)
                    } else {
                        stringResource(R.string.voice_preview_button)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
