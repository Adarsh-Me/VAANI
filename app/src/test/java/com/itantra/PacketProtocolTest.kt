package com.itantra.network

import com.itantra.models.ProsodyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PacketProtocolTest {

    private fun packet(text: String = "मुझे मदद चाहिए") = Packet(
        id = UUID.randomUUID().toString(),
        src = "hi",
        tgt = "ta",
        text = text,
        emo = 1,
        prosody = ProsodyData(0.5f, 0.3f, 0.5f, 0.4f, 0.1f)
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val p = packet()
        val back = Packet.deserialize(p.serialize())
        assertNotNull(back)
        assertEquals(p.id, back!!.id)
        assertEquals("hi", back.src)
        assertEquals("ta", back.tgt)
        assertEquals(p.text, back.text)
        assertEquals(1, back.emo)
        assertTrue(back.isEmergency)
        assertEquals(p.prosody.toBytes().toList(), back.prosody.toBytes().toList())
    }

    @Test
    fun wireSize_typicalSentenceUnderBleLimit() {
        val size = packet().serialize().size
        assertTrue("wire=$size", size <= 512)
    }

    @Test
    fun rejects_blankText() {
        assertNull(Packet.deserialize(packet("   ").serialize()))
    }

    @Test
    fun rejects_textOver400Chars() {
        assertNull(Packet.deserialize(packet("अ".repeat(401)).serialize()))
    }

    @Test
    fun rejects_badEmotion() {
        val p = packet().copy(emo = 9)
        assertNull(Packet.deserialize(p.serialize()))
    }

    @Test
    fun rejects_unknownLanguage() {
        val p = packet().copy(src = "xx")
        assertNull(Packet.deserialize(p.serialize()))
    }

    @Test
    fun rejects_expiredTimestamp() {
        val p = packet().copy(ts = System.currentTimeMillis() - 10 * 60 * 1000L)
        assertNull(Packet.deserialize(p.serialize()))
    }

    @Test
    fun rejects_garbage() {
        assertNull(Packet.deserialize("not json".toByteArray()))
    }

    @Test
    fun chunker_singleFrameFastPath() {
        val data = packet().serialize()
        val frames = PacketChunker.chunk(data)
        assertEquals(1, frames.size)
        val r = PacketChunker.Reassembler()
        assertNotNull(r.feed(frames[0]))
    }

    @Test
    fun chunker_multiFrameReassembly() {
        val big = ByteArray(1200) { (it % 250 + 1).toByte() }
        // Force chunking with a tiny MTU; bypass Packet.deserialize (raw bytes).
        val frames = PacketChunker.chunk(big, mtuPayload = 100)
        assertTrue(frames.size > 1)
        val r = PacketChunker.Reassembler()
        var out: ByteArray? = null
        // Deliver out of order to prove ordering works.
        for (f in frames.reversed()) {
            out = r.feed(f) ?: out
        }
        assertNotNull(out)
        assertEquals(big.toList(), out!!.toList())
    }
}
