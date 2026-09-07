package com.itantra.audio

import com.itantra.models.AudioSpec
import com.itantra.models.VadSpec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release-flush contract: a hold that ends mid-speech (PTT release) must still
 * emit the buffered speech as UtteranceReady — this is the walkie-talkie
 * "speak then release" path that the old cancel-on-release lost.
 */
class VadSegmentationTest {

    // Context is only touched by initialize(); segmentation runs model-free here.
    private val vad = VADManager(org.mockito.Mockito.mock(android.content.Context::class.java))

    /** Tone with RMS ~0.35 -> energy probability saturates to 1.0 (speech). */
    private fun speechChunk() = FloatArray(AudioSpec.CHUNK_SIZE) {
        (0.5 * sin(2 * PI * 440.0 * it / AudioSpec.SAMPLE_RATE)).toFloat()
    }

    private fun silenceChunk() = FloatArray(AudioSpec.CHUNK_SIZE) // rms 0 -> prob 0

    @Test
    fun `release mid-speech flushes buffered utterance`() = runTest {
        val speech = List(10) { speechChunk() } // 320 ms >= 250 ms min speech
        val events = vad.segmentUtterance(flowOf(*speech.toTypedArray())).toList()
        val ready = events.filterIsInstance<VadEvent.UtteranceReady>()
        assertTrue("expected trailing flush on stream end, got $events", ready.isNotEmpty())
        assertTrue(ready[0].audio.size >= VadSpec.MIN_SPEECH_DURATION_MS * 16) // 16 samples/ms
    }

    @Test
    fun `silence hold emits nothing`() = runTest {
        val silence = List(60) { silenceChunk() } // ~2 s of hold in silence
        val events = vad.segmentUtterance(flowOf(*silence.toTypedArray())).toList()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `too-short burst is discarded`() = runTest {
        val burst = List(3) { speechChunk() } // 96 ms < 250 ms min speech
        val events = vad.segmentUtterance(flowOf(*burst.toTypedArray())).toList()
        // SpeechStarted may fire (UI shows recording), but no utterance may be
        // sent — the caller only acts on UtteranceReady.
        assertTrue(
            "short burst must not produce UtteranceReady, got $events",
            events.none { it is VadEvent.UtteranceReady }
        )
    }
}
