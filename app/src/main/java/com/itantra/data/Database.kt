package com.itantra.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.itantra.models.ProsodyData

/** Room converters: enums <-> ints, prosody <-> 5-byte blob. */
class Converters {
    @TypeConverter
    fun prosodyToBytes(p: ProsodyData?): ByteArray? = p?.toBytes()

    @TypeConverter
    fun bytesToProsody(b: ByteArray?): ProsodyData =
        if (b != null && b.size == 5) ProsodyData.fromBytes(b) else ProsodyData.NEUTRAL
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["utteranceId"], unique = true),
        Index(value = ["timestamp"]),
        Index(value = ["direction"]),
        Index(value = ["isEmergency"]),
        Index(value = ["languagePairId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = LanguagePairEntity::class,
            parentColumns = ["pairId"],
            childColumns = ["languagePairId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val utteranceId: String,
    val originalText: String,
    val translatedText: String?,
    val translationFailed: Boolean = false,
    val sourceLang: String,
    val targetLang: String,
    val languagePairId: String? = null,
    val emotionTag: Int = 0,
    val emotionConfidence: Float = 0f,
    val prosodyBytes: ByteArray? = null,
    val direction: Int = 0, // 0 SENT, 1 RECEIVED
    val deliveryStatus: Int = 0, // 0 PENDING, 1 SENT, 2 DELIVERED, 3 FAILED
    val timestamp: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val wasPlayed: Boolean = false,
    val isEmergency: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Entity(
    tableName = "language_pairs",
    indices = [Index(value = ["sourceLang", "targetLang"], unique = true)]
)
data class LanguagePairEntity(
    @PrimaryKey val pairId: String, // "{src}-{tgt}"
    val sourceLang: String,
    val targetLang: String,
    val usageCount: Long = 0,
    val lastUsed: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_registry")
data class ModelRegistryEntity(
    @PrimaryKey val modelId: String, // asr | mt | tts-hi | vad | emotion
    val modelType: String, // vad | asr | mt | tts | emotion
    val filePath: String, // relative to filesDir
    val version: String = "1.0.0",
    val fileSizeBytes: Long = 0,
    val sha256: String = "",
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastVerified: Long = 0,
    val isActive: Boolean = true,
    val languages: String? = null // JSON array or null
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String, // MAC or loopback id
    val deviceName: String,
    val lastConnected: Long = System.currentTimeMillis(),
    val connectionCount: Long = 0,
    val isTrusted: Boolean = false,
    val lastSignalStrength: Int? = null,
    val preferredTargetLang: String? = null
)

@Entity(tableName = "metrics")
data class MetricsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val metricType: String, // asr_latency | mt_latency | tts_latency | e2e_latency ...
    val value: Float,
    val language: String? = null,
    val deviceModel: String? = null
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isEmergency = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEmergency(limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE utteranceId = :utteranceId LIMIT 1")
    suspend fun getByUtteranceId(utteranceId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<MessageEntity>

    @Query("UPDATE messages SET wasPlayed = 1 WHERE id = :id")
    suspend fun markPlayed(id: String)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE utteranceId = :utteranceId")
    suspend fun updateDelivery(utteranceId: String, status: Int)

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)
}

@Dao
interface LanguagePairDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pair: LanguagePairEntity)

    @Query("UPDATE language_pairs SET usageCount = usageCount + 1, lastUsed = :now WHERE pairId = :pairId")
    suspend fun touch(pairId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM language_pairs ORDER BY usageCount DESC")
    suspend fun getAll(): List<LanguagePairEntity>
}

@Dao
interface ModelRegistryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ModelRegistryEntity)

    @Query("SELECT * FROM model_registry WHERE modelId = :modelId LIMIT 1")
    suspend fun get(modelId: String): ModelRegistryEntity?

    @Query("SELECT * FROM model_registry WHERE isActive = 1")
    suspend fun getActive(): List<ModelRegistryEntity>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY lastConnected DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<DeviceEntity>
}

@Dao
interface MetricsDao {
    @Insert
    suspend fun insert(metric: MetricsEntity)

    @Query("SELECT * FROM metrics WHERE metricType = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 200): List<MetricsEntity>
}

@Database(
    entities = [
        MessageEntity::class, LanguagePairEntity::class, ModelRegistryEntity::class,
        DeviceEntity::class, MetricsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ITantraDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun languagePairDao(): LanguagePairDao
    abstract fun modelRegistryDao(): ModelRegistryDao
    abstract fun deviceDao(): DeviceDao
    abstract fun metricsDao(): MetricsDao
}
