package com.notifyring.app.data

import java.security.MessageDigest
import java.security.SecureRandom

object PinHasher {
    private const val SALT_LENGTH_BYTES = 16
    private const val SHA256_LENGTH_BYTES = 32
    private const val HASH_ALGORITHM = "SHA-256"
    private const val SEPARATOR = ':'
    private const val HEX_CHARS = "0123456789abcdef"

    private val secureRandom = SecureRandom()

    fun hash(pin: String): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val digest = sha256(salt, pin.toByteArray(Charsets.UTF_8))
        return "${bytesToHex(salt)}$SEPARATOR${bytesToHex(digest)}"
    }

    fun verify(pin: String, storedHash: String?): Boolean {
        if (pin.isEmpty() || storedHash.isNullOrBlank()) {
            return false
        }
        val parts = storedHash.split(SEPARATOR)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return false
        }
        val salt = hexToBytes(parts[0]) ?: return false
        val expectedDigest = hexToBytes(parts[1]) ?: return false
        if (salt.isEmpty() || expectedDigest.size != SHA256_LENGTH_BYTES) {
            return false
        }
        val actualDigest = sha256(salt, pin.toByteArray(Charsets.UTF_8))
        return constantTimeEquals(actualDigest, expectedDigest)
    }

    private fun sha256(salt: ByteArray, input: ByteArray): ByteArray {
        val messageDigest = MessageDigest.getInstance(HASH_ALGORITHM)
        messageDigest.update(salt)
        messageDigest.update(input)
        return messageDigest.digest()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) {
            return false
        }
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            result[i * 2] = HEX_CHARS[value ushr 4]
            result[i * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(result)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) {
            return null
        }
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high == -1 || low == -1) {
                return null
            }
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
