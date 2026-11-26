package com.notifyring.app.speech

import com.notifyring.app.telecom.AlertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechScriptFormatterTest {

    private val formatter = SpeechScriptFormatter()

    @Test
    fun `formats full payload with clear announcement and natural sentence pauses`() {
        val payload = AlertPayload(
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            sender = "Dr. Patel",
            message = "Your clinic lab results are ready"
        )

        val script = formatter.format(payload)

        assertEquals(
            "WhatsApp alert from Dr. Patel. Your clinic lab results are ready.",
            script
        )
    }

    @Test
    fun `handles blank or missing sender cleanly without empty from clause`() {
        val payload = AlertPayload(
            appName = "Calendar",
            packageName = "com.google.android.calendar",
            sender = "   \n\t  ",
            message = " Cardiology follow-up at 2:30 PM. "
        )

        val script = formatter.format(payload)

        assertEquals(
            "Alert from Calendar. Cardiology follow-up at 2:30 PM.",
            script
        )
    }

    @Test
    fun `deduplicates when sender matches appName case-insensitively`() {
        val payload = AlertPayload(
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            sender = "  whatsapp  ",
            message = "You have 2 new messages!"
        )

        val script = formatter.format(payload)

        assertEquals("Alert from WhatsApp. You have 2 new messages!", script)
        assertFalse(script.contains("WhatsApp alert from", ignoreCase = true))
    }

    @Test
    fun `handles blank message with spoken fallback and handles blank appName`() {
        val blankMsgPayload = AlertPayload(
            appName = "Signal",
            packageName = "org.thoughtcrime.securesms",
            sender = "Caregiver Maya",
            message = "  \r\n\t  "
        )
        assertEquals(
            "Signal alert from Caregiver Maya. No message content available.",
            formatter.format(blankMsgPayload)
        )

        val blankAppPayload = AlertPayload(
            appName = "   ",
            packageName = "",
            sender = "Pharmacy",
            message = "Prescription ready?"
        )
        assertEquals(
            "Alert from Pharmacy. Prescription ready?",
            formatter.format(blankAppPayload)
        )

        val allBlankPayload = AlertPayload(
            appName = "",
            packageName = "",
            sender = "",
            message = ""
        )
        assertEquals(
            "New alert. No message content available.",
            formatter.format(allBlankPayload)
        )
    }

    @Test
    fun `normalizes newlines tabs and repeated spaces across all fields`() {
        val payload = AlertPayload(
            appName = "  Google\nMessages  ",
            packageName = "com.google.android.apps.messaging",
            sender = "  Nurse\t  Sarah. ",
            message = "  Take   morning\r\nmedication   at 8 AM.\n\nDrink   a full glass\tof water  "
        )

        val script = formatter.format(payload)

        assertEquals(
            "Google Messages alert from Nurse Sarah. Take morning medication at 8 AM. Drink a full glass of water.",
            script
        )
    }

    @Test
    fun `truncates overly long messages cleanly at word boundaries without splitting words`() {
        val longBody =
            "Please remember to bring your insurance card, blood pressure log, and current medication bottles to tomorrow morning's appointment"
        val payload = AlertPayload(
            appName = "Messages",
            packageName = "com.google.android.apps.messaging",
            sender = "Clinic Desk",
            message = longBody
        )

        val script = formatter.format(payload, maxMessageChars = 60)

        val prefix = "Messages alert from Clinic Desk. "
        assertTrue(script.startsWith(prefix))
        val spokenBody = script.removePrefix(prefix)

        // "Please remember to bring your insurance card, blood pressure" is 60 chars;
        // must not cut mid-word or leave a trailing comma, and must end with a period.
        assertEquals(
            "Please remember to bring your insurance card, blood pressure.",
            spokenBody
        )
        assertTrue(spokenBody.endsWith("."))

        // Single unbroken token longer than maxMessageChars still bounds cleanly without crashing.
        val unbroken = "A".repeat(100)
        val unbrokenScript = formatter.format(
            AlertPayload("App", "com.app", "Bot", unbroken),
            maxMessageChars = 24
        )
        assertEquals("App alert from Bot. ${"A".repeat(24)}.", unbrokenScript)
    }
}
