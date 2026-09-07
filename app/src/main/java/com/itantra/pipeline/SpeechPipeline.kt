package com.itantra.pipeline

import com.itantra.models.AppSettings
import com.itantra.models.ChatMessage
import com.itantra.models.DeliveryStatus
import com.itantra.models.Emotion
import com.itantra.models.MessageDirection
import com.itantra.models.ProcessingResult
import com.itantra.models.ReceiveResult
import com.itantra.ml.ASRManager
import com.itantra.ml.EmotionManager
import com.itantra.ml.ProsodyExtractor
import com.itantra.ml.TTSManager
import com.itantra.ml.TranslationManager
import com.itantra.audio.AudioPlayer
import com.itantra.network.Packet
import com.itantra.ui.EmergencyAlertManager
import com.itantra.utils.AudioFiles
import com.itantra.utils.PerformanceMonitor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Send pipeline: audio -> (ASR || prosody || emotion) -> packet/message.
 * Receive pipeline: packet -> translate? -> synthesize -> play | alert.
 */
class SpeechPipeline(
    private val asr: ASRManager,
    private val translator: TranslationManager,
    private val tts: TTSManager,
    private val emotion: EmotionManager,
    private val player: AudioPlayer,
    private val emergency: EmergencyAlertManager,
    private val audioFiles: AudioFiles,
    private val perf: PerformanceMonitor
) {
    /** Returns null when there is no usable transcription. */
    suspend fun processUtterance(
        audio: FloatArray,
        srcLang: String,
        tgtLang: String
    ): ProcessingResult? = coroutineScope {
        val t0 = System.currentTimeMillis()
        val asrD = async { perf.timed("asr") { asr.transcribe(audio, srcLang) } }
        val prosD = async { perf.timed("prosody") { ProsodyExtractor.extract(audio) } }
        val asrRes = asrD.await()
        val prosody = prosD.await()
        if (asrRes.text.isBlank()) return@coroutineScope null
        val emoRes = async {
            perf.timed("emotion") { emotion.classify(audio, prosody) }
        }.await()

        val packet = Packet(
            src = srcLang, tgt = tgtLang, text = asrRes.text,
            emo = emoRes.emotionId, prosody = prosody
        )
        val message = packet.toMessage(MessageDirection.SENT).copy(
            id = UUID.randomUUID().toString(),
            emotionConfidence = emoRes.confidence,
            emotionLabel = emoRes.emotion.label,
            deliveryStatus = DeliveryStatus.SENT
        )
        ProcessingResult(
            transcription = asrRes, prosody = prosody, emotion = emoRes,
            message = message, totalProcessingMs = System.currentTimeMillis() - t0
        )
    }

    /** Text-mode send path (no microphone / no ASR model). */
    fun processText(text: String, srcLang: String, tgtLang: String): ProcessingResult? {
        if (text.isBlank()) return null
        val t0 = System.currentTimeMillis()
        val packet = Packet(src = srcLang, tgt = tgtLang, text = text.trim())
        val message = packet.toMessage(MessageDirection.SENT).copy(
            id = UUID.randomUUID().toString(),
            deliveryStatus = DeliveryStatus.SENT
        )
        return ProcessingResult(
            transcription = com.itantra.models.TranscriptionResult(text, srcLang, 1f, 0),
            prosody = packet.prosody,
            emotion = com.itantra.models.EmotionResult(Emotion.NEUTRAL, 1f),
            message = message, totalProcessingMs = System.currentTimeMillis() - t0
        )
    }

    /** Receive path. Plays locally in [playLang] (receiver's language). */
    suspend fun handleReceived(
        packet: Packet,
        playLang: String,
        settings: AppSettings
    ): ReceiveResult {
        val t0 = System.currentTimeMillis()
        var msg = packet.toMessage(MessageDirection.RECEIVED)
        var translated = false

        if (packet.src != playLang) {
            val tr = perf.timed("mt") { translator.translate(packet.text, packet.src, playLang) }
            msg = if (tr.fallbackUsed && tr.text == packet.text && packet.src != playLang &&
                com.itantra.ml.DemoPhrasebook.translate(packet.text, packet.src, playLang) == null
            ) {
                msg.copy(translationFailed = true, mtFallback = false)
            } else {
                translated = tr.text != packet.text
                msg.copy(
                    translatedText = tr.text.takeIf { it != packet.text },
                    translationFailed = false,
                    // Visible badge upstream: phrasebook output is flagged, never
                    // presented as neural translation.
                    mtFallback = tr.fallbackUsed && tr.text != packet.text
                )
            }
        }

        val speakText = msg.translatedText ?: msg.originalText
        val ttsSettings = packet.prosody.toTtsSettings(packet.emo)
        val synth = perf.timed("tts") { tts.synthesize(speakText, ttsSettings, playLang) }

        var audioPath: String? = null
        if (synth != null) {
            audioPath = audioFiles.saveUtterance(packet.id, synth.audioData, synth.sampleRate)
            msg = msg.copy(audioPath = audioPath, audioDurationMs = synth.durationMs)
            if (msg.isEmergency) {
                emergency.trigger(synth.audioData, synth.sampleRate, settings)
            } else {
                player.play(synth.audioData, synth.sampleRate)
            }
            msg = msg.copy(wasPlayed = true)
        }
        return ReceiveResult(
            message = msg, translated = translated, audioPath = audioPath,
            isEmergency = msg.isEmergency, totalProcessingMs = System.currentTimeMillis() - t0
        )
    }
}
