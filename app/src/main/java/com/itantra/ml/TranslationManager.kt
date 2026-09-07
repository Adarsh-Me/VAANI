package com.itantra.ml

import android.content.Context
import com.itantra.models.StorageLayout
import com.itantra.models.TranslationResult
import java.io.File

/**
 * IndicTrans2-320M (INT8, direct Indic->Indic) with a bundled hi/ta/bn
 * demo phrasebook fallback. [fallbackUsed] = true means neural MT was
 * unavailable; the caller decides whether to flag translationFailed.
 */
class TranslationManager(private val context: Context, private val threads: Int = 2) {

    private val tokenizer = IndicTokenizer(context)
    private val srcBpe by lazy { It2BpeTokenizer(context, "src") }
    private val tgtBpe by lazy { It2BpeTokenizer(context, "tgt") }
    private var encoder: ai.onnxruntime.OrtSession? = null
    private var decoder: ai.onnxruntime.OrtSession? = null
    private var decoderPast: ai.onnxruntime.OrtSession? = null
    val isModelLoaded: Boolean get() = encoder != null && decoder != null
    /** True only when sessions AND both BPE tokenizers are ready. */
    val isNeuralReady: Boolean get() = isModelLoaded && srcBpe.isLoaded && tgtBpe.isLoaded
    /** Heavy ONNX load. Call from Dispatchers.IO. Returns true when neural MT is live. */
    fun initialize(): Boolean {
        if (isModelLoaded) return true
        encoder = OnnxLoader.load(context, StorageLayout.MT_ENCODER, threads)
        if (encoder != null) {
            android.util.Log.i(
                "TMT", "ENCODER OrtSession created: " +
                    File(context.filesDir, StorageLayout.MT_ENCODER).length() + " bytes"
            )
            decoder = OnnxLoader.load(context, StorageLayout.MT_DECODER_LEGACY, threads)
            if (decoder != null) {
                android.util.Log.i(
                    "TMT", "DECODER OrtSession created: " +
                        File(context.filesDir, StorageLayout.MT_DECODER_LEGACY).length() + " bytes"
                )
                // KV-cache decoder: the only path that generates full sentences
                // (no-past re-feed truncates — verified against official
                // translate.py). Optional file: absent -> re-feed fallback.
                decoderPast = OnnxLoader.load(context, StorageLayout.MT_DECODER, threads)
                android.util.Log.i(
                    "TMT", "DECODER_PAST " +
                        (if (decoderPast != null) "loaded" else "ABSENT — re-feed fallback")
                )
            } else {
                android.util.Log.w("TMT", "DECODER OrtSession NULL — neural MT stays off")
                runCatching { encoder?.close() }
                encoder = null
            }
        } else {
            android.util.Log.w("TMT", "ENCODER OrtSession NULL — neural MT stays off")
        }
        android.util.Log.i("TMT", "neural MT loaded=$isModelLoaded")
        return isModelLoaded
    }

    fun translate(text: String, src: String, tgt: String): TranslationResult {
        val t0 = System.currentTimeMillis()
        if (src == tgt || text.isBlank()) {
            return TranslationResult(text, src, tgt, fallbackUsed = false, 0)
        }
        if (isModelLoaded) {
            try {
                return neural(text, src, tgt, t0)
            } catch (e: Exception) {
                android.util.Log.w("TMT", "neural failed, flagged fallback: ${e.message}")
                // Fall through to phrasebook — flagged, never silent.
            }
        }
        val demo = DemoPhrasebook.translate(text, src, tgt)
        val ms = System.currentTimeMillis() - t0
        return if (demo != null) {
            TranslationResult(demo, src, tgt, fallbackUsed = true, ms)
        } else {
            // Honest failure: return source text, flagged by caller.
            TranslationResult(text, src, tgt, fallbackUsed = true, ms)
        }
    }

    /**
     * Real IndicTrans2 inference. Encoder forward pass on
     * [srcTag tgtTag text] BPE ids, then greedy decoder loop (no KV-cache:
     * the full prefix is re-fed every step, only `logits` is read).
     * Decoder seeds with decoder_start id 2, stops at EOS id 2.
     */
    private fun neural(text: String, src: String, tgt: String, t0: Long): TranslationResult {
        val enc = encoder ?: throw IllegalStateException("encoder session null")
        val dec = decoder ?: throw IllegalStateException("decoder session null")
        if (!isNeuralReady) throw IllegalStateException("BPE tokenizers not loaded")
        val srcTag = it2Tag(src)
        val tgtTag = it2Tag(tgt)

        // IndicTrans2 preprocesses source into a unified Devanagari space
        // (IndicProcessor). Without it, Tamil/Bengali/Punjabi source chars are
        // out-of-vocab and the tokenizer emits <unk> soup -> garbage output.
        val prepared = preprocessSource(text, src)

        val inIds = srcBpe.encode(prepared, srcTag, tgtTag)
            .take(It2Format.MAX_SRC_TOKENS)
            .toIntArray()
        require(inIds.size >= 3) { "tokenizer produced no ids" }

        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val encIds = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(inIds.map { it.toLong() }.toLongArray()),
            longArrayOf(1, inIds.size.toLong())
        )
        val encMask = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(LongArray(inIds.size) { 1L }),
            longArrayOf(1, inIds.size.toLong())
        )
        val hidden: Array<Array<FloatArray>>
        encIds.use { e ->
            encMask.use { m ->
                enc.run(mapOf("input_ids" to e, "attention_mask" to m)).use { out ->
                    val t = out.get("last_hidden_state").get() as ai.onnxruntime.OnnxTensor
                    val fb = t.floatBuffer
                    hidden = Array(1) { Array(inIds.size) { FloatArray(512) } }
                    for (s in 0 until inIds.size)
                        for (h in 0 until 512) hidden[0][s][h] = fb.get()
                }
            }
        }

        // Greedy decode with KV cache — the official inference path.
        // Step 0 runs the no-past decoder on the seed token and yields logits
        // + 72 KV tensors (18 layers x {dec,enc} x {k,v}); every later step
        // feeds ONE token + the growing past into the with-past decoder.
        // (Verified: re-feeding the full prefix through the no-past decoder
        // truncates output after 2-3 tokens; the KV path produces full
        // sentences AND is ~10x faster.)
        val gen = ArrayList<Int>()
        gen.add(It2Format.DECODER_START_ID)
        val vocab = It2Format.TGT_DICT_SIZE

        fun pickToken(logitsBuf: java.nio.FloatBuffer, seqLen: Int): Int {
            // Anti-loop: ban the last token if it repeats within a short
            // window (catches short token cycles like நம வண நம வண) while
            // letting legit reduplication (bahut-bahut) survive.
            val banned = if (gen.size >= 6 &&
                gen.dropLast(1).takeLast(5).contains(gen.last())
            ) gen.last() else -1
            var best = 0
            var bestV = Float.NEGATIVE_INFINITY
            val base = (seqLen - 1) * vocab
            for (v in 0 until vocab) {
                if (v == banned) continue
                val x = logitsBuf.get(base + v)
                if (x > bestV) {
                    bestV = x; best = v
                }
            }
            return best
        }

        val decMaskBuf = java.nio.LongBuffer.wrap(LongArray(inIds.size) { 1L })
        val decMask = ai.onnxruntime.OnnxTensor.createTensor(
            env, decMaskBuf, longArrayOf(1, inIds.size.toLong())
        )
        val flatHidden = FloatArray(inIds.size * 512) { i -> hidden[0][i / 512][i % 512] }
        val hiddenBuf = java.nio.FloatBuffer.wrap(flatHidden)
        decMask.use {
            ai.onnxruntime.OnnxTensor.createTensor(
                env, hiddenBuf, longArrayOf(1, inIds.size.toLong(), 512)
            ).use { hiddenT ->
                val decPast = decoderPast
                    ?: throw IllegalStateException("with-past decoder missing — neural MT needs models/decoder_with_past_model.onnx")
                // Lifecycle: each Result OWNS its present.* tensors. Keep the
                // Result open while its past feeds the NEXT step; close only
                // after that step's run returns. Closing early = use-after-free
                // (native SIGSEGV) — this exact bug crashed the emulator.
                var prev: ai.onnxruntime.OrtSession.Result? = null
                try {
                    // Step 0: seed through the no-past decoder.
                    ai.onnxruntime.OnnxTensor.createTensor(
                        env, java.nio.LongBuffer.wrap(longArrayOf(gen[0].toLong())), longArrayOf(1, 1)
                    ).use { seedIds ->
                        val out0 = dec.run(
                            mapOf(
                                "input_ids" to seedIds,
                                "encoder_attention_mask" to decMask,
                                "encoder_hidden_states" to hiddenT
                            )
                        )
                        prev = out0
                        val logits0 = out0.get("logits").get() as ai.onnxruntime.OnnxTensor
                        gen.add(pickToken(logits0.floatBuffer, 1))
                    }
                    var past = collectPast(prev!!)

                    // Steps >= 1: one token + past through the with-past decoder.
                    while (gen.last() != It2Format.EOS_ID && gen.size < It2Format.MAX_NEW_TOKENS) {
                        val pastNames = pastNamesInOrder(past.size)
                        val feed = HashMap<String, ai.onnxruntime.OnnxTensor>(past.size + 2)
                        val stepIds = ai.onnxruntime.OnnxTensor.createTensor(
                            env, java.nio.LongBuffer.wrap(longArrayOf(gen.last().toLong())), longArrayOf(1, 1)
                        )
                        feed["input_ids"] = stepIds
                        feed["encoder_attention_mask"] = decMask
                        for (i in pastNames.indices) feed[pastNames[i]] = past[i]
                        val outN = decPast.run(feed)
                        stepIds.close()
                        // Past tensors came from prev — close prev only now.
                        runCatching { prev?.close() }
                        prev = outN
                        val logitsN = outN.get("logits").get() as ai.onnxruntime.OnnxTensor
                        gen.add(pickToken(logitsN.floatBuffer, 1))
                        past = collectPast(outN)
                    }
                } finally {
                    runCatching { prev?.close() }
                }
            }
        }
        val outIds = gen.drop(1).takeWhile { it != 2 }
        val raw = tgtBpe.decode(outIds)
        // IndicTrans2 decodes in a Devanagari-unified space: stray Devanagari
        // tokens leak into ta/pa output. Map them back to the target script.
        val decoded = transliterateToTarget(raw, tgt)
        android.util.Log.i(
            "TMT", "neural $src->$tgt ${inIds.size} in / ${outIds.size} out, " +
                "${System.currentTimeMillis() - t0} ms :: $decoded"
        )
        if (decoded.isBlank()) throw IllegalStateException("decoder produced empty text")
        return TranslationResult(decoded, src, tgt, fallbackUsed = false, System.currentTimeMillis() - t0)
    }

    /** Collect the present.* KV tensors (owned by [result]) in output order:
     *  logits first, then per layer dec.k, dec.v, enc.k, enc.v. */
    private fun collectPast(result: ai.onnxruntime.OrtSession.Result): List<ai.onnxruntime.OnnxTensor> {
        val past = ArrayList<ai.onnxruntime.OnnxTensor>(72)
        for ((name, value) in result) {
            if (name.startsWith("present")) {
                (value as? ai.onnxruntime.OnnxTensor)?.let { past.add(it) }
            }
        }
        return past
    }

    /** Present-tensor input names in the order OrtSession.Result.names()
     *  emits "present.*" outputs: per layer dec.k, dec.v, enc.k, enc.v. */
    private fun pastNamesInOrder(count: Int): List<String> {
        val layers = count / 4
        val names = ArrayList<String>(count)
        for (i in 0 until layers) {
            names.add("past_key_values.$i.decoder.key")
            names.add("past_key_values.$i.decoder.value")
            names.add("past_key_values.$i.encoder.key")
            names.add("past_key_values.$i.encoder.value")
        }
        return names
    }

    /**
     * IndicTrans2 preprocesses source into a unified Devanagari space
     * (IndicProcessor). Without it, Tamil/Bengali/Punjabi source chars are
     * out-of-vocab and the tokenizer emits <unk> soup -> garbage output.
     * Maps each target-script char to its Devanagari counterpart (candra
     * variants for Tamil short e/o — verified present in the tokenizer
     * vocab). Devanagari-script sources pass through unchanged; Arabic-
     * script sources (ur/ks/sd) pass through too (phrasebook covers demos).
     */
    private fun preprocessSource(text: String, src: String): String {
        val map: Map<Char, String> = when (src) {
            "ta" -> TamilToDeva
            "pa" -> GuruToDeva
            "bn" -> BengaliToDeva
            else -> return text
        }
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(map[c] ?: c.toString())
        return sb.toString()
    }

    /**
     * Devanagari-unified decoder output -> target script.
     *
     * Tamil specifics that matter for readability:
     *  - virama ् -> pulli ஂ் (was dropped entirely, corrupting clusters:
     *    प्रभात must be ப்ரபாத், not பரபாத).
     *  - word-final bare consonant gets pulli (Hindi deletes final inherent
     *    'a': नाम -> நாம், kamal -> கமல்).
     *  - anusvara -> homorganic nasal by following consonant class
     *    (हिंदी -> ஹிந்தீ), dropped word-finally.
     * Gurmukhi keeps inherent-a spelling; virama maps to ੍ directly.
     */
    private fun transliterateToTarget(text: String, tgt: String): String {
        when (tgt) {
            "ta" -> {}
            "pa" -> return mapChars(text, DevaToGuru)
            else -> return text
        }
        val sb = StringBuilder(text.length + 16)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val isDeva = c.code in 0x900..0x97F
            if (!isDeva) { sb.append(c); i++; continue }
            val mapped = DevaToTamil[c]
            if (mapped == null) {
                // Nukta and other bare signs vanish; letters pass through.
                if (c != '़') sb.append(c)
                i++; continue
            }
            when {
                c == '्' -> sb.append('்')
                c == 'ं' -> {
                    val next = text.getOrNull(i + 1)
                    if (next == null || next.code !in 0x900..0x97F || next == '।' || next == '॥') {
                        // Word-final anusvara: drop (हैं -> ஹை).
                    } else {
                        sb.append(homorganicNasalTamil(next))
                    }
                }
                isDevaConsonant(c) -> {
                    sb.append(mapped)
                    val next = text.getOrNull(i + 1)
                    val keepsVowel = next != null && (isDevaMatra(next) || next == '्' ||
                        next == 'ं' || next == 'ः' || next == '़')
                    val wordEnds = next == null || next.code !in 0x900..0x97F ||
                        next == '।' || next == '॥'
                    if (!keepsVowel && wordEnds) sb.append('்')
                }
                else -> sb.append(mapped)
            }
            i++
        }
        return sb.toString()
    }

    private fun mapChars(text: String, map: Map<Char, String>): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.code in 0x900..0x97F) {
                val m = map[c]
                if (m != null) sb.append(m) else if (c != '़') sb.append(c)
            } else sb.append(c)
        }
        return sb.toString()
    }

    private fun isDevaConsonant(c: Char): Boolean =
        c.code in 0x915..0x939 || c.code in 0x958..0x95F || c.code in 0x978..0x97F

    private fun isDevaMatra(c: Char): Boolean =
        c.code in 0x93E..0x94D || c.code == 0x962 || c.code == 0x963

    /** ஂ placement by the class of the following Devanagari consonant. */
    private fun homorganicNasalTamil(next: Char): String = when (next) {
        'क', 'ख', 'ग', 'घ', 'ङ' -> "ங்"
        'च', 'छ', 'ज', 'झ', 'ञ' -> "ஞ்"
        'ट', 'ठ', 'ड', 'ढ', 'ण' -> "ண்"
        'त', 'थ', 'द', 'ध', 'न' -> "ந்"
        else -> "ம்"
    }

    /** Tamil -> unified Devanagari (IndicProcessor-equivalent, char level). */
    private val TamilToDeva: Map<Char, String> = mapOf(
        'அ' to "अ", 'ஆ' to "आ", 'இ' to "इ", 'ஈ' to "ई", 'உ' to "उ", 'ஊ' to "ऊ",
        'எ' to "ऎ", 'ஏ' to "ए", 'ஐ' to "ऐ", 'ஒ' to "ऒ", 'ஓ' to "ओ", 'ஔ' to "औ",
        'ஃ' to "ः",
        'க' to "क", 'ங' to "ङ", 'ச' to "च", 'ஞ' to "ञ", 'ட' to "ट", 'ண' to "ण",
        'த' to "त", 'ந' to "न", 'ன' to "ऩ", 'ப' to "प", 'ம' to "म", 'ய' to "य",
        'ர' to "र", 'ற' to "ऱ", 'ல' to "ल", 'ள' to "ळ", 'ழ' to "ऴ", 'வ' to "व",
        'ஶ' to "श", 'ஷ' to "ष", 'ஸ' to "स", 'ஹ' to "ह", 'ஜ' to "ज",
        'ா' to "ा", 'ி' to "ि", 'ீ' to "ी", 'ு' to "ु", 'ூ' to "ू",
        'ெ' to "ॆ", 'ே' to "े", 'ை' to "ै", 'ொ' to "ॊ", 'ோ' to "ो", 'ௌ' to "ौ",
        '்' to "्",
        '௦' to "0", '௧' to "1", '௨' to "2", '௩' to "3", '௪' to "4",
        '௫' to "5", '௬' to "6", '௭' to "7", '௮' to "8", '௯' to "9"
    )

    /** Gurmukhi -> unified Devanagari. */
    private val GuruToDeva: Map<Char, String> = mapOf(
        'ਅ' to "अ", 'ਆ' to "आ", 'ਇ' to "इ", 'ਈ' to "ई", 'ਉ' to "उ", 'ਊ' to "ऊ",
        'ਏ' to "ए", 'ਐ' to "ऐ", 'ਓ' to "ओ", 'ਔ' to "औ",
        'ਕ' to "क", 'ਖ' to "ख", 'ਗ' to "ग", 'ਘ' to "घ", 'ਙ' to "ङ",
        'ਚ' to "च", 'ਛ' to "छ", 'ਜ' to "ज", 'ਝ' to "झ", 'ਞ' to "ञ",
        'ਟ' to "ट", 'ਠ' to "ठ", 'ਡ' to "ड", 'ਢ' to "ढ", 'ਣ' to "ण",
        'ਤ' to "त", 'ਥ' to "थ", 'ਦ' to "द", 'ਧ' to "ध", 'ਨ' to "न",
        'ਪ' to "प", 'ਫ' to "फ", 'ਬ' to "ब", 'ਭ' to "भ", 'ਮ' to "म",
        'ਯ' to "य", 'ਰ' to "र", 'ਲ' to "ल", 'ਵ' to "व", 'ੜ' to "ड़",
        'ਸ' to "स", 'ਹ' to "ह", 'ਲ਼' to "ळ",
        '਼' to "़", '੍' to "्", 'ਂ' to "ं", 'ੰ' to "ं",
        'ਾ' to "ा", 'ਿ' to "ि", 'ੀ' to "ी", 'ੁ' to "ु", 'ੂ' to "ू",
        'ੇ' to "े", 'ੈ' to "ै", 'ੋ' to "ो", 'ੌ' to "ौ",
        '੦' to "0", '੧' to "1", '੨' to "2", '੩' to "3", '੪' to "4",
        '੫' to "5", '੬' to "6", '੭' to "7", '੮' to "8", '੯' to "9"
    )

    /** Bengali -> unified Devanagari. */
    private val BengaliToDeva: Map<Char, String> = mapOf(
        'অ' to "अ", 'আ' to "आ", 'ই' to "इ", 'ঈ' to "ई", 'উ' to "उ", 'ঊ' to "ऊ",
        'ঋ' to "ऋ", 'এ' to "ए", 'ঐ' to "ऐ", 'ও' to "ओ", 'ঔ' to "औ",
        'ক' to "क", 'খ' to "ख", 'গ' to "ग", 'ঘ' to "घ", 'ঙ' to "ङ",
        'চ' to "च", 'ছ' to "छ", 'জ' to "ज", 'ঝ' to "झ", 'ঞ' to "ञ",
        'ট' to "ट", 'ঠ' to "ठ", 'ড' to "ड", 'ঢ' to "ढ", 'ণ' to "ण",
        'ত' to "त", 'থ' to "थ", 'দ' to "द", 'ধ' to "ध", 'ন' to "न",
        'প' to "प", 'ফ' to "फ", 'ব' to "ब", 'ভ' to "भ", 'ম' to "म",
        'য' to "य", 'র' to "र", 'ল' to "ल", 'শ' to "श", 'ষ' to "ष",
        'স' to "स", 'হ' to "ह",
        // ড় etc. are 2 codepoints (consonant + nukta) - cannot be Char keys.
        // The nukta row below composes them: ড+় -> ड़ etc.
        '়' to "़",
        'ঁ' to "ँ", 'ং' to "ं", 'ঃ' to "ः",
        'া' to "ा", 'ি' to "ि", 'ী' to "ी", 'ু' to "ु", 'ূ' to "ू",
        'ৃ' to "ृ", 'ে' to "े", 'ৈ' to "ै", 'ো' to "ो", 'ৌ' to "ৌ",
        '্' to "्", 'ৎ' to "त",
        '০' to "0", '১' to "1", '২' to "2", '৩' to "3", '৪' to "4",
        '৫' to "5", '৬' to "6", '৭' to "7", '৮' to "8", '৯' to "9"
    )

    /**
     * Unified Devanagari -> Tamil. Consonants map to their base letter with
     * inherent 'அ'; cluster handling (pulli/virama) is applied contextually
     * in transliterateToTarget, not in this table.
     */
    private val DevaToTamil: Map<Char, String> = mapOf(
        'अ' to "அ", 'आ' to "ஆ", 'इ' to "இ", 'ई' to "ஈ", 'उ' to "உ", 'ऊ' to "ஊ",
        'ऋ' to "ரு", 'ॠ' to "ரூ", 'ऌ' to "லு", 'ॡ' to "லூ",
        'ए' to "ஏ", 'ऐ' to "ஐ", 'ओ' to "ஓ", 'औ' to "ஔ",
        // Candra vowels (short e/o from Tamil எ/ஒ) fold back to e/o.
        'ऎ' to "எ", 'ऒ' to "ஒ",
        'क' to "க", 'ख' to "க", 'ग' to "க", 'घ' to "க", 'ङ' to "ங",
        'च' to "ச", 'छ' to "ச", 'ज' to "ஜ", 'झ' to "ஜ", 'ञ' to "ஞ",
        'ट' to "ட", 'ठ' to "ட", 'ड' to "ட", 'ढ' to "ட", 'ण' to "ண",
        'त' to "த", 'थ' to "த", 'द' to "த", 'ध' to "த", 'न' to "ந",
        'ऩ' to "ன", 'प' to "ப", 'फ' to "ப", 'ब' to "ப", 'भ' to "ப", 'म' to "ம",
        'य' to "ய", 'र' to "ர", 'ऱ' to "ற", 'ल' to "ல", 'ळ' to "ள", 'ऴ' to "ழ", 'व' to "வ",
        'श' to "ஷ", 'ष' to "ஷ", 'स' to "ஸ", 'ह' to "ஹ",
        '़' to "", 'ं' to "ஂ", 'ँ' to "ஂ", 'ः' to "ஃ",
        'ा' to "ா", 'ि' to "ி", 'ी' to "ீ", 'ु' to "ு", 'ू' to "ூ",
        'ृ' to "ு", 'ॄ' to "ூ", 'ॢ' to "ு", 'ॣ' to "ூ",
        'े' to "ே", 'ै' to "ை", 'ो' to "ோ", 'ौ' to "ௌ",
        'ॉ' to "ா", 'ॅ' to "ெ", 'ॆ' to "ெ", 'ॊ' to "ொ",
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9"
    )

    /** Unified Devanagari -> Gurmukhi (direct script map; Gurmukhi keeps
     *  inherent-a spelling, so no pulli logic needed). */
    private val DevaToGuru: Map<Char, String> = mapOf(
        'अ' to "ਅ", 'आ' to "ਆ", 'इ' to "ਇ", 'ई' to "ਈ", 'उ' to "ਉ", 'ऊ' to "ਊ",
        'ए' to "ਏ", 'ऐ' to "ਐ", 'ओ' to "ਓ", 'औ' to "ਔ",
        'ऋ' to "ਰ", 'ऎ' to "ਏ", 'ऒ' to "ਓ",
        'क' to "ਕ", 'ख' to "ਖ", 'ग' to "ਗ", 'घ' to "ਘ", 'ङ' to "ਙ",
        'च' to "ਚ", 'छ' to "ਛ", 'ज' to "ਜ", 'झ' to "ਝ", 'ञ' to "ਞ",
        'ट' to "ਟ", 'ठ' to "ਠ", 'ड' to "ਡ", 'ढ' to "ਢ", 'ण' to "ਣ",
        'त' to "ਤ", 'थ' to "ਥ", 'द' to "ਦ", 'ध' to "ਧ", 'न' to "ਨ",
        'प' to "ਪ", 'फ' to "ਫ", 'ब' to "ਬ", 'भ' to "ਭ", 'म' to "ਮ",
        'य' to "ਯ", 'र' to "ਰ", 'ल' to "ਲ", 'व' to "ਵ", 'ळ' to "ਲ", 'ऴ' to "ਲ",
        'श' to "ਸ਼", 'ष' to "ਸ਼", 'स' to "ਸ", 'ह' to "ਹ",
        '़' to "਼", '्' to "੍", 'ं' to "ੰ", 'ँ' to "ੰ", 'ः' to "ਃ",
        'ा' to "ਾ", 'ि' to "ਿ", 'ी' to "ੀ", 'ु' to "ੁ", 'ू' to "ੂ",
        'ृ' to "ਰ", 'े' to "ੇ", 'ै' to "ੈ", 'ो' to "ੋ", 'ौ' to "ੌ",
        'ॉ' to "ਾ", 'ॅ' to "ੇ", 'ॆ' to "ੇ", 'ॊ' to "ੋ",
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9"
    )

    /** FLORES-200 tags for every language the model knows. Old map covered
     *  only hi/ta/pa and silently fell back to hin_Deva for everything else
     *  (bn input was translated AS IF it were Hindi — garbage out). */
    private fun it2Tag(code: String): String = when (code) {
        "as" -> "asm_Beng"
        "bn" -> "ben_Beng"
        "brx" -> "brx_Deva"
        "doi" -> "doi_Deva"
        "gu" -> "guj_Gujr"
        "hi" -> "hin_Deva"
        "kn" -> "kan_Knda"
        "ks" -> "kas_Arab"
        "kok" -> "gom_Deva"
        "mai" -> "mai_Deva"
        "ml" -> "mal_Mlym"
        "mni" -> "mni_Beng"
        "mr" -> "mar_Deva"
        "ne" -> "npi_Deva"
        "or" -> "ory_Orya"
        "pa" -> "pan_Guru"
        "sa" -> "san_Deva"
        "sat" -> "sat_Olck"
        "sd" -> "snd_Arab"
        "ta" -> "tam_Taml"
        "te" -> "tel_Telu"
        "ur" -> "urd_Arab"
        else -> "hin_Deva"
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { decoderPast?.close() }
        encoder = null
        decoder = null
        decoderPast = null
    }
}

/** Bundled hi/ta/bn demo phrasebook. Normalizes punctuation before lookup. */
object DemoPhrasebook {
    private data class Triple3(val hi: String, val ta: String, val bn: String)

    private val PHRASES = listOf(
        Triple3("मुझे मदद चाहिए", "எனக்கு உதவி தேவை", "আমার সাহায্য দরকার"),
        Triple3("पानी बहुत तेज़ है", "தண்ணீர் மிகவும் வேகமாக உள்ளது", "জল খুব দ্রুত বাড়ছে"),
        Triple3("खतरा है यहाँ से हटें", "ஆபத்து இங்கிருந்து விலகுங்கள்", "বিপদ এখান থেকে সরে যান"),
        Triple3("डॉक्टर चाहिए", "மருத்துவர் தேவை", "ডাক্তার দরকার"),
        Triple3("सब सुरक्षित हैं", "அனைவரும் பாதுகாப்பாக உள்ளனர்", "সবাই নিরাপদে আছে"),
        Triple3("हाँ", "ஆம்", "হ্যাঁ"),
        Triple3("नहीं", "இல்லை", "না"),
        Triple3("आप कहाँ हैं", "நீங்கள் எங்கே இருக்கிறீர்கள்", "আপনি কোথায় আছেন"),
        Triple3("खाना चाहिए", "உணவு தேவை", "খাবার দরকার"),
        Triple3("शांत रहें", "அமைதியாக இருங்கள்", "শান্ত থাকুন"),
        Triple3("बचाव दल आ रहा है", "மீட்புக் குழு வருகிறது", "উদ্ধারকারী দল আসছে"),
        Triple3("नमस्ते", "வணக்கம்", "নমস্কার")
    )

    private val WORDS: Map<Pair<String, String>, Map<String, String>> = mapOf(
        ("hi" to "ta") to mapOf(
            // Core emergency/demo vocab
            "पानी" to "தண்ணீர்", "मदद" to "உதவி", "खतरा" to "ஆபத்து",
            "डॉक्टर" to "மருத்துவர்", "सुरक्षित" to "பாதுகாப்பு", "हाँ" to "ஆம்",
            "नहीं" to "இல்லை", "खाना" to "உணவு", "नमस्ते" to "வணக்கம்",
            // Introductions / names
            "मेरा" to "என்", "मेरी" to "என்", "तेरा" to "உன்", "तेरी" to "உன்",
            "तुम्हारा" to "உன்", "तुम्हारी" to "உன்", "तुम्हारा" to "உன்",
            "नाम" to "பெயர்", "है" to "", "हैं" to "", "हो" to "இருக்கிறாய்",
            "क्या" to "என்ன", "कौन" to "யார்", "कहाँ" to "எங்கே",
            "मैं" to "நான்", "तुम" to "நீ", "आप" to "நீங்கள்",
            "और" to "மற்றும்", "बहुत" to "மிகவும்", "थोड़ा" to "கொஞ்சம்",
            // Urgency / situations
            "तेज़" to "வேகமாக", "धीरे" to "மெதுவாக", "यहाँ" to "இங்கே",
            "वहाँ" to "அங்கே", "यहाँ" to "இங்கே", "से" to "இருந்து",
            "में" to "", "पर" to "", "का" to "", "की" to "", "के" to "",
            "चाहिए" to "தேவை", "चाहिये" to "தேவை", "दो" to "கொடு",
            "आओ" to "வா", "जाओ" to "போ", "आ" to "வா", "रहा" to "",
            "रही" to "", "रहे" to "", "गा" to "", "गी" to "", "गे" to "",
            "बचाव" to "மீட்பு", "दल" to "குழு", "शांत" to "அமைதி",
            "सब" to "அனைவரும்", "लोग" to "மக்கள்", "बच्चा" to "குழந்தை",
            "अस्पताल" to "மருத்துவமனை", "दवा" to "மருந்து", "आग" to "நெருப்பு",
            "बाढ़" to "வெள்ளம்", "तूफ़ान" to "புயல்", "भूकंप" to "நிலநடுக்கம்",
            "रास्ता" to "பாதை", "घर" to "வீடு", "सड़क" to "சாலை"
        ),
        ("ta" to "hi") to mapOf(
            "தண்ணீர்" to "पानी", "உதவி" to "मदद", "ஆபத்து" to "खतरा",
            "மருத்துவர்" to "डॉक्टर", "ஆம்" to "हाँ", "இல்லை" to "नहीं",
            "உணவு" to "खाना", "வணக்கம்" to "नमस्ते",
            "என்" to "मेरा", "பெயர்" to "नाम", "உன்" to "तुम्हारा",
            "என்ன" to "क्या", "யார்" to "कौन", "எங்கே" to "कहाँ",
            "நான்" to "मैं", "நீ" to "तुम", "நீங்கள்" to "आप",
            "மற்றும்" to "और", "மிகவும்" to "बहुत", "கொஞ்சம்" to "थोड़ा",
            "வேகமாக" to "तेज़", "மெதுவாக" to "धीरे", "இங்கே" to "यहाँ",
            "அங்கே" to "वहाँ", "தேவை" to "चाहिए", "குழந்தை" to "बच्चा",
            "மருத்துவமனை" to "अस्पताल", "மருந்து" to "दवा",
            "நெருப்பு" to "आग", "வெள்ளம்" to "बाढ़", "புயல்" to "तूफ़ान",
            "வீடு" to "घर", "சாலை" to "सड़क", "மீட்பு" to "बचाव",
            "குழு" to "दल", "அமைதி" to "शांत", "அனைவரும்" to "सब",
            "மக்கள்" to "लोग"
        ),
        ("hi" to "bn") to mapOf(
            "पानी" to "জল", "मदद" to "সাহায্য", "खतरा" to "বিপদ",
            "डॉक्टर" to "ডাক্তার", "हाँ" to "হ্যাঁ", "नहीं" to "না",
            "खाना" to "খাবার", "नमस्ते" to "নমস্কার"
        ),
        ("bn" to "hi") to mapOf(
            "জল" to "पानी", "সাহায্য" to "मदद", "বিপদ" to "खतरा",
            "ডাক্তার" to "डॉक्टर", "হ্যাঁ" to "हाँ", "না" to "नहीं",
            "খাবার" to "खाना", "নমস্কার" to "नमस्ते"
        ),
        ("ta" to "bn") to mapOf(
            "தண்ணீர்" to "জল", "உதவி" to "সাহায্য", "ஆபத்து" to "বিপদ",
            "மருத்துவர்" to "ডাক্তার", "ஆம்" to "হ্যাঁ", "இல்லை" to "না",
            "வணக்கம்" to "নমস্কার"
        ),
        ("bn" to "ta") to mapOf(
            "জল" to "தண்ணீர்", "সাহায্য" to "உதவி", "বিপদ" to "ஆபத்து",
            "டாக்டர" to "மருத்துவர்", "হ্যাঁ" to "ஆம்", "না" to "இல்லை",
            "নমস্কার" to "வணக்கம்"
        )
    )

    /** NFKD: phone keyboards type ज़ as ज+़ (U+091C+U+093C) while the
     *  phrasebook stores the precomposed U+095B — that pairing is a
     *  COMPATIBILITY mapping, so only NFKD (not NFC) aligns both sides. */
    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
            .trim()
            .replace(Regex("[।.,?!;:\"'()\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")

    /** Exact phrase hit, else word-level gloss, else null. */
    fun translate(text: String, src: String, tgt: String): String? {
        val norm = normalize(text)
        for (p in PHRASES) {
            val from = when (src) { "hi" -> p.hi; "ta" -> p.ta; "bn" -> p.bn; else -> null }
            if (from != null && normalize(from) == norm) {
                return when (tgt) { "hi" -> p.hi; "ta" -> p.ta; "bn" -> p.bn; else -> null }
            }
        }
        val dict = WORDS[src to tgt] ?: return null
        val words = norm.split(" ")
        var hits = 0
        val out = words.map { w -> dict[w]?.also { hits++ } ?: w }
        return if (hits > 0) out.joinToString(" ") else null
    }
}
