package com.notifyring.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionDetectorTest {

    @Test
    fun passesThroughNormalVisibleNotificationContent() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "WhatsApp",
                visibility = RedactionDetector.VISIBILITY_PUBLIC,
                extractedTitle = "Mom",
                extractedText = "I made soup for dinner"
            )
        )

        assertFalse(result.isRedacted)
        assertNull(result.fallbackMessage)
        assertNull(result.reason)
    }

    @Test
    fun passesThroughPrivateNotificationWhenRealContentDiffersFromPublicPlaceholder() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "Signal",
                visibility = RedactionDetector.VISIBILITY_PRIVATE,
                extractedTitle = "Dr. Patel",
                extractedText = "Your lab results look great.",
                publicVersionTitle = "Signal",
                publicVersionText = "1 new message"
            )
        )

        assertFalse(result.isRedacted)
        assertNull(result.fallbackMessage)
    }

    @Test
    fun detectsInjectedLocalizedSystemRedactedPhrasesInBody() {
        val detector = RedactionDetector(
            systemRedactedPhrases = setOf(
                "Contenido de notificación confidencial oculto",
                "Texto oculto"
            )
        )

        val otpRedacted = detector.detect(
            RedactionCandidate(
                appName = "Chase",
                visibility = RedactionDetector.VISIBILITY_PRIVATE,
                extractedTitle = "Chase",
                extractedText = "Contenido de notificación confidencial oculto."
            )
        )

        assertTrue(otpRedacted.isRedacted)
        assertEquals(RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER, otpRedacted.reason)
        assertEquals(
            "Private alert from Chase. Open the app to view it.",
            otpRedacted.fallbackMessage
        )

        val hiddenTextRedacted = detector.detect(
            RedactionCandidate(
                appName = "Messages",
                extractedText = "  texto   oculto  "
            )
        )
        assertTrue(hiddenTextRedacted.isRedacted)
        assertEquals(RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER, hiddenTextRedacted.reason)
    }

    @Test
    fun detectsInjectedSystemRedactedPhraseInsideMessagingStyleMessages() {
        val detector = RedactionDetector(
            systemRedactedPhrases = setOf("Sensible Benachrichtigungsinhalte ausgeblendet")
        )

        val result = detector.detect(
            RedactionCandidate(
                appName = "Messages",
                extractedTitle = "Bank OTP",
                extractedText = "Sensible Benachrichtigungsinhalte ausgeblendet",
                extractedMessages = listOf("Sensible Benachrichtigungsinhalte ausgeblendet")
            )
        )

        assertTrue(result.isRedacted)
        assertEquals(RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER, result.reason)
        assertEquals(
            "Private alert from Messages. Open the app to view it.",
            result.fallbackMessage
        )
    }

    @Test
    fun detectsKnownOemFallbackRedactedPhrasesCaseInsensitively() {
        val detector = RedactionDetector(systemRedactedPhrases = emptySet())
        val phrases = listOf(
            "Sensitive notification content hidden",
            "SENSITIVE NOTIFICATION CONTENT HIDDEN.",
            "Contents hidden",
            "Content hidden",
            "Notification content hidden"
        )

        for (phrase in phrases) {
            val result = detector.detect(
                RedactionCandidate(
                    appName = "Authenticator",
                    extractedText = phrase
                )
            )
            assertTrue("Expected '$phrase' to be detected as redacted", result.isRedacted)
            assertEquals(RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER, result.reason)
            assertEquals(
                "Private alert from Authenticator. Open the app to view it.",
                result.fallbackMessage
            )
        }
    }

    @Test
    fun detectsRedactedPlaceholderInTitleWhenBodyIsEmpty() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "BankApp",
                extractedTitle = "Contents hidden",
                extractedText = ""
            )
        )

        assertTrue(result.isRedacted)
        assertEquals(RedactionReason.SYSTEM_OR_OEM_PLACEHOLDER, result.reason)
    }

    @Test
    fun detectsVisibilitySecretFlagUnconditionally() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "Health Portal",
                visibility = RedactionDetector.VISIBILITY_SECRET,
                extractedTitle = "Lab Update",
                extractedText = "Some text"
            )
        )

        assertTrue(result.isRedacted)
        assertEquals(RedactionReason.VISIBILITY_SECRET, result.reason)
        assertEquals(
            "Private alert from Health Portal. Open the app to view it.",
            result.fallbackMessage
        )
    }

    @Test
    fun detectsVisibilityPrivateMatchingPublicVersionPlaceholderText() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "Signal",
                visibility = RedactionDetector.VISIBILITY_PRIVATE,
                extractedTitle = "Signal",
                extractedText = "New message",
                publicVersionTitle = "Signal",
                publicVersionText = "New message."
            )
        )

        assertTrue(result.isRedacted)
        assertEquals(RedactionReason.PUBLIC_VERSION_PLACEHOLDER_MATCH, result.reason)
        assertEquals(
            "Private alert from Signal. Open the app to view it.",
            result.fallbackMessage
        )
    }

    @Test
    fun detectsVisibilityPrivateStrippedBodyWhenPublicVersionExists() {
        val detector = RedactionDetector()
        val result = detector.detect(
            RedactionCandidate(
                appName = "Messages",
                visibility = RedactionDetector.VISIBILITY_PRIVATE,
                extractedTitle = "Bank",
                extractedText = "",
                extractedMessages = emptyList(),
                publicVersionTitle = "Messages",
                publicVersionText = "1 new message"
            )
        )

        assertTrue(result.isRedacted)
        assertEquals(RedactionReason.PUBLIC_VERSION_PLACEHOLDER_MATCH, result.reason)
    }

    @Test
    fun identifiesRedactedSenderPhraseViaHelper() {
        val detector = RedactionDetector(
            systemRedactedPhrases = setOf("Contenu sensible masqué")
        )
        assertTrue(detector.isRedactedPhrase("Contenu sensible masqué"))
        assertTrue(detector.isRedactedPhrase("Sensitive notification content hidden"))
        assertFalse(detector.isRedactedPhrase("Grandma"))
        assertFalse(detector.isRedactedPhrase(null))
    }

    @Test
    fun usesCustomLocalizedFallbackFormatterAndDefaultAppLabelWhenAppNameBlank() {
        val detector = RedactionDetector(
            defaultAppLabel = "Aplicación",
            fallbackFormatter = { app -> "Alerta privada de $app. Ábrala para verla." }
        )

        val result = detector.detect(
            RedactionCandidate(
                appName = "   ",
                visibility = RedactionDetector.VISIBILITY_SECRET
            )
        )

        assertTrue(result.isRedacted)
        assertEquals("Alerta privada de Aplicación. Ábrala para verla.", result.fallbackMessage)
    }
}
