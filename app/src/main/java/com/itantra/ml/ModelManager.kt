package com.itantra.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import java.io.File

/** Resolves runtime model files under filesDir (downloaded on demand, never bundled). */
class ModelPaths(private val context: Context) {
    fun file(relativePath: String): File = File(context.filesDir, relativePath)
    fun exists(relativePath: String): Boolean {
        val f = file(relativePath)
        return f.exists() && f.length() > 0
    }
}

object ModelIds {
    const val VAD = "vad"
    const val ASR = "asr"
    const val MT = "mt"
    const val EMOTION = "emotion"
    fun tts(lang: String) = "tts-$lang"
}

/** Shared ONNX session bootstrap. Returns null when the file is absent. */
object OnnxLoader {
    fun load(
        context: Context,
        relativePath: String,
        threads: Int,
        useNnapi: Boolean = false
    ): OrtSession? {
        val file = ModelPaths(context).file(relativePath)
        if (!file.exists() || file.length() == 0L) return null
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi && Build.VERSION.SDK_INT >= 27) {
                runCatching { addNnapi() }
            }
        }
        return try {
            env.createSession(file.absolutePath, opts)
        } catch (e: Exception) {
            // Unloadable model (wrong opset, corrupt file, missing native) must
            // degrade to "not loaded" — managers fall back honestly from there.
            android.util.Log.w("OnnxLoader", "session load failed: ${file.name}: ${e.message}")
            null
        }
    }
}
