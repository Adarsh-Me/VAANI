package com.itantra.data

import com.itantra.models.ChatMessage
import com.itantra.models.DeliveryStatus
import com.itantra.models.Emotion
import com.itantra.models.MessageDirection
import com.itantra.models.ProsodyData

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    utteranceId = utteranceId,
    originalText = originalText,
    translatedText = translatedText,
    translationFailed = translationFailed,
    sourceLang = sourceLang,
    targetLang = targetLang,
    languagePairId = "$sourceLang-$targetLang",
    emotionTag = emotionTag,
    emotionConfidence = emotionConfidence,
    prosodyBytes = prosody.toBytes(),
    direction = if (direction == MessageDirection.SENT) 0 else 1,
    deliveryStatus = when (deliveryStatus) {
        DeliveryStatus.PENDING -> 0
        DeliveryStatus.SENT -> 1
        DeliveryStatus.DELIVERED -> 2
        DeliveryStatus.FAILED -> 3
    },
    timestamp = timestamp,
    audioPath = audioPath,
    audioDurationMs = audioDurationMs,
    wasPlayed = wasPlayed,
    isEmergency = isEmergency
)

fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    utteranceId = utteranceId,
    sourceLang = sourceLang,
    targetLang = targetLang,
    originalText = originalText,
    translatedText = translatedText,
    translationFailed = translationFailed,
    emotionTag = emotionTag,
    emotionLabel = Emotion.fromId(emotionTag).label,
    emotionConfidence = emotionConfidence,
    prosody = prosodyBytes?.takeIf { it.size == 5 }?.let { ProsodyData.fromBytes(it) }
        ?: ProsodyData.NEUTRAL,
    direction = if (direction == 0) MessageDirection.SENT else MessageDirection.RECEIVED,
    deliveryStatus = when (deliveryStatus) {
        1 -> DeliveryStatus.SENT
        2 -> DeliveryStatus.DELIVERED
        3 -> DeliveryStatus.FAILED
        else -> DeliveryStatus.PENDING
    },
    timestamp = timestamp,
    audioPath = audioPath,
    audioDurationMs = audioDurationMs,
    wasPlayed = wasPlayed,
    isEmergency = isEmergency
)
