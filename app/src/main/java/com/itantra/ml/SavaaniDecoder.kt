package com.itantra.ml

/**
 * SraVaani TDT transducer greedy decoder — pure Kotlin port of the
 * host-verified ONNX smoke loop (tools/export_sravaani_final.py).
 *
 * Three ONNX sessions:
 *  encoder: audio_signal [1,128,T] + length [1] -> enc_out [1,1024,T'] (T' = ceil(T/8))
 *  predict: targets [1,1] + h_in_1 [1,1,640] + h_in_2 [1,1,640]
 *           -> pred [1,640,1] + h_out_1 + h_out_2 (LSTM prediction network)
 *  joint:   enc [1,1024,1] + pred [1,640,1] -> logits [1,1,5006]
 *           (5001 vocab incl. blank + 5 TDT duration heads)
 *
 * TDT greedy: emit token (non-blank) + jump frames = argmax(duration head),
 * blank advances 1 frame. SOS = blank. max_symbols guard per frame.
 */
object SavaaniDecoder {

    const val VOCAB_SIZE = 5000      // real tokens
    const val BLANK_ID = 5000        // vocab_size = blank
    const val DUR_HEADS = 5          // durations 0..4
    const val LOGIT_STRIDE = VOCAB_SIZE + 1 + DUR_HEADS // 5006
    const val ENC_DIM = 1024
    const val PRED_DIM = 640
    const val MAX_SYMBOLS_PER_FRAME = 10

    /** Pure decode math — testable without ONNX.
     *  @param logits flat [T][5006] frames
     *  @return decoded token ids (without blanks)
     */
    fun decodeFrames(
        logits: FloatArray,
        frames: Int,
        emit: (token: Int, frame: Int) -> Unit
    ) {
        var t = 0
        var steps = 0
        while (t < frames && steps < frames * MAX_SYMBOLS_PER_FRAME) {
            steps++
            val base = t * LOGIT_STRIDE
            // token part: [0 .. vocab_size]
            var tok = 0
            var best = Float.NEGATIVE_INFINITY
            for (i in 0..VOCAB_SIZE) {
                val v = logits[base + i]
                if (v > best) { best = v; tok = i }
            }
            // duration part: [vocab_size+1 .. +5]
            var dur = 1
            var dbest = Float.NEGATIVE_INFINITY
            for (i in 0 until DUR_HEADS) {
                val v = logits[base + VOCAB_SIZE + 1 + i]
                if (v > dbest) { dbest = v; dur = i }
            }
            if (tok != BLANK_ID) emit(tok, t)
            t += maxOf(dur, 0)
        }
    }

    /** CTC-free text builder: join SPM pieces, ▁ -> space. */
    fun toText(tokens: List<Int>, vocab: List<String>): String {
        val sb = StringBuilder()
        for (id in tokens) {
            if (id == BLANK_ID) continue
            sb.append(vocab.getOrElse(id) { "" })
        }
        return sb.toString().replace("▁", " ").trim().replace(Regex("\\s+"), " ")
    }
}
