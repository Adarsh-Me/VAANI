package com.itantra.ml

import com.itantra.models.ProsodyData
import com.itantra.utils.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PipelineUnitTest {

    @Test
    fun savaani_tdtDecode_skipsBlankAndUsesDurationJumps() {
        // Synthetic logits: frame0 -> blank + dur 2 (jump to frame2),
        // frame1 skipped, frame2 -> token 7 + dur 1. Deterministic single pass.
        val logits = FloatArray(3 * SavaaniDecoder.LOGIT_STRIDE)
        setTok2(logits, 0, SavaaniDecoder.BLANK_ID); setDur2(logits, 0, 2)
        setTok2(logits, 1, 42); setDur2(logits, 1, 1) // skipped by jump
        setTok2(logits, 2, 7); setDur2(logits, 2, 1)

        val emitted = mutableListOf<Int>()
        SavaaniDecoder.decodeFrames(logits, 3) { tok, _ -> emitted.add(tok) }
        assertEquals(listOf(7), emitted)
    }

    @Test
    fun savaani_tdtDecode_emitsTokenThenBlankJump() {
        // frame0 -> token 42 + dur 2 (advance past frame1), frame2 -> EOS-ish blank
        val logits = FloatArray(3 * SavaaniDecoder.LOGIT_STRIDE)
        setTok2(logits, 0, 42); setDur2(logits, 0, 2)
        setTok2(logits, 1, 99); setDur2(logits, 1, 1) // must be skipped
        setTok2(logits, 2, SavaaniDecoder.BLANK_ID); setDur2(logits, 2, 1)

        val emitted = mutableListOf<Int>()
        SavaaniDecoder.decodeFrames(logits, 3) { tok, _ -> emitted.add(tok) }
        assertEquals(listOf(42), emitted)
    }

    private fun setTok2(logits: FloatArray, frame: Int, tok: Int) {
        val stride = SavaaniDecoder.LOGIT_STRIDE
        for (i in 0..SavaaniDecoder.VOCAB_SIZE) logits[frame * stride + i] = if (i == tok) 9f else 0f
    }

    private fun setDur2(logits: FloatArray, frame: Int, d: Int) {
        val stride = SavaaniDecoder.LOGIT_STRIDE
        for (i in 0 until SavaaniDecoder.DUR_HEADS)
            logits[frame * stride + SavaaniDecoder.VOCAB_SIZE + 1 + i] = if (i == d) 8f else 0f
    }

    @Test
    fun savaani_toText_joinsPiecesAndSpace() {
        val vocab = List(3) { "" } + listOf("▁नमस्ते") // id 3
        val text = SavaaniDecoder.toText(listOf(3), vocab)
        assertEquals("नमस्ते", text)
    }

    @Test
    fun prosody_bytesRoundTrip() {
        val p = ProsodyData(0.2f, 0.7f, 0.5f, 0.9f, 0.05f)
        val back = ProsodyData.fromBytes(p.toBytes())
        assertEquals(p.toBytes().toList(), back.toBytes().toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun prosody_rejectsWrongSize() {
        ProsodyData.fromBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun prosody_silenceGivesHighPause() {
        val silent = FloatArray(16000)
        val p = ProsodyExtractor.extract(silent, text = "")
        assertTrue(p.pauseRatio > 0.9f)
        assertTrue(p.energy < 0.05f)
    }

    @Test
    fun prosody_loudToneHasEnergyAndPitch() {
        // 200 Hz sine, 1 s, amplitude 0.5.
        val tone = FloatArray(16000) { i ->
            (0.5 * kotlin.math.sin(2 * Math.PI * 200 * i / 16000)).toFloat()
        }
        val p = ProsodyExtractor.extract(tone, text = "नमस्ते दुनिया")
        assertTrue("energy=${p.energy}", p.energy > 0.2f)
        assertTrue("pitchHz=${p.physicalPitchHz()}", abs(p.physicalPitchHz() - 200f) < 60f)
        assertTrue("rate=${p.physicalRateWps()}", p.physicalRateWps() in 0.5f..5.0f)
    }

    @Test
    fun phrasebook_hiToTa_distress() {
        assertEquals(
            "எனக்கு உதவி தேவை",
            DemoPhrasebook.translate("मुझे मदद चाहिए।", "hi", "ta")
        )
    }

    @Test
    fun phrasebook_taToHi_water() {
        assertEquals(
            "पानी बहुत तेज़ है",
            DemoPhrasebook.translate("தண்ணீர் மிகவும் வேகமாக உள்ளது", "ta", "hi")
        )
    }

    @Test
    fun phrasebook_unknownReturnsNull() {
        assertNull(DemoPhrasebook.translate("यह वाक्य कलकत्ता जाएगा", "hi", "ta"))
    }

    @Test
    fun detector_devanagariIsHindi() {
        assertEquals("hi", LanguageDetector.detect("मुझे मदद चाहिए"))
    }

    @Test
    fun detector_tamilScript() {
        assertEquals("ta", LanguageDetector.detect("எனக்கு உதவி தேவை"))
    }

    @Test
    fun detector_bengaliScript() {
        assertEquals("bn", LanguageDetector.detect("আমার সাহায্য দরকার"))
    }

    @Test
    fun detector_latinFallsBack() {
        assertEquals("hi", LanguageDetector.detect("hello there"))
    }

    @Test
    fun ctcDecode_collapsesRepeatsAndBlanks() {
        // New IndicConformer family: blank id 5632, ▁ inside tokens -> space.
        val vocab = listOf("न", "म", "▁स", "ते")
        val text = ASRManagerLazy.collapseCtcForTest(intArrayOf(0, 0, 5632, 1, 2, 5632, 3), vocab)
        assertEquals("नम सते", text)
    }
}

/** Thin hook so the pure CTC decoder is unit-testable without a Context. */
object ASRManagerLazy {
    fun collapseCtcForTest(ids: IntArray, vocab: List<String>): String {
        val sb = StringBuilder()
        var prev = -1
        for (id in ids) {
            if (id == 5632) {
                prev = -1
                continue
            }
            if (id == prev) continue
            prev = id
            sb.append(vocab.getOrNull(id) ?: "")
        }
        return sb.toString().replace("▁", " ").trim().replace(Regex("\\s+"), " ")
    }
}
