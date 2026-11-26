package com.notifyring.app.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.notifyring.app.data.DataStoreSettingsRepository
import com.notifyring.app.data.PinHasher
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaregiverPinGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shouldBypassGate_bypassesOnlyWhenNoPinConfigured() {
        assertTrue(CaregiverPinGateMachine.shouldBypassGate(hasCaregiverPin = false))
        assertFalse(CaregiverPinGateMachine.shouldBypassGate(hasCaregiverPin = true))
    }

    @Test
    fun isValidPinFormat_acceptsExactlyFourDigitsOnly() {
        assertTrue(CaregiverPinGateMachine.isValidPinFormat("0000"))
        assertTrue(CaregiverPinGateMachine.isValidPinFormat("1234"))
        assertTrue(CaregiverPinGateMachine.isValidPinFormat("9876"))

        assertFalse(CaregiverPinGateMachine.isValidPinFormat(""))
        assertFalse(CaregiverPinGateMachine.isValidPinFormat("123"))
        assertFalse(CaregiverPinGateMachine.isValidPinFormat("12345"))
        assertFalse(CaregiverPinGateMachine.isValidPinFormat("12a4"))
        assertFalse(CaregiverPinGateMachine.isValidPinFormat("12 4"))
    }

    @Test
    fun digitEntryBackspaceAndClear_manageDigitsAndIgnoreInvalidInputs() {
        var state = CaregiverPinGateState(error = PinGateError.INVALID_PIN)

        // Non-digit ignored.
        state = CaregiverPinGateMachine.appendDigit(state, 'x')
        assertEquals("", state.enteredDigits)
        assertEquals(PinGateError.INVALID_PIN, state.error)

        // Valid digit appends and clears prior error.
        state = CaregiverPinGateMachine.appendDigit(state, '1')
        assertEquals("1", state.enteredDigits)
        assertEquals(PinGateError.NONE, state.error)
        assertFalse(CaregiverPinGateMachine.isReadyToSubmit(state))

        state = CaregiverPinGateMachine.appendDigit(state, '2')
        state = CaregiverPinGateMachine.appendDigit(state, '3')
        state = CaregiverPinGateMachine.appendDigit(state, '4')
        assertEquals("1234", state.enteredDigits)
        assertTrue(CaregiverPinGateMachine.isReadyToSubmit(state))

        // Fifth digit is ignored at max length.
        state = CaregiverPinGateMachine.appendDigit(state, '5')
        assertEquals("1234", state.enteredDigits)

        // Backspace removes last digit.
        state = CaregiverPinGateMachine.backspace(state)
        assertEquals("123", state.enteredDigits)
        assertFalse(CaregiverPinGateMachine.isReadyToSubmit(state))

        // Clear empties all digits.
        state = CaregiverPinGateMachine.clear(state)
        assertEquals("", state.enteredDigits)

        // Backspace on empty is a no-op.
        assertEquals(state, CaregiverPinGateMachine.backspace(state))
    }

    @Test
    fun keypadActions_areIgnoredWhileVerifying() {
        val verifying = CaregiverPinGateState(enteredDigits = "123", isVerifying = true)

        assertFalse(CaregiverPinGateMachine.isReadyToSubmit(verifying.copy(enteredDigits = "1234")))
        assertEquals(verifying, CaregiverPinGateMachine.appendDigit(verifying, '4'))
        assertEquals(verifying, CaregiverPinGateMachine.backspace(verifying))
        assertEquals(verifying, CaregiverPinGateMachine.clear(verifying))
    }

    @Test
    fun submitUnlock_withPinHasher_unlocksValidPinAndHandlesInvalidPinRetry() = runTest {
        val storedHash = PinHasher.hash("2468")
        val verifier: suspend (String) -> Boolean = { candidate ->
            PinHasher.verify(candidate, storedHash)
        }

        // Invalid PIN increments failedAttempts, clears digits, sets INVALID_PIN.
        var state = CaregiverPinGateState(enteredDigits = "1111", isVerifying = true)
        state = CaregiverPinGateMachine.submitUnlock(state, verifier)
        assertFalse(state.isUnlocked)
        assertFalse(state.isVerifying)
        assertEquals("", state.enteredDigits)
        assertEquals(PinGateError.INVALID_PIN, state.error)
        assertEquals(1, state.failedAttempts)

        // Retrying with the correct PIN unlocks cleanly.
        "2468".forEach { digit ->
            state = CaregiverPinGateMachine.appendDigit(state, digit)
        }
        assertEquals(PinGateError.NONE, state.error)
        state = CaregiverPinGateMachine.submitUnlock(state, verifier)
        assertTrue(state.isUnlocked)
        assertFalse(state.isVerifying)
        assertEquals("", state.enteredDigits)
        assertEquals(PinGateError.NONE, state.error)
    }

    @Test
    fun submitUnlock_rejectsMalformedPinWithoutCallingVerifier() = runTest {
        var verifierCalled = false
        val state = CaregiverPinGateState(enteredDigits = "12")

        val result = CaregiverPinGateMachine.submitUnlock(state) {
            verifierCalled = true
            true
        }

        assertFalse(verifierCalled)
        assertFalse(result.isUnlocked)
        assertEquals(PinGateError.INVALID_FORMAT, result.error)
        assertEquals(0, result.failedAttempts)
    }

    @Test
    fun submitUnlock_integratesEndToEndWithSettingsRepository() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempFolder.root, "pin_gate.preferences_pb") }
        )
        val repository = DataStoreSettingsRepository(dataStore)
        repository.setCaregiverPin("7391")

        val failed = CaregiverPinGateMachine.submitUnlock(
            state = CaregiverPinGateState(enteredDigits = "0000"),
            verifyPin = repository::verifyCaregiverPin
        )
        assertFalse(failed.isUnlocked)
        assertEquals(PinGateError.INVALID_PIN, failed.error)
        assertEquals(1, failed.failedAttempts)

        val unlocked = CaregiverPinGateMachine.submitUnlock(
            state = failed.copy(enteredDigits = "7391"),
            verifyPin = repository::verifyCaregiverPin
        )
        assertTrue(unlocked.isUnlocked)
        assertEquals(PinGateError.NONE, unlocked.error)
    }

    @Test
    fun validateNewPinPair_validatesFormatAndMatch() {
        assertEquals(
            PinGateError.NONE,
            CaregiverPinGateMachine.validateNewPinPair("4321", "4321")
        )
        assertEquals(
            PinGateError.MISMATCH,
            CaregiverPinGateMachine.validateNewPinPair("4321", "1234")
        )
        assertEquals(
            PinGateError.INVALID_FORMAT,
            CaregiverPinGateMachine.validateNewPinPair("432", "432")
        )
        assertEquals(
            PinGateError.INVALID_FORMAT,
            CaregiverPinGateMachine.validateNewPinPair("4321", "43a1")
        )
    }
}
