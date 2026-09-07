package com.itantra

import com.itantra.ml.DemoPhrasebook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phone keyboards type nukta characters as base+sign (ज U+091C + ़ U+093C)
 * while the phrasebook stores the precomposed ज़ (U+095B). That pairing is a
 * compatibility mapping, so only NFKD normalization aligns both spellings.
 */
class PhrasebookUnicodeTest {

    private val tamilHelp = "எனக்கு உதவி தேவை"
    private val tamilWater = "தண்ணீர் மிகவும் வேகமாக உள்ளது"

    @Test
    fun `exact phrasebook hit translates hi to ta`() {
        assertEquals(tamilHelp, DemoPhrasebook.translate("मुझे मदद चाहिए", "hi", "ta"))
    }

    @Test
    fun `nukta decomposed spelling still hits the phrase`() {
        // Precomposed: पानी बहुत तेज़ है  (ज़ = U+095B)
        val precomposed = "पानी बहुत तेज़ है"
        // Decomposed: ज (U+091C) + ़ (U+093C) — typical Hindi-keyboard output.
        val decomposed = "पानी बहुत ते\u091C\u093C है"
        assertEquals(tamilWater, DemoPhrasebook.translate(precomposed, "hi", "ta"))
        assertEquals(tamilWater, DemoPhrasebook.translate(decomposed, "hi", "ta"))
    }

    @Test
    fun `punctuation and spacing variants still match`() {
        assertEquals(tamilHelp, DemoPhrasebook.translate("मुझे मदद चाहिए।", "hi", "ta"))
        assertEquals(tamilHelp, DemoPhrasebook.translate("  मुझे   मदद चाहिए? ", "hi", "ta"))
    }

    @Test
    fun `intro sentence glosses known words`() {
        // "नमस्ते, मेरा नाम आदर्श है। तुम्हारा नाम क्या है?"
        // Known words become Tamil, unknown proper noun (आदर्श) stays.
        val out = DemoPhrasebook.translate(
            "नमस्ते, मेरा नाम आदर्श है। तुम्हारा नाम क्या है?", "hi", "ta"
        )
        org.junit.Assert.assertNotNull(out)
        val t = out!!
        org.junit.Assert.assertTrue("vanakkam missing: $t", t.contains("வணக்கம்"))
        org.junit.Assert.assertTrue("name missing: $t", t.contains("பெயர்"))
        org.junit.Assert.assertTrue("proper noun kept: $t", t.contains("आदर्श"))
        org.junit.Assert.assertTrue("question missing: $t", t.contains("என்ன"))
    }
}
