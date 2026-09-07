package com.itantra.data

import java.util.Base64
import com.itantra.models.Languages
import com.itantra.models.PacketLimits
import java.io.File
import java.security.MessageDigest

/** Packet field validation (Schema §11). Returns null when valid. */
object PacketValidator {
    private val UUID_V4 =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun validate(
        id: String, src: String, tgt: String, text: String,
        emo: Int, prosodyBase64: String, ts: Long, version: Int,
        wireBytes: Int
    ): String? {
        if (!UUID_V4.matches(id)) return "bad id"
        if (!Languages.isSupported(src)) return "unknown src lang"
        if (!Languages.isSupported(tgt)) return "unknown tgt lang"
        if (text.isBlank()) return "blank text"
        if (text.length > PacketLimits.MAX_TEXT_CHARS) return "text too long"
        if (emo !in 0..4) return "bad emotion"
        if (version !in PacketLimits.SUPPORTED_VERSIONS) return "bad version"
        if (wireBytes > PacketLimits.MAX_SIZE_BYTES) return "packet too large"
        val now = System.currentTimeMillis()
        if (ts > now + PacketLimits.FUTURE_SKEW_MS) return "timestamp in future"
        if (now - ts > PacketLimits.MAX_AGE_MS) return "packet expired"
        try {
            val pros = Base64.getDecoder().decode(prosodyBase64)
            if (pros.size != 5) return "prosody must be 5 bytes"
        } catch (e: IllegalArgumentException) {
            return "bad prosody base64"
        }
        return null
    }
}

/** On-device model file checks (Schema §4). */
object ModelValidator {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedSha256: String?): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSha256.isNullOrBlank()) return true // no manifest yet: presence check
        return try {
            sha256(file).equals(expectedSha256, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
