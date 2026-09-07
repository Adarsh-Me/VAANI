package com.itantra.ml

import android.content.Context
import com.itantra.models.StorageLayout
import org.json.JSONObject

/**
 * Tokenizer for IndicTrans2. MVP: whitespace + danda split with language
 * tokens; upgrades to the downloaded tokenizer.json when present.
 * Format: [<src>] tokens... [<tgt>], greedy decode, EOS stop, max 128.
 */
class IndicTokenizer(context: Context) {

    private var wordToId: Map<String, Int>? = null
    private var idToWord: List<String>? = null

    companion object {
        const val MAX_NEW_TOKENS = 128
        const val MAX_SEQ = 2048
        fun langToken(code: String) = "<$code>"
    }

    init {
        runCatching {
            val f = java.io.File(context.filesDir, StorageLayout.MT_TOKENIZER)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                val vocab = json.getJSONObject("vocab")
                val map = HashMap<String, Int>(vocab.length())
                val inv = ArrayList<String>(vocab.length())
                val keys = vocab.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = vocab.getInt(k)
                }
                val sorted = map.entries.sortedBy { it.value }
                for (e in sorted) inv.add(e.key)
                wordToId = map
                idToWord = inv
            }
        }
    }

    val isPretrained: Boolean get() = wordToId != null

    fun tokenize(text: String): List<String> =
        text.trim().split(Regex("[\\s।.,?!;:]")).filter { it.isNotEmpty() }

    fun encode(text: String, src: String, tgt: String): IntArray {
        val w2i = wordToId
        val toks = ArrayList<String>(tokenize(text).size + 2)
        toks.add(langToken(src))
        toks.addAll(tokenize(text))
        toks.add(langToken(tgt))
        if (w2i == null) {
            // Char-hash fallback: deterministic, reversible-ish for tests only.
            return toks.flatMap { it.toList().map { c -> c.code and 0x7FFF } }.toIntArray()
        }
        val unk = w2i["<unk>"] ?: 1
        return toks.map { w2i[it] ?: unk }.toIntArray().take(MAX_SEQ).toIntArray()
    }

    fun decode(ids: IntArray): String {
        val i2w = idToWord ?: return ""
        return ids.asIterable().mapNotNull { i2w.getOrNull(it) }
            .filter { !it.startsWith("<") }
            .joinToString(" ")
    }

    fun eosId(): Int = wordToId?.get("</s>") ?: -1
}
