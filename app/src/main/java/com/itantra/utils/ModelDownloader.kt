package com.itantra.utils

import android.content.Context
import com.itantra.data.ModelRegistryDao
import com.itantra.data.ModelRegistryEntity
import com.itantra.data.ModelValidator
import com.itantra.models.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelSpec(
    val id: String,
    val type: String,
    val url: String, // empty = not configured (heuristic/system fallback stays active)
    val relPath: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val languages: String? = null,
    /**
     * Non-null: relPath is a .tar.bz2 voice bundle. After download it is
     * extracted into this filesDir-relative directory and the archive is
     * deleted. Present-check then inspects the extracted contents.
     */
    val extractTo: String? = null,
    /**
     * Non-blank: ONNX external-data companion (e.g. encoder_model.onnx.data).
     * dataRelPath MUST match the `location` string inside the .onnx stub
     * byte-for-byte (ONNX resolves it relative to the model's directory —
     * a renamed copy is silently ignored and the session loads weightless).
     */
    val dataUrl: String = "",
    val dataRelPath: String = "",
    val dataSizeBytes: Long = 0
)

data class DownloadState(
    val specId: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val status: Status
) {
    enum class Status { IDLE, RUNNING, DONE, FAILED, SKIPPED }
    val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
}

/** Model download catalog.
 *  Source of truth is assets/model_catalog.json (swap models by editing that
 *  file — no code change). If the asset is missing or malformed, falls back
 *  to the built-in DEFAULTS below. */
object ModelCatalog {
    /** Asset-driven primary list; null until first successful load. */
    private var fromAsset: List<ModelSpec>? = null

    fun load(context: android.content.Context): List<ModelSpec> {
        fromAsset?.let { return it }
        val parsed = runCatching {
            val raw = context.assets.open("model_catalog.json").bufferedReader().readText()
            parseCatalogJson(raw)
        }.getOrNull()
        fromAsset = parsed ?: DEFAULTS
        return fromAsset!!
    }

    val ALL: List<ModelSpec>
        get() = fromAsset ?: DEFAULTS

    private fun parseCatalogJson(raw: String): List<ModelSpec> {
        val root = org.json.JSONObject(raw)
        val arr = root.getJSONArray("models")
        val out = ArrayList<ModelSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ModelSpec(
                    id = o.getString("id"),
                    type = o.getString("type"),
                    url = o.optString("url", ""),
                    relPath = o.getString("relPath"),
                    sha256 = o.optString("sha256", ""),
                    sizeBytes = o.optLong("sizeBytes", 0),
                    languages = if (o.has("languages"))
                        o.getJSONArray("languages").join(",").replace("\"", "").let { "[$it]" }
                    else null,
                    extractTo = o.optString("extractTo", "").ifBlank { null },
                    dataUrl = o.optString("dataUrl", ""),
                    dataRelPath = o.optString("dataRelPath", ""),
                    dataSizeBytes = o.optLong("dataSizeBytes", 0)
                )
            )
        }
        require(out.isNotEmpty()) { "empty catalog" }
        return out
    }

    private val DEFAULTS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "vad", type = "vad",
            url = "https://raw.githubusercontent.com/snakers4/silero-vad/v4.0/files/silero_vad.onnx",
            relPath = StorageLayout.VAD_MODEL, sizeBytes = 1_800_000
        ),
        // No verified single-file INT8 weight exists at the old livinNector path
        // (repo hosts export scripts only). Verified alternates in README.
        // Drop an exported model at models/indic_conformer_int8.onnx to enable.
        ModelSpec(id = "asr", type = "asr", url = "", relPath = StorageLayout.ASR_MODEL),
        ModelSpec(
            id = "mt", type = "mt",
            url = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/encoder_model.onnx",
            relPath = StorageLayout.MT_ENCODER, sizeBytes = 831_435,
            dataUrl = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/encoder_model.onnx.data",
            dataRelPath = "models/encoder_model.onnx.data",
            dataSizeBytes = 120_068_096
        ),
        // Decoder + tokenizer ship from the same repo; added once the encoder lands.
        ModelSpec(
            id = "mt-dec", type = "mt",
            url = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/decoder_model.onnx",
            relPath = StorageLayout.MT_DECODER_LEGACY, sizeBytes = 2_037_642,
            dataUrl = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/decoder_shared.onnx.data",
            dataRelPath = "models/decoder_shared.onnx.data",
            dataSizeBytes = 203_048_944
        ),
        // KV-cache decoder: REQUIRED for correct generation. The no-past
        // decoder_model.onnx re-feed path produces truncated output (verified
        // on host vs official translate.py); the with-past path produces full
        // sentences and is also ~10x faster.
        ModelSpec(
            id = "mt-dec-past", type = "mt",
            url = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/decoder_with_past_model.onnx",
            relPath = StorageLayout.MT_DECODER, sizeBytes = 1_850_619
        ),
        ModelSpec(id = "emotion", type = "emotion", url = "", relPath = StorageLayout.EMOTION_MODEL),
        ModelSpec(
            id = "tts-hi", type = "tts",
            // sherpa-onnx Piper/VITS Hindi voice (pratham, medium). Ships as a
            // tar.bz2 bundle: <voice>.onnx + tokens.txt + espeak-ng-data/.
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2",
            relPath = StorageLayout.ttsTarball("hi"),
            sizeBytes = 67_238_438,
            extractTo = StorageLayout.ttsDir("hi"),
            languages = "[\"hi\"]"
        ),
        ModelSpec(
            id = "mt-tok", type = "mt",
            url = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/tokenizer_src.json",
            relPath = "models/mt/tokenizer_src.json", sizeBytes = 23_876_630
        ),
        ModelSpec(
            id = "mt-tok-tgt", type = "mt",
            url = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/main/tokenizer_tgt.json",
            relPath = "models/mt/tokenizer_tgt.json", sizeBytes = 23_870_000
        ),
        ModelSpec(id = "tts-ta", type = "tts", url = "", relPath = StorageLayout.ttsVoice("ta"), languages = "[\"ta\"]"),
        ModelSpec(id = "tts-pa", type = "tts", url = "", relPath = StorageLayout.ttsVoice("pa"), languages = "[\"pa\"]"),
        // MMS-TTS neural voices (Meta MMS via willwade ONNX, CC-BY-NC-4.0):
        // model.onnx + tokens.txt per language, loaded by MmsTtsEngine.
        ModelSpec(
            id = "mms-ta", type = "mms",
            url = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tam/model.onnx",
            relPath = "models/mms/ta/model.onnx", sizeBytes = 114_032_388,
            languages = "[\"ta\"]"
        ),
        ModelSpec(
            id = "mms-ta-tok", type = "mms",
            url = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tam/tokens.txt",
            relPath = "models/mms/ta/tokens.txt", sizeBytes = 375,
            languages = "[\"ta\"]"
        ),
        ModelSpec(
            id = "mms-pa", type = "mms",
            url = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/pan/model.onnx",
            relPath = "models/mms/pa/model.onnx", sizeBytes = 114_033_156,
            languages = "[\"pa\"]"
        ),
        ModelSpec(
            id = "mms-pa-tok", type = "mms",
            url = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/pan/tokens.txt",
            relPath = "models/mms/pa/tokens.txt", sizeBytes = 400,
            languages = "[\"pa\"]"
        )
    )
}

/**
 * Background model downloader: progress reporting, 3x retry, SHA-256 check,
 * registry bookkeeping. Never bundles models in the APK.
 */
class ModelDownloader(
    private val context: Context,
    private val registry: ModelRegistryDao? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    fun isPresent(spec: ModelSpec): Boolean {
        val archive = File(context.filesDir, spec.relPath)
        if (spec.extractTo == null && spec.dataUrl.isBlank() && spec.type != "mms") {
            return ModelValidator.verify(archive, spec.sha256.ifBlank { null })
        }
        if (spec.type == "mms") {
            // MMS voice dir needs model.onnx + tokens.txt; each spec covers one
            // file, presence of the pair is checked via the sibling spec.
            if (spec.id.endsWith("-tok")) return ModelValidator.verify(archive, null)
            val dir = File(context.filesDir, "models/mms/" + (spec.languages
                ?.removeSurrounding("[", "]")?.trim('"') ?: ""))
            return File(dir, "model.onnx").exists() &&
                File(dir, "model.onnx").length() > 10_000_000 &&
                File(dir, "tokens.txt").exists()
        }
        if (spec.extractTo != null) {
            // Extracted voice bundle: model + tokens.txt + espeak-ng-data/ must all exist.
            val dir = File(context.filesDir, spec.extractTo)
            return dir.listFiles()?.any { it.isFile && it.name.endsWith(".onnx") } == true &&
                File(dir, "tokens.txt").exists() &&
                File(dir, "espeak-ng-data").isDirectory
        }
        // ONNX external-data pair: the .onnx stub AND its .onnx.data companion
        // (at the exact location the stub references) must both exist, at roughly
        // the expected sizes. A stub without weights is the classic "present but
        // weightless" failure — it loads an empty graph and fails on ai.onnx.ml.
        val data = File(context.filesDir, spec.dataRelPath)
        return ModelValidator.verify(archive, spec.sha256.ifBlank { null }) &&
            data.exists() && data.length() > spec.dataSizeBytes / 2
    }

    suspend fun download(spec: ModelSpec, attempts: Int = 3): Boolean =
        withContext(Dispatchers.IO) {
            android.util.Log.i("iTantra", "download start: ${spec.id}")
            if (spec.url.isBlank()) {
                setState(spec.id, 0, spec.sizeBytes, DownloadState.Status.SKIPPED)
                return@withContext false
            }
            var lastErr: Exception? = null
            repeat(attempts) {
                try {
                    fetch(spec)
                    // ONNX external-data companion (e.g. encoder_model.onnx.data):
                    // fetched to the exact relative path the stub references.
                    if (spec.dataUrl.isNotBlank()) {
                        fetchUrl(spec.id, spec.dataUrl, spec.dataRelPath, spec.dataSizeBytes)
                    }
                    val file = File(context.filesDir, spec.relPath)
                    if (ModelValidator.verify(file, spec.sha256.ifBlank { null })) {
                        val installedPath = if (spec.extractTo != null) {
                            extractBundle(spec, file)
                        } else {
                            spec.relPath
                        }
                        registry?.upsert(
                            ModelRegistryEntity(
                                modelId = spec.id, modelType = spec.type,
                                filePath = installedPath, fileSizeBytes = file.length(),
                                sha256 = spec.sha256, languages = spec.languages,
                                lastVerified = System.currentTimeMillis()
                            )
                        )
                        setState(spec.id, file.length(), file.length(), DownloadState.Status.DONE)
                        return@withContext true
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            android.util.Log.w("iTantra", "download failed: ${spec.id}: $lastErr")
            false
        }

    /** Extracts a .tar.bz2 bundle into filesDir/spec.extractTo, then deletes the archive. */
    private fun extractBundle(spec: ModelSpec, archive: File): String {
        val dest = File(context.filesDir, spec.extractTo!!)
        dest.deleteRecursively()
        dest.mkdirs()
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (!tar.canReadEntryData(entry)) continue
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(dest.canonicalPath + File.separator)) {
                    throw SecurityException("tar entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        // Piper/sherpa tarballs wrap everything in a top-level folder; flatten
        // it so model/tokens.txt/espeak-ng-data sit directly under dest.
        val children = dest.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles().orEmpty().forEach { it.renameTo(File(dest, it.name)) }
            inner.delete()
        }
        android.util.Log.i(
            "iTantra", "extracted ${spec.id} -> ${dest.absolutePath} " +
                "(${dest.walkTopDown().filter { it.isFile }.count()} files)"
        )
        return spec.extractTo
    }

    private fun fetch(spec: ModelSpec) {
        fetchUrl(spec.id, spec.url, spec.relPath, spec.sizeBytes)
    }

    /** Downloads one URL to a filesDir-relative path with progress + resume. */
    private fun fetchUrl(specId: String, url: String, relPath: String, expectedBytes: Long) {
        val dest = File(context.filesDir, relPath)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        // Resume: keep already-downloaded bytes when the old partial is smaller.
        var have = if (tmp.exists()) tmp.length() else 0L
        if (dest.exists() && (expectedBytes <= 0 || dest.length() >= expectedBytes)) return
        val req = Request.Builder()
            .url(url)
            .apply { if (have > 0) addHeader("Range", "bytes=$have-") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 416) {
                have = 0; tmp.delete()
                return fetchUrl(specId, url, relPath, expectedBytes)
            }
            // Server ignored Range (200 instead of 206): restart from zero.
            if (resp.code == 200 && have > 0) {
                have = 0; tmp.delete()
            }
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("empty body")
            val total = if (body.contentLength() > 0) body.contentLength() + have
            else expectedBytes
            setState(specId, have, total, DownloadState.Status.RUNNING)
            java.io.FileOutputStream(tmp, have > 0).use { fos ->
                fos.channel.position(have)
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 20)
                    var done = have
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        done += n
                        setState(specId, done, total, DownloadState.Status.RUNNING)
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw RuntimeException("rename failed: $relPath")
    }

    private fun setState(id: String, done: Long, total: Long, status: DownloadState.Status) {
        val cur = _states.value.toMutableMap()
        cur[id] = DownloadState(id, done, total, status)
        _states.value = cur
    }
}
