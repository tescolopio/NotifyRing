package com.notifyring.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notifyring.app.R
import kotlinx.coroutines.launch

const val CAREGIVER_PIN_LENGTH: Int = 4

enum class PinGateError {
    NONE,
    INVALID_PIN,
    MISMATCH,
    INVALID_FORMAT
}

/**
 * Immutable state for the 4-digit caregiver PIN gate.
 * Pure Kotlin so NR-08 can unit-test every keypad and verification transition on the JVM.
 */
data class CaregiverPinGateState(
    val enteredDigits: String = "",
    val isVerifying: Boolean = false,
    val error: PinGateError = PinGateError.NONE,
    val failedAttempts: Int = 0,
    val isUnlocked: Boolean = false
)

/**
 * Pure-Kotlin 4-digit PIN gate state machine.
 */
object CaregiverPinGateMachine {

    fun shouldBypassGate(hasCaregiverPin: Boolean): Boolean = !hasCaregiverPin

    fun isValidPinFormat(pin: String): Boolean =
        pin.length == CAREGIVER_PIN_LENGTH && pin.all { it in '0'..'9' }

    fun isReadyToSubmit(state: CaregiverPinGateState): Boolean =
        state.enteredDigits.length == CAREGIVER_PIN_LENGTH && !state.isVerifying

    fun appendDigit(state: CaregiverPinGateState, digit: Char): CaregiverPinGateState {
        if (state.isVerifying || digit !in '0'..'9') return state
        if (state.enteredDigits.length >= CAREGIVER_PIN_LENGTH) return state
        return state.copy(
            enteredDigits = state.enteredDigits + digit,
            error = PinGateError.NONE
        )
    }

    fun backspace(state: CaregiverPinGateState): CaregiverPinGateState {
        if (state.isVerifying || state.enteredDigits.isEmpty()) return state
        return state.copy(
            enteredDigits = state.enteredDigits.dropLast(1),
            error = PinGateError.NONE
        )
    }

    fun clear(state: CaregiverPinGateState): CaregiverPinGateState {
        if (state.isVerifying) return state
        return state.copy(
            enteredDigits = "",
            error = PinGateError.NONE
        )
    }

    suspend fun submitUnlock(
        state: CaregiverPinGateState,
        verifyPin: suspend (String) -> Boolean
    ): CaregiverPinGateState {
        if (!isValidPinFormat(state.enteredDigits)) {
            return state.copy(
                error = PinGateError.INVALID_FORMAT,
                isVerifying = false,
                isUnlocked = false
            )
        }
        val matched = verifyPin(state.enteredDigits)
        return if (matched) {
            state.copy(
                enteredDigits = "",
                isVerifying = false,
                error = PinGateError.NONE,
                isUnlocked = true
            )
        } else {
            state.copy(
                enteredDigits = "",
                isVerifying = false,
                error = PinGateError.INVALID_PIN,
                failedAttempts = state.failedAttempts + 1,
                isUnlocked = false
            )
        }
    }

    /**
     * Validates a two-step "Create PIN" -> "Confirm PIN" flow.
     * Returns [PinGateError.NONE] if both are valid 4-digit PINs and identical.
     */
    fun validateNewPinPair(firstPin: String, confirmPin: String): PinGateError {
        if (!isValidPinFormat(firstPin) || !isValidPinFormat(confirmPin)) {
            return PinGateError.INVALID_FORMAT
        }
        return if (firstPin == confirmPin) PinGateError.NONE else PinGateError.MISMATCH
    }
}

/**
 * High-contrast 4-digit Caregiver PIN unlock dialog with oversized (>=64dp) keypad buttons.
 */
@Composable
fun CaregiverPinUnlockDialog(
    onVerifyPin: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var gateState by remember { mutableStateOf(CaregiverPinGateState()) }

    CaregiverPinPadDialog(
        title = stringResource(R.string.pin_dialog_title),
        subtitle = stringResource(R.string.pin_dialog_subtitle),
        enteredCount = gateState.enteredDigits.length,
        error = gateState.error,
        enabled = !gateState.isVerifying,
        onDigit = { digit ->
            val updated = CaregiverPinGateMachine.appendDigit(gateState, digit)
            gateState = updated
            if (CaregiverPinGateMachine.isReadyToSubmit(updated)) {
                gateState = updated.copy(isVerifying = true)
                scope.launch {
                    val result = CaregiverPinGateMachine.submitUnlock(updated, onVerifyPin)
                    gateState = result
                    if (result.isUnlocked) {
                        onUnlocked()
                    }
                }
            }
        },
        onBackspace = { gateState = CaregiverPinGateMachine.backspace(gateState) },
        onClear = { gateState = CaregiverPinGateMachine.clear(gateState) },
        onDismiss = onDismiss
    )
}

/**
 * Reusable 4-digit PIN entry dialog used for both unlocking Caregiver Settings and creating/changing
 * a 4-digit PIN.
 */
@Composable
fun CaregiverPinPadDialog(
    title: String,
    subtitle: String,
    enteredCount: Int,
    error: PinGateError,
    enabled: Boolean = true,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                PinDotRow(enteredCount = enteredCount)

                Spacer(modifier = Modifier.height(12.dp))
                val errorText = when (error) {
                    PinGateError.NONE -> null
                    PinGateError.INVALID_PIN -> stringResource(R.string.pin_error_invalid)
                    PinGateError.MISMATCH -> stringResource(R.string.pin_error_mismatch)
                    PinGateError.INVALID_FORMAT -> stringResource(R.string.pin_error_format)
                }
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                val keypadRows = listOf(
                    listOf('1', '2', '3'),
                    listOf('4', '5', '6'),
                    listOf('7', '8', '9')
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    keypadRows.forEach { rowDigits ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowDigits.forEach { digit ->
                                PinDigitButton(
                                    digit = digit,
                                    enabled = enabled,
                                    onClick = { onDigit(digit) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onClear,
                            enabled = enabled && enteredCount > 0,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.common_clear),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        PinDigitButton(
                            digit = '0',
                            enabled = enabled,
                            onClick = { onDigit('0') },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onBackspace,
                            enabled = enabled && enteredCount > 0,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.pin_backspace),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDotRow(enteredCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            contentDescription = "$enteredCount of $CAREGIVER_PIN_LENGTH digits entered"
        }
    ) {
        repeat(CAREGIVER_PIN_LENGTH) { index ->
            val filled = index < enteredCount
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    )
                    .border(
                        width = 2.dp,
                        color = if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun PinDigitButton(
    digit: Char,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.headlineSmall
        )
    }
}
