package com.itantra.network

import com.itantra.data.PacketValidator
import com.itantra.models.ChatMessage
import com.itantra.models.DeliveryStatus
import com.itantra.models.Emotion
import com.itantra.models.MessageDirection
import com.itantra.models.PacketLimits
import com.itantra.models.ProsodyData
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Wire packet: compact JSON over BLE GATT (~200 B typical, <= 512 B).
 * Short keys: id/src/tgt/text/emo/pros/ts + ttl/hops/v.
 */
data class Packet(
    val id: String = UUID.randomUUID().toString(),
    val src: String,
    val tgt: String,
    val text: String,
    val emo: Int = Emotion.NEUTRAL.id,
    val prosody: ProsodyData = ProsodyData.NEUTRAL,
    val ts: Long = System.currentTimeMillis(),
    val ttl: Int = PacketLimits.DEFAULT_TTL,
    val hops: Int = 0,
    val version: Int = PacketLimits.PROTOCOL_VERSION
) {
    val isEmergency: Boolean get() = emo == Emotion.DISTRESS.id

    fun serialize(): ByteArray {
        val json = JSONObject()
            .put("id", id)
            .put("src", src)
            .put("tgt", tgt)
            .put("text", text)
            .put("emo", emo)
            .put("pros", Base64.getEncoder().encodeToString(prosody.toBytes()))
            .put("ts", ts)
            .put("ttl", ttl)
            .put("hops", hops)
            .put("v", version)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun toMessage(direction: MessageDirection): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        utteranceId = id,
        sourceLang = src,
        targetLang = tgt,
        originalText = text,
        emotionTag = emo,
        emotionLabel = Emotion.fromId(emo).label,
        prosody = prosody,
        direction = direction,
        deliveryStatus = DeliveryStatus.PENDING,
        timestamp = ts
    )

    companion object {
        /** Returns null when the packet fails validation (caller logs + discards). */
        fun deserialize(bytes: ByteArray): Packet? {
            if (bytes.size > PacketLimits.MAX_SIZE_BYTES) return null
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val id = json.optString("id", "")
                val src = json.optString("src", "")
                val tgt = json.optString("tgt", "")
                val text = json.optString("text", "")
                val emo = json.optInt("emo", 0)
                val prosB64 = json.optString("pros", "")
                val ts = json.optLong("ts", 0)
                val version = json.optInt("v", 1)
                val err = PacketValidator.validate(
                    id, src, tgt, text, emo, prosB64, ts, version, bytes.size
                )
                if (err != null) return null
                Packet(
                    id = id, src = src, tgt = tgt, text = text, emo = emo,
                    prosody = ProsodyData.fromBytes(
                        Base64.getDecoder().decode(prosB64)
                    ),
                    ts = ts,
                    ttl = json.optInt("ttl", PacketLimits.DEFAULT_TTL),
                    hops = json.optInt("hops", 0),
                    version = version
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Segmentation for payloads that exceed the BLE char limit.
 * Header (10 B): [0..7] id prefix ASCII, [8] frame index, [9] bit0=last + bits1..7=total.
 */
object PacketChunker {
    const val HEADER = 10
    const val MAX_FRAMES = 16

    fun chunk(data: ByteArray, mtuPayload: Int = PacketLimits.MAX_SIZE_BYTES): List<ByteArray> {
        if (data.size <= mtuPayload) return listOf(data)
        val body = mtuPayload - HEADER
        val frames = (data.size + body - 1) / body
        require(frames <= MAX_FRAMES) { "packet too large even chunked" }
        val prefix = data.take(8).toByteArray()
        return (0 until frames).map { i ->
            val start = i * body
            val end = minOf(start + body, data.size)
            val last = i == frames - 1
            val head = ByteArray(HEADER)
            System.arraycopy(prefix, 0, head, 0, minOf(8, prefix.size))
            head[8] = i.toByte()
            head[9] = ((frames shl 1) or (if (last) 1 else 0)).toByte()
            head + data.copyOfRange(start, end)
        }
    }

    class Reassembler(private val timeoutMs: Long = 5000) {
        private data class Slot(
            val frames: MutableMap<Int, ByteArray>,
            val total: Int,
            val firstSeen: Long
        )
        private val slots = HashMap<String, Slot>()

        /** Returns the full payload once the last frame arrives, else null. */
        @Synchronized
        fun feed(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER) return frame // unchunked fast path
            // Heuristic: chunked frames carry the 10 B header; unchunked JSON starts with '{'.
            if (frame[0] == '{'.code.toByte()) return frame
            val key = frame.copyOfRange(0, 8).toString(Charsets.ISO_8859_1)
            val idx = frame[8].toInt() and 0xFF
            val total = (frame[9].toInt() and 0xFF) shr 1
            if (total <= 1 || total > MAX_FRAMES || idx >= total) return null
            val now = System.currentTimeMillis()
            slots.entries.removeIf { now - it.value.firstSeen > timeoutMs }
            val slot = slots.getOrPut(key) { Slot(HashMap(), total, now) }
            if (slot.total != total) return null
            slot.frames[idx] = frame.copyOfRange(HEADER, frame.size)
            if (slot.frames.size == total) {
                slots.remove(key)
                val out = ByteArray(slot.frames.values.sumOf { it.size })
                var off = 0
                for (i in 0 until total) {
                    val f = slot.frames[i] ?: return null
                    System.arraycopy(f, 0, out, off, f.size)
                    off += f.size
                }
                return out
            }
            return null
        }
    }
}
