package com.itantra.utils

import com.itantra.models.Languages

/** Script-range language guess (AppFlow §6 fallback; full ID deferred). */
object LanguageDetector {
    private data class Range(val lang: String, val from: Int, val to: Int)

    private val RANGES = listOf(
        Range("hi", 0x0900, 0x097F), // Devanagari: hi/mr/ne/sa/kok/mai...
        Range("bn", 0x0980, 0x09FF), // Bengali/Assamese
        Range("pa", 0x0A00, 0x0A7F), // Gurmukhi
        Range("gu", 0x0A80, 0x0AFF), // Gujarati
        Range("or", 0x0B00, 0x0B7F), // Odia
        Range("ta", 0x0B80, 0x0BFF), // Tamil
        Range("te", 0x0C00, 0x0C7F), // Telugu
        Range("kn", 0x0C80, 0x0CFF), // Kannada
        Range("ml", 0x0D00, 0x0D7F) // Malayalam
    )

    fun detect(text: String, default: String = Languages.DEFAULT_SOURCE): String {
        val counts = HashMap<String, Int>()
        for (c in text) {
            val code = c.code
            for (r in RANGES) {
                if (code in r.from..r.to) {
                    counts[r.lang] = (counts[r.lang] ?: 0) + 1
                    break
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: default
    }
}
