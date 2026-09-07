package com.itantra.ml

import android.content.Context
import com.itantra.models.StorageLayout
import java.io.File
import org.json.JSONObject

/** IndicTrans2 wire format constants (verified from tokenizer_meta.json + generation_config.json). */
object It2Format {
    /** src_dict_size: encoder embedding size; ids >= this must be <unk>. */
    const val SRC_DICT_SIZE = 122706
    /** tgt_dict_size: decoder logits range. */
    const val TGT_DICT_SIZE = 122672
    const val DECODER_START_ID = 2
    const val EOS_ID = 2
    const val MAX_SRC_TOKENS = 240
    const val MAX_NEW_TOKENS = 128
}

/**
 * IndicTrans2 BPE tokenizer (HuggingFace `tokenizers` BPE model:
 * tokenizer_src.json / tokenizer_tgt.json, Metaspace pre-tokenizer,
 * byte_fallback=false, fuse_unk=true).
 *
 * Pure-Kotlin port of the encode path:
 *  - Metaspace: prepend U+2581 to every whitespace-split word
 *  - BPE: rank-ordered merges over (vocab, merges) tables
 *  - unknown chars fuse into a single <unk> (fuse_unk)
 * Decode: strip specials, concat, U+2581 -> space.
 */
class It2BpeTokenizer(context: Context, side: String) {

    private val vocab: Map<String, Int>
    private val ids: List<String>
    private val merges: Map<Pair<String, String>, Int>
    val unkId: Int
    val padId = 1
    val eosId = 2

    val isLoaded: Boolean

    init {
        var v: Map<String, Int>? = null
        var m: Map<Pair<String, String>, Int>? = null
        runCatching {
            val name = if (side == "tgt") "tokenizer_tgt.json" else "tokenizer_src.json"
            val f = File(context.filesDir, "models/mt/$name")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                val model = json.getJSONObject("model")
                val vo = model.getJSONObject("vocab")
                val map = HashMap<String, Int>(vo.length())
                val keys = vo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = vo.getInt(k)
                }
                val ma = model.getJSONArray("merges")
                val mm = HashMap<Pair<String, String>, Int>(ma.length())
                for (i in 0 until ma.length()) {
                    val pair = ma.getJSONArray(i)
                    mm[Pair(pair.getString(0), pair.getString(1))] = i
                }
                v = map
                m = mm
            }
        }
        vocab = v ?: emptyMap()
        merges = m ?: emptyMap()
        val inv = ArrayList<String>(vocab.size)
        if (vocab.isNotEmpty()) {
            val sorted = vocab.entries.sortedBy { it.value }
            for (e in sorted) {
                while (inv.size <= e.value) inv.add("<unk>")
                inv[e.value] = e.key
            }
        }
        ids = inv
        unkId = vocab["<unk>"] ?: 3
        isLoaded = vocab.isNotEmpty() && merges.isNotEmpty()
    }

    /** Encode with explicit language tags, matching IndicTransTokenizer format.
     *
     * CRITICAL (verified against the official ONNX-bundle translate.py):
     * IndicProcessor produces  "{src_tag} {tgt_tag} {text}"  and the tokenizer
     * post-processor appends EOS — so the model input is
     *   [src_tag, tgt_tag, words..., EOS]
     * with BOTH tags at the front. The decoder emits target text conditioned
     * on the trailing position of the pair. Ids are clamped to the encoder's
     * embedding range (SRC_DICT_SIZE): tokenizer_src.json holds 130526
     * entries but the encoder embeds only 122706 — unclamped high ids crash
     * or silently corrupt inference.
     */
    fun encode(text: String, srcTag: String, tgtTag: String): IntArray {
        if (!isLoaded) return intArrayOf()
        val out = ArrayList<Int>()
        out.add(clampSrc(vocab[srcTag] ?: unkId))
        out.add(clampSrc(vocab[tgtTag] ?: unkId))
        for (word in text.trim().split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            out.addAll(bpe("▁$word"))
        }
        out.add(eosId)
        return out.toIntArray()
    }

    /** Ids at or beyond the encoder's embedding size must become <unk>. */
    private fun clampSrc(id: Int): Int =
        if (id >= It2Format.SRC_DICT_SIZE) unkId else id

    /** Standard BPE: start from chars, repeatedly merge lowest-rank pair. */
    private fun bpe(token: String): List<Int> {
        if (vocab.containsKey(token)) return listOf(clampSrc(vocab.getValue(token)))
        val parts = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            val c = token[i].toString()
            if (vocab.containsKey(c)) {
                parts.add(c); i++
            } else {
                // fuse_unk: skip the whole unknown run, emit one <unk>
                var j = i + 1
                while (j < token.length && !vocab.containsKey(token[j].toString())) j++
                parts.add("\uFFFF"); i = j
            }
        }
        while (true) {
            var best: Pair<Int, Int>? = null
            var bestRank = Int.MAX_VALUE
            for (k in 0 until parts.size - 1) {
                if (parts[k] == "\uFFFF" || parts[k + 1] == "\uFFFF") continue
                val r = merges[Pair(parts[k], parts[k + 1])] ?: continue
                if (r < bestRank) {
                    bestRank = r; best = Pair(k, k + 1)
                }
            }
            if (best == null) break
            val (a, b) = best
            parts[a] = parts[a] + parts[b]
            parts.removeAt(b)
        }
        return parts.map { if (it == "\uFFFF") unkId else clampSrc(vocab[it] ?: unkId) }
    }

    fun decode(idList: List<Int>): String {
        if (!isLoaded) return ""
        val sb = StringBuilder()
        for (id in idList) {
            val p = ids.getOrNull(id) ?: continue
            if (p == "<s>" || p == "</s>" || p == "<pad>" || p == "<unk>") continue
            if (p == "hin_Deva" || p == "tam_Taml" || p == "pan_Guru" ||
                p == "eng_Latn" || p.length > 1 && p.endsWith("_Deva") ||
                p.endsWith("_Taml") || p.endsWith("_Guru") || p.endsWith("_Latn")
            ) continue
            sb.append(p)
        }
        return sb.toString().replace("▁", " ").trim()
    }
}
