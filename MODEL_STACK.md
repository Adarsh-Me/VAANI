# iTantra — Complete Model Stack
**Offline on-device neural pipeline: Speech → Text → Translation → Speech, for Indian languages (SIH26173)**

All models run **100% on-device** (ONNX Runtime, Android) — no internet needed after one-time model download. Models are fetched at first launch from pinned URLs and live in the app's private storage; the model catalog (`app/src/main/assets/model_catalog.json`) lets any model be swapped by editing one JSON entry.

## Pipeline Overview
```
Mic → Silero VAD → IndicConformer ASR → IndicTrans2 MT ─┬→ TTS (Piper/MMS) → Speaker
                                  (prosody side-channel)┘
                16 kHz mono PCM throughout · BLE or loopback transport
```

---

## 1. Speech-to-Text (ASR)

| | |
|---|---|
| **Model** | IndicConformer — hybrid CTC decoder (NeMo export, INT8-quantized) |
| **Languages** | Per-language graphs: Hindi, Tamil, Bengali, Punjabi (22-language family available) |
| **Size** | ~197.6 MB per language (INT8) |
| **License** | Apache-2.0 (upstream AI4Bharat, MIT) |
| **Source** | huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx |
| **Runtime** | ONNX Runtime Android, per-language OrtSession, NNAPI |

**Tensor I/O (verified from export notebook):**
- Inputs: `audio_signal [1, 80, T]` (80-dim log-mel filterbank, 25 ms window / 10 ms shift, pre-emphasis 0.97, per-utterance mean-normalized — implemented in pure Kotlin, `FbankExtractor`) + `length [1]`
- Output: `logprobs [1, T', 5633]`; blank id = **5632**; shared `tokens.txt` vocab (BPE, ▁ = space)
- Language mask is baked into each per-language graph (no language-id tensor)
- Decoding: greedy CTC collapse (drop blanks, merge repeats)

## 2. Translation (MT)

| | |
|---|---|
| **Model** | **IndicTrans2-320M** (Indic→Indic distilled) — INT8 dynamic quantization, ONNX |
| **Pairs** | Direct 22×22 = 462 Indic language pairs (no English pivot) |
| **Size** | Encoder 0.8 MB stub + 120 MB weights · no-past decoder 2 MB + 203 MB shared weights · **KV-cache decoder 1.9 MB** · tokenizers 47.7 MB — **total ≈ 373 MB** |
| **License** | MIT |
| **Source** | huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8 |
| **Runtime** | ONNX Runtime, custom Kotlin BPE tokenizer + greedy KV-cache generation |

**Inference spec (reverse-engineered + verified against the official `translate.py`):**
- Input ids: `[src_tag, tgt_tag, BPE(words…), EOS=2]` — both FLORES-200 tags at front (e.g. `hin_Deva`, `tam_Taml`, 22 tags)
- Source text is preprocessed into the **unified Devanagari space** (IndicProcessor equivalent: Tamil/Gurmukhi/Bengali → Devanagari char maps, candra vowels ऎ/ॊ)
- Tokenizer: HuggingFace BPE, Metaspace (▁ prefix per word), 130,526-entry vocab clamped to encoder embedding size 122,706
- Generation: step 0 through the no-past decoder (yields logits + 72 `present.*` KV tensors), then one-token steps through `decoder_with_past_model.onnx` — the no-past re-feed path truncates output (verified) so the KV path is mandatory
- Output: Devanagari-unified text → script transliteration back to target (Tamil pulli logic, homorganic nasals, Gurmukhi, Bengali)

## 3. Text-to-Speech (TTS)

| Language | Model | Size | Rate | License | Engine |
|---|---|---|---|---|---|
| Hindi | **Piper VITS** `hi_IN-pratham-medium` | 67 MB | 22.05 kHz | MIT | sherpa-onnx JNI |
| Tamil | **Meta MMS VITS** `tam` | 114 MB | 16 kHz | CC-BY-NC-4.0 | custom ONNX engine |
| Punjabi | **Meta MMS VITS** `pan` | 114 MB | 16 kHz | CC-BY-NC-4.0 | custom ONNX engine |
| Other | Android system TTS | 0 | — | platform | fallback |

- MMS quirk fixed: duration predictor races at nominal speed → effective `length_scale × 2.3` + peak-normalization to 0.9 for intelligible pace/loudness
- Char-level tokenization from `tokens.txt`; unknown chars dropped
- Prosody from the sender (rate, pitch, energy — 5-byte side-channel) modulates speed/pitch

## 4. Voice Activity Detection (VAD)

| | |
|---|---|
| **Model** | Silero VAD v4.0 ONNX |
| **Size** | 1.8 MB |
| **License** | MIT |
| **Runtime** | ONNX Runtime, streaming LSTM state, 512-sample chunks @ 16 kHz, speech threshold 0.5 |

## 5. Emotion Detection (2-tier)

- **Tier A (active, 0 MB)**: heuristic prosody classifier — pitch mean/range (autocorrelation), speaking rate, RMS energy, pause ratio
- **Tier B (optional)**: emotion2vec (~20 MB, 5 classes: neutral/distress/angry/calm/happy) — catalog entry exists; distress tag triggers the emergency alarm path

## 6. Model Management
- **Catalog**: `assets/model_catalog.json` — every model (id, pinned URL, sha256, size, per-language mapping); swap any model by editing JSON, no code change
- **Downloader**: runtime download with progress/resume/retries, SHA-256 verify, Room-registry bookkeeping
- **Footprint**: APK 117.5 MB (signed release) · models ≈ 660 MB for hi+ta+pa full pipeline · ASR +197.6 MB per language

## Size / License Summary
| Stack | Size | License |
|---|---|---|
| ASR (per lang) | 197.6 MB | Apache-2.0 |
| MT (all pairs) | 373 MB | MIT |
| TTS hi | 67 MB | MIT |
| TTS ta/pa | 228 MB | CC-BY-NC-4.0 |
| VAD | 1.8 MB | MIT |

*All components verified end-to-end on device: Hindi↔Tamil, Hindi↔Punjabi translations match the official reference pipeline word-for-word; neural voices speak on receive; 29/29 unit tests pass.*
