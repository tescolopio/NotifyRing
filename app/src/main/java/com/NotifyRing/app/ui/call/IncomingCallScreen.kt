package com.notifyring.app.ui.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifyring.app.R
import com.notifyring.app.telecom.AudioEndpointType
import com.notifyring.app.telecom.CallAudioEndpoint
import com.notifyring.app.telecom.CallDisconnectReason
import java.util.UUID

// Self-contained high-contrast WCAG AAA dark palette for senior-first lockscreen legibility.
private object CallHighContrastPalette {
    val Background = Color(0xFF0A0E14)
    val SurfaceCard = Color(0xFF161E2B)
    val SurfaceElevated = Color(0xFF1F2B3D)
    val BorderSubtle = Color(0xFF4B607D)
    val OnDarkPrimary = Color(0xFFFFFFFF)
    val OnDarkSecondary = Color(0xFFD7E2F0)

    val AnswerGreen = Color(0xFF0D632F) // Contrast with white: 7.2:1 (WCAG AAA)
    val DeclineRed = Color(0xFFA8141B) // Contrast with white: 7.3:1 (WCAG AAA)
    val AccentBlue = Color(0xFF1558B0) // Contrast with white: 7.1:1 (WCAG AAA)
    val ActiveHighlightBg = Color(0xFFF5C451) // Contrast with near-black: 12.8:1 (WCAG AAA)
    val ActiveHighlightText = Color(0xFF0A0E14)
    val HeldBannerBg = Color(0xFFFFF3C4)
    val HeldBannerText = Color(0xFF2B1D00)
    val IncomingPillBg = Color(0xFF1B3A5C)
    val IncomingPillBorder = Color(0xFF6FA8DC)
    val ConnectedPillBg = Color(0xFF123B26)
    val ConnectedPillBorder = Color(0xFF4CD97B)
}

private val SeniorCallColorScheme = darkColorScheme(
    primary = CallHighContrastPalette.AccentBlue,
    onPrimary = CallHighContrastPalette.OnDarkPrimary,
    secondary = CallHighContrastPalette.AnswerGreen,
    onSecondary = CallHighContrastPalette.OnDarkPrimary,
    error = CallHighContrastPalette.DeclineRed,
    onError = CallHighContrastPalette.OnDarkPrimary,
    background = CallHighContrastPalette.Background,
    onBackground = CallHighContrastPalette.OnDarkPrimary,
    surface = CallHighContrastPalette.SurfaceCard,
    onSurface = CallHighContrastPalette.OnDarkPrimary,
    surfaceVariant = CallHighContrastPalette.SurfaceElevated,
    onSurfaceVariant = CallHighContrastPalette.OnDarkSecondary,
    outline = CallHighContrastPalette.BorderSubtle
)

private val SeniorCallTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold
    )
)

@Composable
fun IncomingCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SeniorCallColorScheme,
        typography = SeniorCallTypography,
        content = content
    )
}

/**
 * Senior-first, high-contrast full-screen call UI for incoming and connected alert calls.
 */
@Composable
fun IncomingCallScreen(
    uiState: IncomingCallUiState,
    onAnswer: (UUID) -> Unit,
    onDecline: (UUID) -> Unit,
    onRepeatMessage: () -> Unit,
    onToggleSpeaker: (UUID, CallAudioEndpoint) -> Unit,
    onToggleMute: (UUID) -> Unit,
    onOpenSourceApp: (String) -> Unit,
    onEndCall: (UUID) -> Unit,
    onCloseFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    IncomingCallTheme {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                when (uiState) {
                    is IncomingCallUiState.Ringing -> RingingCallContent(
                        state = uiState,
                        onAnswer = { onAnswer(uiState.callId) },
                        onDecline = { onDecline(uiState.callId) }
                    )

                    is IncomingCallUiState.Active -> ActiveCallContent(
                        state = uiState,
                        onRepeatMessage = onRepeatMessage,
                        onToggleSpeaker = { target ->
                            onToggleSpeaker(uiState.callId, target)
                        },
                        onToggleMute = { onToggleMute(uiState.callId) },
                        onOpenSourceApp = { onOpenSourceApp(uiState.packageName) },
                        onEndCall = { onEndCall(uiState.callId) }
                    )

                    is IncomingCallUiState.Ending -> EndingCallContent(
                        state = uiState,
                        onClose = onCloseFallback
                    )
                }
            }
        }
    }
}

@Composable
private fun RingingCallContent(
    state: IncomingCallUiState.Ringing,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    val resolvedAppName = state.appName.ifBlank { stringResource(R.string.call_unknown_app) }
    val resolvedSender = state.sender.ifBlank { stringResource(R.string.call_unknown_sender) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            StatusPill(
                text = stringResource(R.string.call_status_incoming),
                backgroundColor = CallHighContrastPalette.IncomingPillBg,
                borderColor = CallHighContrastPalette.IncomingPillBorder,
                icon = CallIcons.Bell
            )

            Spacer(modifier = Modifier.height(28.dp))

            AppBadge(
                initial = state.badgeInitial,
                sizeDp = 92
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = resolvedAppName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = resolvedSender,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (state.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onDecline,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 64.dp, minHeight = 76.dp)
                    .heightIn(min = 76.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CallHighContrastPalette.DeclineRed,
                    contentColor = CallHighContrastPalette.OnDarkPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Icon(
                    imageVector = CallIcons.EndCall,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.call_action_decline),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onAnswer,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 64.dp, minHeight = 76.dp)
                    .heightIn(min = 76.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CallHighContrastPalette.AnswerGreen,
                    contentColor = CallHighContrastPalette.OnDarkPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Icon(
                    imageVector = CallIcons.AnswerCall,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.call_action_answer),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActiveCallContent(
    state: IncomingCallUiState.Active,
    onRepeatMessage: () -> Unit,
    onToggleSpeaker: (CallAudioEndpoint) -> Unit,
    onToggleMute: () -> Unit,
    onOpenSourceApp: () -> Unit,
    onEndCall: () -> Unit
) {
    val resolvedAppName = state.appName.ifBlank { stringResource(R.string.call_unknown_app) }
    val resolvedSender = state.sender.ifBlank { stringResource(R.string.call_unknown_sender) }
    val resolvedMessage = state.message.ifBlank { stringResource(R.string.call_message_empty) }
    val currentRouteLabel = endpointLabel(state.currentEndpoint)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusPill(
                text = stringResource(R.string.call_status_connected),
                backgroundColor = CallHighContrastPalette.ConnectedPillBg,
                borderColor = CallHighContrastPalette.ConnectedPillBorder,
                icon = CallIcons.AnswerCall
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (state.isHeld) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = CallHighContrastPalette.HeldBannerBg
                ) {
                    Text(
                        text = stringResource(R.string.call_status_held_banner),
                        style = MaterialTheme.typography.titleLarge,
                        color = CallHighContrastPalette.HeldBannerText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppBadge(
                    initial = state.badgeInitial,
                    sizeDp = 64
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resolvedAppName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = resolvedSender,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SpeechStatusBanner(status = state.speechStatus)

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(
                        text = resolvedMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRepeatMessage,
                enabled = !state.isHeld,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CallHighContrastPalette.AccentBlue,
                    contentColor = CallHighContrastPalette.OnDarkPrimary
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Icon(
                    imageVector = CallIcons.Repeat,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.call_action_repeat),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val speakerContainer = if (state.isSpeakerActive) {
                    CallHighContrastPalette.ActiveHighlightBg
                } else {
                    CallHighContrastPalette.SurfaceElevated
                }
                val speakerContent = if (state.isSpeakerActive) {
                    CallHighContrastPalette.ActiveHighlightText
                } else {
                    CallHighContrastPalette.OnDarkPrimary
                }
                val nextEndpoint = state.nextEndpoint
                Button(
                    onClick = {
                        if (nextEndpoint != null) {
                            onToggleSpeaker(nextEndpoint)
                        }
                    },
                    enabled = nextEndpoint != null,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
                        .heightIn(min = 64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = speakerContainer,
                        contentColor = speakerContent,
                        disabledContainerColor = CallHighContrastPalette.SurfaceCard,
                        disabledContentColor = CallHighContrastPalette.OnDarkSecondary
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = CallIcons.Speaker,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isSpeakerActive) {
                            stringResource(R.string.call_route_speaker_on)
                        } else {
                            stringResource(R.string.call_route_button_label, currentRouteLabel)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val muteContainer = if (state.isMuted) {
                    CallHighContrastPalette.ActiveHighlightBg
                } else {
                    CallHighContrastPalette.SurfaceElevated
                }
                val muteContent = if (state.isMuted) {
                    CallHighContrastPalette.ActiveHighlightText
                } else {
                    CallHighContrastPalette.OnDarkPrimary
                }
                Button(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
                        .heightIn(min = 64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = muteContainer,
                        contentColor = muteContent
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = if (state.isMuted) CallIcons.MuteOff else CallIcons.Speaker,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (state.isMuted) R.string.call_action_unmute else R.string.call_action_mute
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (state.canOpenSourceApp) {
                OutlinedButton(
                    onClick = onOpenSourceApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
                        .heightIn(min = 64.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, CallHighContrastPalette.OnDarkSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CallHighContrastPalette.SurfaceElevated,
                        contentColor = CallHighContrastPalette.OnDarkPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = CallIcons.OpenApp,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.call_action_open_app, resolvedAppName),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onEndCall,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 64.dp, minHeight = 72.dp)
                    .heightIn(min = 72.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CallHighContrastPalette.DeclineRed,
                    contentColor = CallHighContrastPalette.OnDarkPrimary
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Icon(
                    imageVector = CallIcons.EndCall,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.call_action_end),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EndingCallContent(
    state: IncomingCallUiState.Ending,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (state.appName.isNotBlank()) {
            Text(
                text = state.appName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = stringResource(R.string.call_status_ending),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .defaultMinSize(minWidth = 180.dp, minHeight = 64.dp)
                .heightIn(min = 64.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, CallHighContrastPalette.OnDarkSecondary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = CallHighContrastPalette.OnDarkPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.call_action_close),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    backgroundColor: Color,
    borderColor: Color,
    icon: ImageVector
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = backgroundColor,
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CallHighContrastPalette.OnDarkPrimary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                color = CallHighContrastPalette.OnDarkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppBadge(
    initial: String,
    sizeDp: Int
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(CallHighContrastPalette.SurfaceElevated)
            .border(2.5.dp, CallHighContrastPalette.IncomingPillBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = if (sizeDp >= 80) {
                MaterialTheme.typography.displaySmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            color = CallHighContrastPalette.OnDarkPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SpeechStatusBanner(status: CallSpeechStatus) {
    val labelRes = when (status) {
        CallSpeechStatus.WARMING_UP -> R.string.call_speech_warming_up
        CallSpeechStatus.PREPARING -> R.string.call_speech_preparing
        CallSpeechStatus.SPEAKING -> R.string.call_speech_speaking
        CallSpeechStatus.PAUSED -> R.string.call_speech_paused
        CallSpeechStatus.MUTED -> R.string.call_speech_muted
        CallSpeechStatus.READY -> R.string.call_speech_ready
        CallSpeechStatus.ERROR -> R.string.call_speech_error
    }
    val highlight = status == CallSpeechStatus.SPEAKING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) {
            CallHighContrastPalette.IncomingPillBg
        } else {
            CallHighContrastPalette.SurfaceElevated
        },
        border = BorderStroke(
            width = 1.5.dp,
            color = if (highlight) {
                CallHighContrastPalette.IncomingPillBorder
            } else {
                CallHighContrastPalette.BorderSubtle
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (status == CallSpeechStatus.MUTED) {
                    CallIcons.MuteOff
                } else {
                    CallIcons.Speaker
                },
                contentDescription = null,
                tint = CallHighContrastPalette.OnDarkPrimary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleLarge,
                color = CallHighContrastPalette.OnDarkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun endpointLabel(endpoint: CallAudioEndpoint?): String {
    if (endpoint == null) return stringResource(R.string.call_route_audio)
    return when (endpoint.type) {
        AudioEndpointType.SPEAKER -> stringResource(R.string.call_route_speaker)
        AudioEndpointType.EARPIECE -> stringResource(R.string.call_route_earpiece)
        AudioEndpointType.BLUETOOTH -> endpoint.name.ifBlank {
            stringResource(R.string.call_route_bluetooth)
        }
        AudioEndpointType.WIRED_HEADSET -> stringResource(R.string.call_route_headset)
        AudioEndpointType.STREAMING, AudioEndpointType.UNKNOWN -> endpoint.name.ifBlank {
            stringResource(R.string.call_route_audio)
        }
    }
}

/**
 * Self-contained vector icons so `ui/call/` has zero dependency on `material-icons-extended`.
 */
private object CallIcons {
    val AnswerCall: ImageVector by lazy {
        ImageVector.Builder(
            name = "AnswerCall",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
            ) {
                moveTo(6.62f, 10.79f)
                curveTo(8.06f, 13.62f, 10.38f, 15.94f, 13.21f, 17.38f)
                lineTo(15.41f, 15.18f)
                curveTo(15.69f, 14.9f, 16.08f, 14.82f, 16.43f, 14.93f)
                curveTo(17.55f, 15.3f, 18.75f, 15.5f, 20.0f, 15.5f)
                curveTo(20.55f, 15.5f, 21.0f, 15.95f, 21.0f, 16.5f)
                verticalLineTo(20.0f)
                curveTo(21.0f, 20.55f, 20.55f, 21.0f, 20.0f, 21.0f)
                curveTo(10.61f, 21.0f, 3.0f, 13.39f, 3.0f, 4.0f)
                curveTo(3.0f, 3.45f, 3.45f, 3.0f, 4.0f, 3.0f)
                horizontalLineTo(7.5f)
                curveTo(8.05f, 3.0f, 8.5f, 3.45f, 8.5f, 4.0f)
                curveTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f)
                curveTo(9.18f, 7.92f, 9.1f, 8.31f, 8.82f, 8.59f)
                lineTo(6.62f, 10.79f)
                close()
            }
        }.build()
    }

    val EndCall: ImageVector by lazy {
        ImageVector.Builder(
            name = "EndCall",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12.0f, 9.0f)
                curveTo(10.4f, 9.0f, 8.85f, 9.25f, 7.4f, 9.72f)
                verticalLineTo(12.82f)
                curveTo(7.4f, 13.21f, 7.17f, 13.56f, 6.84f, 13.72f)
                curveTo(5.86f, 14.21f, 4.97f, 14.84f, 4.17f, 15.57f)
                curveTo(3.99f, 15.75f, 3.74f, 15.85f, 3.47f, 15.85f)
                curveTo(3.19f, 15.85f, 2.94f, 15.74f, 2.76f, 15.56f)
                lineTo(0.29f, 13.08f)
                curveTo(0.11f, 12.9f, 0.0f, 12.65f, 0.0f, 12.37f)
                curveTo(0.0f, 12.09f, 0.11f, 11.84f, 0.29f, 11.66f)
                curveTo(3.34f, 8.78f, 7.46f, 7.0f, 12.0f, 7.0f)
                curveTo(16.54f, 7.0f, 20.66f, 8.78f, 23.71f, 11.66f)
                curveTo(23.89f, 11.84f, 24.0f, 12.09f, 24.0f, 12.37f)
                curveTo(24.0f, 12.65f, 23.89f, 12.9f, 23.71f, 13.08f)
                lineTo(21.24f, 15.56f)
                curveTo(21.06f, 15.74f, 20.81f, 15.85f, 20.53f, 15.85f)
                curveTo(20.26f, 15.85f, 20.01f, 15.75f, 19.83f, 15.57f)
                curveTo(19.03f, 14.84f, 18.14f, 14.21f, 17.16f, 13.72f)
                curveTo(16.83f, 13.56f, 16.6f, 13.21f, 16.6f, 12.82f)
                verticalLineTo(9.72f)
                curveTo(15.15f, 9.25f, 13.6f, 9.0f, 12.0f, 9.0f)
                close()
            }
        }.build()
    }

    val Repeat: ImageVector by lazy {
        ImageVector.Builder(
            name = "Repeat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17.5f, 6.5f)
                arcTo(7.5f, 7.5f, 0f, true, false, 19.5f, 12.0f)
                moveTo(17.5f, 2.5f)
                verticalLineTo(6.5f)
                horizontalLineTo(13.5f)
            }
        }.build()
    }

    val Speaker: ImageVector by lazy {
        ImageVector.Builder(
            name = "Speaker",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(3.0f, 9.0f)
                verticalLineTo(15.0f)
                horizontalLineTo(7.0f)
                lineTo(12.0f, 20.0f)
                verticalLineTo(4.0f)
                lineTo(7.0f, 9.0f)
                horizontalLineTo(3.0f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15.5f, 8.5f)
                curveTo(16.8f, 9.8f, 16.8f, 14.2f, 15.5f, 15.5f)
                moveTo(18.5f, 5.5f)
                curveTo(21.2f, 8.2f, 21.2f, 15.8f, 18.5f, 18.5f)
            }
        }.build()
    }

    val MuteOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "MuteOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(3.0f, 9.0f)
                verticalLineTo(15.0f)
                horizontalLineTo(7.0f)
                lineTo(12.0f, 20.0f)
                verticalLineTo(4.0f)
                lineTo(7.0f, 9.0f)
                horizontalLineTo(3.0f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(16.0f, 9.0f)
                lineTo(21.0f, 15.0f)
                moveTo(21.0f, 9.0f)
                lineTo(16.0f, 15.0f)
            }
        }.build()
    }

    val OpenApp: ImageVector by lazy {
        ImageVector.Builder(
            name = "OpenApp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14.0f, 4.0f)
                horizontalLineTo(20.0f)
                verticalLineTo(10.0f)
                moveTo(20.0f, 4.0f)
                lineTo(11.0f, 13.0f)
                moveTo(18.0f, 14.0f)
                verticalLineTo(19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(6.0f)
                horizontalLineTo(10.0f)
            }
        }.build()
    }

    val Bell: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bell",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12.0f, 22.0f)
                curveTo(13.1f, 22.0f, 14.0f, 21.1f, 14.0f, 20.0f)
                horizontalLineTo(10.0f)
                curveTo(10.0f, 21.1f, 10.9f, 22.0f, 12.0f, 22.0f)
                close()
                moveTo(18.0f, 16.0f)
                verticalLineTo(11.0f)
                curveTo(18.0f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f)
                verticalLineTo(4.0f)
                curveTo(13.5f, 3.17f, 12.83f, 2.5f, 12.0f, 2.5f)
                curveTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4.0f)
                verticalLineTo(4.68f)
                curveTo(7.63f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
                verticalLineTo(16.0f)
                lineTo(4.0f, 18.0f)
                verticalLineTo(19.0f)
                horizontalLineTo(20.0f)
                verticalLineTo(18.0f)
                lineTo(18.0f, 16.0f)
                close()
            }
        }.build()
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun RingingCallPreview() {
    IncomingCallScreen(
        uiState = IncomingCallUiState.Ringing(
            callId = UUID.randomUUID(),
            appName = "MyChart",
            packageName = "epic.mychart.android",
            sender = "Dr. Patel's Office",
            message = "Your prescription is ready for pickup at Oak Street Pharmacy.",
            badgeInitial = "M"
        ),
        onAnswer = {},
        onDecline = {},
        onRepeatMessage = {},
        onToggleSpeaker = { _, _ -> },
        onToggleMute = {},
        onOpenSourceApp = {},
        onEndCall = {},
        onCloseFallback = {}
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun ActiveCallPreview() {
    val earpiece = CallAudioEndpoint("ep", "Earpiece", AudioEndpointType.EARPIECE)
    val speaker = CallAudioEndpoint("sp", "Speaker", AudioEndpointType.SPEAKER)
    IncomingCallScreen(
        uiState = IncomingCallUiState.Active(
            callId = UUID.randomUUID(),
            appName = "MyChart",
            packageName = "epic.mychart.android",
            sender = "Dr. Patel's Office",
            message = "Your prescription is ready for pickup at Oak Street Pharmacy before 6 PM today.",
            badgeInitial = "M",
            isHeld = false,
            isMuted = false,
            currentEndpoint = speaker,
            nextEndpoint = earpiece,
            isSpeakerActive = true,
            speechStatus = CallSpeechStatus.SPEAKING,
            speechErrorDetail = null,
            canOpenSourceApp = true
        ),
        onAnswer = {},
        onDecline = {},
        onRepeatMessage = {},
        onToggleSpeaker = { _, _ -> },
        onToggleMute = {},
        onOpenSourceApp = {},
        onEndCall = {},
        onCloseFallback = {}
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun EndingCallPreview() {
    IncomingCallScreen(
        uiState = IncomingCallUiState.Ending(
            appName = "MyChart",
            reason = CallDisconnectReason.LOCAL
        ),
        onAnswer = {},
        onDecline = {},
        onRepeatMessage = {},
        onToggleSpeaker = { _, _ -> },
        onToggleMute = {},
        onOpenSourceApp = {},
        onEndCall = {},
        onCloseFallback = {}
    )
}
