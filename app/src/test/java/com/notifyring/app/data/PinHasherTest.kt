package com.notifyring.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun hashUsesSaltedSha256HexFormat() {
        val hashed = PinHasher.hash("1234")
        val parts = hashed.split(":")

        assertEquals(2, parts.size)
        assertEquals(32, parts[0].length)
        assertEquals(64, parts[1].length)
        assertTrue(parts[0].all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(parts[1].all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun hashGeneratesUniqueSaltsForSamePin() {
        val first = PinHasher.hash("2468")
        val second = PinHasher.hash("2468")

        assertNotEquals(first, second)
        assertTrue(PinHasher.verify("2468", first))
        assertTrue(PinHasher.verify("2468", second))
    }

    @Test
    fun verifyReturnsFalseForMismatchAndMalformedInputs() {
        val hashed = PinHasher.hash("9876")

        assertFalse(PinHasher.verify("9875", hashed))
        assertFalse(PinHasher.verify("", hashed))
        assertFalse(PinHasher.verify("9876", null))
        assertFalse(PinHasher.verify("9876", ""))
        assertFalse(PinHasher.verify("9876", "not-a-valid-hash"))
        assertFalse(PinHasher.verify("9876", "zzzz:1234"))
        assertFalse(PinHasher.verify("9876", "001122:abcd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hashRejectsEmptyPin() {
        PinHasher.hash("")
    }
}
