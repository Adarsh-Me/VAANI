package com.itantra.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.itantra.audio.AudioCaptureEngine
import com.itantra.audio.AudioPlayer
import com.itantra.audio.VADManager
import com.itantra.audio.VadEvent
import com.itantra.data.ITantraDatabase
import com.itantra.data.LanguagePairEntity
import com.itantra.data.toDomain
import com.itantra.data.toEntity
import com.itantra.ml.ASRManager
import com.itantra.ml.EmotionManager
import com.itantra.ml.TTSManager
import com.itantra.ml.TranslationManager
import com.itantra.models.AppSettings
import com.itantra.models.AppState
import com.itantra.models.ChatMessage
import com.itantra.models.ConnectionState
import com.itantra.models.DeliveryStatus
import com.itantra.models.InputMode
import com.itantra.models.Languages
import com.itantra.models.TransportType
import com.itantra.network.BleClient
import com.itantra.network.DualBleTransport
import com.itantra.network.LoopbackTransport
import kotlinx.coroutines.async
import com.itantra.network.Packet
import com.itantra.network.Transport
import com.itantra.pipeline.SpeechPipeline
import com.itantra.utils.AppPrefs
import com.itantra.utils.AudioFiles
import com.itantra.utils.ModelCatalog
import com.itantra.utils.ModelDownloader
import com.itantra.utils.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrator (TechSpec §2.1 application layer): owns managers, transport,
 * pipeline, persistence and all UI state.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val prefs = AppPrefs(ctx)
    private val db: ITantraDatabase = Room.databaseBuilder(
        ctx, ITantraDatabase::class.java, "itantra.db"
    ).build()

    val modelDownloader = ModelDownloader(ctx, db.modelRegistryDao())
    private val perf = PerformanceMonitor(db.metricsDao())
    private val audioFiles = AudioFiles(ctx)

    private val capture = AudioCaptureEngine(ctx)
    private val vad = VADManager(ctx)
    private val asr = ASRManager(ctx)
    private val translator = TranslationManager(ctx)
    private val tts = TTSManager(ctx)
    private val emotion = EmotionManager(ctx)
    private val player = AudioPlayer(ctx)
    val emergency = EmergencyAlertManager(ctx)
    private val pipeline = SpeechPipeline(asr, translator, tts, emotion, player, emergency, audioFiles, perf)

    private var transport: Transport = LoopbackTransport()

    // ---- UI state ----
    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<AppSettings> = _settings

    private val _appState = MutableStateFlow(AppState.NOT_INITIALIZED)
    val appState: StateFlow<AppState> = _appState

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection

    private val _modelStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val modelStatus: StateFlow<Map<String, Boolean>> = _modelStatus

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val perfReport = perf.report

    private var recordJob: Job? = null
    private var connJob: Job? = null
    private var autoDownloadJob: Job? = null
    private var pressTime = 0L
    private var initialized = false

    init {
        viewModelScope.launch {
            _messages.value = db.messageDao().getRecent(100).map { it.toDomain() }
        }
        // Offline-first install: the moment connectivity exists (now or later),
        // every model with a pinned URL downloads itself — no Get taps needed.
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    autoDownloadMissingModels()
                }
            }
        )
    }

    // ---------- lifecycle ----------

    /** Heavy init on Dispatchers.IO: models + transport. Safe to call twice. */
    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch(Dispatchers.IO) {
            val s = _settings.value
            // IO-bound loads run concurrently; total ~= slowest, not sum.
            val vadD = async { vad.initialize() }
            val asrD = async { asr.initialize(s.sourceLanguage) }
            val mtD = async { translator.initialize() }
            val emoD = async { emotion.initialize() }
            val ttsD = async { tts.initialize(s.sourceLanguage) }
            val (vadOk, asrOk, mtOk, emoOk, ttsOk) = listOf(vadD, asrD, mtD, emoD, ttsD)
                .map { it.await() }
            withContext(Dispatchers.Main) {
                // TTS is per-language: ready only when that voice bundle is
                // installed (system fallback always exists, so it never
                // marks tts-* ready on its own).
                val catalog = com.itantra.utils.ModelCatalog.load(ctx)
                _modelStatus.value = mapOf(
                    "vad" to vadOk, "asr" to asrOk, "mt" to mtOk,
                    "emotion" to emoOk,
                    "tts-hi" to modelDownloader.isPresent(catalog.first { it.id == "tts-hi" }),
                    "tts-ta" to modelDownloader.isPresent(catalog.first { it.id == "tts-ta" }),
                    "tts-pa" to modelDownloader.isPresent(catalog.first { it.id == "tts-pa" })
                )
            }
            startTransport(s.transportType)
            withContext(Dispatchers.Main) {
                _appState.value = AppState.READY
            }
            autoDownloadMissingModels()
        }
    }

    fun setTransport(type: TransportType) {
        updateSettings(_settings.value.copy(transportType = type))
        viewModelScope.launch(Dispatchers.IO) { startTransport(type) }
    }

    private fun startTransport(type: TransportType) {
        transport.stop()
        connJob?.cancel()
        transport = when (type) {
            TransportType.LOOPBACK -> LoopbackTransport()
            // Both roles at once: each phone advertises AND scans, so the
            // first GATT connection wins instead of both sides waiting.
            TransportType.BLE -> DualBleTransport(ctx)
        }
        transport.start { packet ->
            viewModelScope.launch { onPacketReceived(packet) }
        }
        connJob = viewModelScope.launch {
            transport.state.collect { _connection.value = it }
        }
    }

    /** Second BLE role for peer discovery (used by Connection screen). */
    fun scanWithClient(): BleClient = BleClient(ctx)

    // ---------- push-to-talk ----------

    fun pressPtt() {
        pressTime = System.currentTimeMillis()
        if (_appState.value != AppState.READY) return
        if (!asr.isModelLoaded) {
            // Loud failure: silent empty-transcript fallback wasted user taps.
            setError("Voice input unavailable — ASR model not installed. Get it from the Models screen or type text.")
            return
        }
        _appState.value = AppState.RECORDING
        _isRecording.value = true
        _liveTranscript.value = ""
        recordJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                vad.segmentUtterance(capture.startCapture()).collect { event ->
                    when (event) {
                        is VadEvent.SpeechStarted -> setState(AppState.RECORDING)
                        is VadEvent.UtteranceReady -> onUtterance(event.audio)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal PTT release cancels this job — rethrow, never surface.
                throw e
            } catch (e: Exception) {
                setError("Capture failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _isRecording.value = false
                    _appState.value = AppState.READY
                }
            }
        }
        // Safety: force-stop at max duration.
        viewModelScope.launch {
            delay((_settings.value.maxRecordingDurationSec * 1000L))
            if (_isRecording.value) releasePtt()
        }
    }

    fun releasePtt() {
        val held = System.currentTimeMillis() - pressTime
        viewModelScope.launch(Dispatchers.IO) {
            // stopCapture() ends the chunk flow, which makes segmentUtterance
            // flush trailing speech as UtteranceReady — do NOT cancel the
            // collector here or a mid-speech release loses the whole utterance.
            capture.stopCapture()
            if (held < _settings.value.holdThresholdMs) {
                // Tap, not hold: discard before the flush can fire.
                recordJob?.cancel()
                recordJob = null
            }
            withContext(Dispatchers.Main) {
                _isRecording.value = false
                if (_appState.value == AppState.RECORDING) _appState.value = AppState.READY
            }
        }
    }

    /** Auto (hands-free) mode toggle. */
    fun setAutoMode(enabled: Boolean) {
        updateSettings(
            _settings.value.copy(
                inputMode = if (enabled) InputMode.AUTO else InputMode.PUSH_TO_TALK
            )
        )
        if (enabled) pressPtt() else releasePtt()
    }

    private suspend fun onUtterance(audio: FloatArray) {
        val s = _settings.value
        withContext(Dispatchers.Main) {
            _appState.value = AppState.PROCESSING
            _liveTranscript.value = "…"
        }
        val res = pipeline.processUtterance(audio, s.sourceLanguage, s.targetLanguage)
        if (res == null) {
            withContext(Dispatchers.Main) {
                // Speech was captured but produced no text — say why instead of
                // silently dropping it (ASR model absent vs nothing recognized).
                _liveTranscript.value = if (!asr.isModelLoaded)
                    "Voice captured — ASR model not installed. Text mode works now."
                else
                    "Didn't catch that — hold and speak again."
                _appState.value = if (_isRecording.value) AppState.RECORDING else AppState.READY
            }
            return
        }
        withContext(Dispatchers.Main) {
            _liveTranscript.value = res.transcription.text
            _appState.value = AppState.TRANSMITTING
        }
        persist(res.message)
        var sent = false
        repeat(3) {
            if (transport.send(
                    Packet(
                        id = res.message.utteranceId,
                        src = res.message.sourceLang,
                        tgt = res.message.targetLang,
                        text = res.message.originalText,
                        emo = res.message.emotionTag,
                        prosody = res.prosody
                    )
                )
            ) {
                sent = true
                return@repeat
            }
            delay(400)
        }
        val final = res.message.copy(
            deliveryStatus = if (sent) DeliveryStatus.SENT else DeliveryStatus.FAILED
        )
        persist(final)
        // Loopback also echoes locally; the remote copy arrives via onPacketReceived.
        withContext(Dispatchers.Main) {
            _liveTranscript.value = ""
            _appState.value = if (_isRecording.value) AppState.RECORDING else AppState.READY
        }
    }

    // ---------- text mode (works with zero models) ----------

    fun sendText(text: String) {
        val s = _settings.value
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _appState.value = AppState.TRANSMITTING }
            val res = pipeline.processText(text, s.sourceLanguage, s.targetLanguage)
                ?: return@launch
            persist(res.message)
            val ok = transport.send(
                Packet(
                    id = res.message.utteranceId,
                    src = res.message.sourceLang,
                    tgt = res.message.targetLang,
                    text = res.message.originalText,
                    emo = res.message.emotionTag,
                    prosody = res.prosody
                )
            )
            persist(
                res.message.copy(
                    deliveryStatus = if (ok) DeliveryStatus.SENT else DeliveryStatus.FAILED
                )
            )
            withContext(Dispatchers.Main) { _appState.value = AppState.READY }
        }
    }

    // ---------- receive ----------

    private suspend fun onPacketReceived(packet: Packet) {
        withContext(Dispatchers.Main) {
            if (packet.isEmergency) _appState.value = AppState.EMERGENCY_ALERT
            else _appState.value = AppState.RECEIVING
        }
        val s = _settings.value
        // Loopback simulates the far phone, so the receiver "hears" the target
        // language — that is what makes MT + TTS actually run in the one-phone
        // demo. On a real BLE link the peer receives src=<our language> and
        // plays it in their own source language, which this line already does.
        val playLang = if (transport is LoopbackTransport) s.targetLanguage else s.sourceLanguage
        // Neural MT is 30+ s of CPU on the 320M model — MUST stay off Main or
        // the whole app ANRs ("iTantra isn't responding") for that duration.
        val res = withContext(Dispatchers.Default) {
            pipeline.handleReceived(packet, playLang, s)
        }
        persist(res.message)
        db.languagePairDao().insert(
            LanguagePairEntity("${packet.src}-${packet.tgt}", packet.src, packet.tgt)
        )
        db.languagePairDao().touch("${packet.src}-${packet.tgt}")
        withContext(Dispatchers.Main) {
            if (!packet.isEmergency && _appState.value != AppState.RECORDING) {
                _appState.value = AppState.READY
            }
        }
    }

    fun acknowledgeEmergency() {
        emergency.stop()
        if (_appState.value == AppState.EMERGENCY_ALERT) _appState.value = AppState.READY
    }

    // ---------- settings / models ----------

    fun updateSettings(s: AppSettings) {
        _settings.value = s
        prefs.save(s)
        viewModelScope.launch(Dispatchers.IO) {
            tts.setSystemLang(s.sourceLanguage)
        }
    }

    fun swapLanguages() {
        val s = _settings.value
        updateSettings(s.copy(sourceLanguage = s.targetLanguage, targetLanguage = s.sourceLanguage))
    }

    fun downloadModel(id: String) {
        val spec = ModelCatalog.load(ctx).firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (modelDownloader.download(spec)) {
                reloadAfterDownload(spec)
            } else {
                setError("Download failed: $id. Check connection and retry.")
            }
        }
    }

    /**
     * Downloads every catalog model that has a pinned URL and isn't installed
     * yet, in impact order (VAD → Hindi voice → MT encoder). The MT encoder is
     * skipped: its decoder is manual-install and the neural binding isn't live,
     * so 80 MB would download with zero functional gain (still available via
     * Get on the Models screen). Silent on failure; re-fires from the
     * connectivity callback whenever the network returns.
     */
    fun autoDownloadMissingModels() {
        if (autoDownloadJob?.isActive == true) return
        autoDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            val order = listOf(
                "vad", "tts-hi", "mt", "mt-dec-past", "mt-dec", "mt-tok", "mt-tok-tgt",
                "mms-ta", "mms-ta-tok", "mms-pa", "mms-pa-tok"
            )
            for (id in order) {
                val spec = ModelCatalog.load(ctx).firstOrNull { it.id == id } ?: continue
                if (spec.url.isBlank()) continue
                // NOTE: do not gate on _modelStatus here — reloadAfterDownload
                // marks the parent type ("mt") ready mid-loop, which silently
                // skipped the tokenizer sub-assets (mt-tok/mt-tok-tgt).
                // isPresent(spec) is the authoritative already-downloaded check.
                if (modelDownloader.isPresent(spec)) continue
                if (modelDownloader.states.value[spec.id]?.status ==
                    com.itantra.utils.DownloadState.Status.RUNNING
                ) continue
                if (!isOnline()) return@launch
                android.util.Log.i("iTantra", "auto-download: ${spec.id}")
                if (modelDownloader.download(spec)) {
                    reloadAfterDownload(spec)
                } else {
                    android.util.Log.w("iTantra", "auto-download failed: ${spec.id}")
                }
            }
        }
    }

    /** Hot-reloads the manager that owns [spec]; status flips only on real load. */
    private suspend fun reloadAfterDownload(spec: com.itantra.utils.ModelSpec) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                when (spec.type) {
                    "vad" -> vad.initialize()
                    "asr" -> asr.initialize(_settings.value.sourceLanguage)
                    "mt" -> translator.initialize()
                    "emotion" -> emotion.initialize()
                    "tts" -> {
                        // spec.languages is a JSON list like ["hi"].
                        val lang = spec.languages
                            ?.removeSurrounding("[", "]")
                            ?.split(",")
                            ?.firstOrNull()?.trim('"', ' ')
                            ?.takeIf { it.isNotBlank() }
                            ?: _settings.value.sourceLanguage
                        tts.initialize(lang)
                    }
                    // MMS voice file: after both model+tokens land, load the
                    // voice and mark the tts-<lang> status key from real load.
                    "mms" -> {
                        val lang = spec.languages
                            ?.removeSurrounding("[", "]")
                            ?.split(",")
                            ?.firstOrNull()?.trim('"', ' ')
                            ?: return@runCatching false
                        if (spec.id.endsWith("-tok")) {
                            modelDownloader.isPresent(
                                ModelCatalog.load(ctx).first { it.id == "mms-$lang" }
                            )
                        } else {
                            tts.loadMms(lang)
                        }
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }
        withContext(Dispatchers.Main) {
            val cur = _modelStatus.value.toMutableMap()
            // MMS files roll up into the tts-<lang> key the Models screen reads.
            val statusKey = if (spec.type == "mms") {
                "tts-" + (spec.languages?.removeSurrounding("[", "]")
                    ?.split(",")?.firstOrNull()?.trim('"', ' ') ?: "?")
            } else if (spec.type == "tts") spec.id else spec.type
            cur[statusKey] = ok
            _modelStatus.value = cur
        }
    }

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun replay(message: ChatMessage) {
        val path = message.audioPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = audioFiles.load(path) ?: return@launch
            if (message.isEmergency) {
                emergency.trigger(pcm, 16000, _settings.value, message.displayText)
            } else {
                player.play(pcm)
            }
        }
    }

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun isFirstLaunch(): Boolean = prefs.firstLaunch
    fun finishOnboarding() {
        prefs.firstLaunch = false
    }

    private suspend fun persist(msg: ChatMessage) {
        withContext(Dispatchers.IO) {
            runCatching {
                // Parent row must exist before the FK-guarded message insert.
                db.languagePairDao().insert(
                    LanguagePairEntity(
                        "${msg.sourceLang}-${msg.targetLang}",
                        msg.sourceLang, msg.targetLang
                    )
                )
                db.messageDao().insert(msg.toEntity())
            }.onFailure { setError("Save failed: ${it.message}") }
        }
        withContext(Dispatchers.Main) {
            val cur = _messages.value.toMutableList()
            cur.removeAll { it.utteranceId == msg.utteranceId }
            cur.add(0, msg)
            _messages.value = cur.take(200)
        }
    }

    private fun setState(s: AppState) {
        viewModelScope.launch(Dispatchers.Main) { _appState.value = s }
    }

    private fun setError(msg: String) {
        viewModelScope.launch(Dispatchers.Main) { _error.value = msg }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        transport.stop()
        capture.stopCapture()
        vad.close()
        asr.close()
        translator.close()
        tts.shutdown()
        emotion.close()
        emergency.shutdown()
        db.close()
    }
}

/** Language display helper for pickers. */
fun languageOptions(): List<Pair<String, String>> =
    Languages.ALL.map { it.key to it.value }
