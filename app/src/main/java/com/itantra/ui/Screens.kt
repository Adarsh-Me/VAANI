package com.itantra.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itantra.models.InputMode
import com.itantra.models.Languages
import com.itantra.models.TransportType
import com.itantra.utils.ModelCatalog
import kotlin.math.roundToInt

/** Prototype .iconbtn â€” 44dp bordered square, surface bg, soft shadow. */
fun Modifier.itIconButton(): Modifier = this
    .shadow(2.dp, RoundedCornerShape(12.dp), ambientColor = IT.Shadow, spotColor = IT.Shadow)
    .clip(RoundedCornerShape(12.dp))
    .background(IT.Surface)
    .border(1.dp, IT.Border, RoundedCornerShape(12.dp))

/** Prototype .topbar â€” pair eyebrow, wordmark, status pill, settings iconbtn. */
@Composable
fun AppTopBar(
    pairLabel: String,
    linkLabel: String,
    linkOn: Boolean,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IT.Bg)
            .border(1.dp, IT.Border, RoundedCornerShape(0.dp))
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(pairLabel)
            Text("iTantra", style = MaterialTheme.typography.titleLarge)
        }
        Pill(linkLabel, dotOn = linkOn)
        Box(
            modifier = Modifier
                .itIconButton()
                .size(44.dp)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Settings, "Settings",
                modifier = Modifier.size(20.dp), tint = IT.Ink
            )
        }
    }
}

/**
 * Persistent shell shared by every prototype screen: topbar (pair eyebrow,
 * wordmark, status pill, settings) + scrollable content + bottom nav.
 */
@Composable
fun AppShell(
    current: String,
    vm: MainViewModel,
    onNavigate: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val settings by vm.settings.collectAsState()
    val conn by vm.connection.collectAsState()
    val ble = settings.transportType == TransportType.BLE
    val pairLabel = settings.sourceLanguage.uppercase() + " â†’ " +
        settings.targetLanguage.uppercase() + " Â· " + if (ble) "BLE" else "Loopback"
    // Loopback rehearses as CONNECTED â€” only call it BLE live on the real transport.
    val linkLabel = when {
        !ble -> "Ready"
        conn == com.itantra.models.ConnectionState.CONNECTED -> "Connected"
        conn == com.itantra.models.ConnectionState.DISCONNECTED -> "Ready"
        else -> "Linkingâ€¦"
    }
    Column(Modifier.fillMaxSize().background(IT.Bg)) {
        AppTopBar(
            pairLabel = pairLabel,
            linkLabel = linkLabel,
            linkOn = conn == com.itantra.models.ConnectionState.CONNECTED,
            onSettings = { onNavigate("settings") }
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
        BottomNav(
            current = current,
            onTalk = { onNavigate("main") },
            onHistory = { onNavigate("history") },
            onLink = { onNavigate("connection") },
            onModels = { onNavigate("models") },
            onSetup = { onNavigate("settings") }
        )
    }
}

/** Short native-only chip label, e.g. "à¤¹à¤¿à¤‚à¤¦à¥€" from "à¤¹à¤¿à¤‚à¤¦à¥€ (Hindi)". */
private fun nativeName(code: String): String =
    Languages.displayName(code).substringBefore(" (").trim()

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onNavigate: (String) -> Unit
) {
    val settings by vm.settings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Languages", "Audio", "Link", "AI models", "Emergency", "About")
    AppShell(current = "settings", vm = vm, onNavigate = onNavigate) {
        ScreenTitle("Settings", "Tune iTantra")
        // Prototype .od-rail â€” horizontal chip tablist.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            tabs.forEachIndexed { i, t ->
                ITChip(t, selected = tab == i) {
                    if (t == "AI models") onNavigate("models") else tab = i
                }
            }
        }
        when (tabs[tab]) {
            "Languages" -> {
                ITCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("I speak", style = MaterialTheme.typography.labelLarge)
                        Hint(Languages.displayName(settings.sourceLanguage))
                        ChipLangRow(Languages.DEMO.toList(), settings.sourceLanguage) {
                            vm.updateSettings(settings.copy(sourceLanguage = it))
                        }
                        ITDivider()
                        Text("They hear", style = MaterialTheme.typography.labelLarge)
                        Hint(Languages.displayName(settings.targetLanguage))
                        ChipLangRow(Languages.DEMO.toList(), settings.targetLanguage) {
                            vm.updateSettings(settings.copy(targetLanguage = it))
                        }
                        ITDivider()
                        ToggleRow(
                            "Auto-detect",
                            "Script detection on input",
                            settings.autoDetectLanguage
                        ) { vm.updateSettings(settings.copy(autoDetectLanguage = it)) }
                    }
                }
                // Prototype "Language packs" card. Punjabi ships the same neural
                // voice model as Tamil; Telugu/Marathi stay on the roadmap.
                ITCard {
                    Eyebrow("Language packs")
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Punjabi (à¨ªà©°à¨œà¨¾à¨¬à©€)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            ITMini("Download") { vm.downloadModel("tts-pa") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Telugu (à°¤à±†à°²à±à°—à±)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TagChip("ROADMAP")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Marathi (à¤®à¤°à¤¾à¤ à¥€)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TagChip("ROADMAP")
                        }
                    }
                }
            }
            "Audio" -> {
                val ctx = LocalContext.current
                val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
                var speakerVol by remember {
                    mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
                ITCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToggleRow(
                            label = "Input mode",
                            hint = if (settings.inputMode == InputMode.AUTO) "Hands-free (auto VAD)"
                            else "Push-to-talk",
                            checked = settings.inputMode == InputMode.AUTO
                        ) { vm.setAutoMode(it) }
                        Column {
                            Text(
                                "Mic sensitivity Â· " + "%.2f".format(settings.vadThreshold),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Slider(
                                value = settings.vadThreshold,
                                onValueChange = { vm.updateSettings(settings.copy(vadThreshold = it)) },
                                valueRange = 0.2f..0.8f,
                                colors = SliderDefaults.colors(
                                    thumbColor = IT.Ink, activeTrackColor = IT.Ink,
                                    inactiveTrackColor = IT.Surface3
                                )
                            )
                        }
                        Column {
                            Text(
                                "Speaker volume",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Hint("Normal path (emergency always max)")
                            Slider(
                                value = speakerVol.toFloat(),
                                onValueChange = {
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                                    speakerVol = it.toInt()
                                },
                                valueRange = 0f..am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = IT.Ink, activeTrackColor = IT.Ink,
                                    inactiveTrackColor = IT.Surface3
                                )
                            )
                        }
                    }
                }
            }
            "Link" -> {
                ITCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Transport", style = MaterialTheme.typography.titleSmall)
                        Hint("Loopback rehearses; BLE links two phones.")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                ITMini(
                                    "Loopback",
                                    Modifier.fillMaxWidth(),
                                    onClick = { vm.setTransport(TransportType.LOOPBACK) }
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                ITMini(
                                    "Bluetooth LE",
                                    Modifier.fillMaxWidth(),
                                    onClick = { vm.setTransport(TransportType.BLE) }
                                )
                            }
                        }
                        KvText("MTU 517 Â· TX notify on Â· mesh relay roadmap (+150 ms/hop).")
                        ITSecondaryButton("Open connection screen", Modifier.fillMaxWidth()) {
                            onNavigate("connection")
                        }
                    }
                }
            }
            "Emergency" -> {
                ITCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToggleRow(
                            "Maximum volume",
                            "STREAM_ALARM to max, non-interruptible",
                            settings.emergencyMaxVolume
                        ) { vm.updateSettings(settings.copy(emergencyMaxVolume = it)) }
                        ToggleRow(
                            "Vibration",
                            "500 Â· 200 Â· 500 pattern",
                            settings.emergencyVibrationEnabled
                        ) { vm.updateSettings(settings.copy(emergencyVibrationEnabled = it)) }
                        ToggleRow(
                            "Auto-repeat",
                            "Every 5 s until acknowledged",
                            settings.emergencyAutoRepeatSec > 0
                        ) {
                            vm.updateSettings(
                                settings.copy(emergencyAutoRepeatSec = if (it) 5 else 0)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        ITDangerButton("Test alert", Modifier.fillMaxWidth()) {
                            vm.emergency.trigger(
                                FloatArray(0), settings.sampleRate, settings,
                                Languages.displayName(settings.sourceLanguage) + " test alert"
                            )
                        }
                    }
                }
            }
            "About" -> {
                ITCard {
                    Text("iTantra 1.0-demo Â· SIH26173", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "Cascade ASR â†’ MT â†’ TTS + 5-byte prosody side-channel. " +
                            "Demo hi/ta/bn. Budgets: <2 s p50, ~412 MB with models."
                    )
                    KvText("ISRO misc Â· software Â· offline walkie-talkie")
                }
            }
        }
    }
}

/** Compact language selector (mock .chip cluster, wraps to a second line). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipLangRow(codes: List<String>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        codes.forEach { code ->
            ITChip(nativeName(code), selected = code == selected) { onSelect(code) }
        }
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val messages by vm.messages.collectAsState()
    var onlyEmergency by remember { mutableStateOf(false) }
    AppShell(current = "history", vm = vm, onNavigate = onNavigate) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "History",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            ITChip("Emergency", selected = onlyEmergency) { onlyEmergency = !onlyEmergency }
        }
        Hint("Stored on-device (Room). Tap replay to hear again.")
        val list = if (onlyEmergency) messages.filter { it.isEmergency } else messages
        if (list.isEmpty()) {
            Hint("No messages yet. Hold TALK or send text â€” every loop lands here.")
        }
        // Prototype history is a plain stack of the same .msg bubbles.
        list.reversed().forEach { msg ->
            MessageBubble(msg, onReplay = { vm.replay(msg) })
        }
    }
}

@Composable
fun ConnectionScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val conn by vm.connection.collectAsState()
    val settings by vm.settings.collectAsState()
    // connection-found variant only for a live BLE peer; loopback shows the
    // default rehearsal card (prototype connection.html vs connection-found.html).
    val connected = settings.transportType == TransportType.BLE &&
        conn == com.itantra.models.ConnectionState.CONNECTED
    val scanning = settings.transportType == TransportType.BLE && !connected
    AppShell(current = "connection", vm = vm, onNavigate = onNavigate) {
        ScreenTitle(
            if (connected) "Connectivity Â· scanning complete" else "Connectivity",
            "Two-phone link"
        )
        ITCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(connected)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (connected) "Connected: iTantra peer" else "Loopback",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Hint(
                        if (connected) "Signal strong (âˆ’58 dBm) Â· MTU 517 Â· TX notify on Â· ready to communicate"
                        else "Single-phone rehearsal. Packets stay on this device."
                    )
                }
            }
        }
        ITCard {
            Text("How BLE works here", style = MaterialTheme.typography.titleSmall)
            Hint(
                "Both phones â†’ Bluetooth LE. 10â€“30 m apart. Only ~200 bytes of " +
                    "text + prosody cross the air â€” never audio."
            )
            Spacer(Modifier.height(10.dp))
            ITPrimaryButton("Advertise / scan (BLE)", Modifier.fillMaxWidth()) {
                vm.setTransport(TransportType.BLE)
            }
            Spacer(Modifier.height(8.dp))
            ITGhostButton("Back to loopback demo", Modifier.fillMaxWidth()) {
                vm.setTransport(TransportType.LOOPBACK)
            }
        }
        // Prototype hidden scanning card â€” shown while a BLE link is being established.
        if (scanning) {
            ITCard {
                Eyebrow("Scanningâ€¦")
                Spacer(Modifier.height(6.dp))
                ITProgress(0.1f)
                Spacer(Modifier.height(6.dp))
                Hint("Looking for iTantra devicesâ€¦")
            }
        }
        if (connected) {
            ITCard {
                Eyebrow("Scan results")
                Spacer(Modifier.height(8.dp))
                LangCard(
                    title = "iTantra peer",
                    subtitle = "Strong Â· âˆ’58 dBm Â· iTantra peer",
                    selected = true,
                    onClick = {}
                )
                Spacer(Modifier.height(8.dp))
                ITGhostButton("Rescan", Modifier.fillMaxWidth()) {
                    vm.setTransport(TransportType.BLE)
                }
            }
            ITCard {
                Eyebrow("Reconnect policy")
                Hint("Auto-retry 3Ã— on link loss, then queue outgoing packets until reconnected.")
            }
        }
        ITCard {
            Eyebrow("Fallback")
            Hint(
                "No peer? Stay on loopback â€” the full pipeline still rehearses. " +
                    "Connection loss auto-retries 3Ã—, then queues."
            )
        }
    }
}

@Composable
fun ModelsScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val status by vm.modelStatus.collectAsState()
    val downloads by vm.modelDownloader.states.collectAsState()
    AppShell(current = "models", vm = vm, onNavigate = onNavigate) {
        ScreenTitle("On-device AI · offline after download", "Models")
        val readyCount = status.count { it.value }
        ITCard(padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(true)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voice pipeline status", style = MaterialTheme.typography.titleSmall)
                    Hint("Text + system voices live · neural voice partial")
                }
                Spacer(Modifier.size(8.dp))
                TagChip("$readyCount/${status.size.coerceAtLeast(1)} READY")
            }
        }
        val ctx = LocalContext.current
        val catalog = remember(ctx) { com.itantra.utils.ModelCatalog.load(ctx) }

        // Compact: 3 pipeline cards (STT / MT / TTS), each collapsible to hide
        // its per-language model files. Tap to expand.
        val liveOf: (com.itantra.utils.ModelSpec) -> Boolean = { spec ->
            status[if (spec.type == "tts" || spec.type == "mms") spec.id else spec.type] == true
        }
        val sttSpecs = catalog.filter { it.type == "asr" }
        val mtSpecs = catalog.filter { it.type == "mt" }
        val ttsSpecs = catalog.filter { it.type == "tts" || it.type == "mms" }
        var expanded by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

        val cards = listOf(
            Triple("stt", "Speech Recognition · SraVaani TDT", "✎") to sttSpecs,
            Triple("mt", "Translation · IndicTrans2", "⇄") to mtSpecs,
            Triple("tts", "Voices · Hear2Read + system", "♪") to ttsSpecs
        )
        for ((head, specs) in cards) {
            val (id, title, glyph) = head
            val anyLive = specs.any { liveOf(it) }
            val isExpanded = expanded == id
            ITCard(padding = 16) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (isExpanded) null else id }
                ) {
                    Box(
                        Modifier
                            .itIconButton()
                            .size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(glyph, style = ITText.kv.copy(color = IT.Ink))
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.labelLarge)
                        KvText(if (anyLive) "✓ ready" else "download required")
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(if (isExpanded) "▾" else "▸", style = ITText.kv.copy(color = IT.Ink))
                }
                if (isExpanded) {
                    Spacer(Modifier.height(8.dp))
                    for (spec in specs) {
                        val live = liveOf(spec)
                        val dl = downloads[spec.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(modelLabel(spec), style = MaterialTheme.typography.labelMedium)
                                KvText(
                                    spec.type.uppercase() + " · " +
                                        (if (spec.sizeBytes > 0) formatBytes(spec.sizeBytes) else unconfiguredLabel(spec.type))
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            when {
                                live -> TagChip("✓ ACTIVE")
                                dl?.status?.name == "RUNNING" ->
                                    KvText("${((dl?.fraction ?: 0f) * 100).toInt()}%")
                                dl?.status?.name == "FAILED" -> ITMini("Retry") { vm.downloadModel(spec.id) }
                                spec.url.isBlank() -> TagChip(unconfiguredLabel(spec.type))
                                else -> ITMini("Get") { vm.downloadModel(spec.id) }
                            }
                        }
                        if (dl?.status?.name == "RUNNING") {
                            ITProgress(dl?.fraction ?: 0f)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
        // Storage card: verified model bytes vs the ~512 MB budget.
        val usedBytes = catalog.sumOf { spec ->
            val key = if (spec.type == "tts" || spec.type == "mms") spec.id else spec.type
            if (status[key] == true) spec.sizeBytes else 0L
        }
        ITCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Storage", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                KvText("${formatBytes(usedBytes)} / 512 MB")
            }
            Spacer(Modifier.height(8.dp))
            ITProgress((usedBytes / (512.0 * 1000 * 1000)).toFloat().coerceIn(0f, 1f))
            Spacer(Modifier.height(6.dp))
            Hint("Voices are per-language; ASR/MT are shared across all pairs.")
        }
        ITSecondaryButton("Check for updates", Modifier.fillMaxWidth()) {
            catalog.forEach { spec ->
                val key = if (spec.type == "tts" || spec.type == "mms") spec.id else spec.type
                if (status[key] != true && spec.url.isNotBlank()) vm.downloadModel(spec.id)
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> {
        val mb = (b / 100_000.0).roundToInt() / 10.0
        if (mb % 1.0 == 0.0) "${mb.toInt()} MB" else "$mb MB"
    }
    b >= 1_000 -> "${b / 1_000} KB"
    else -> "$b B"
}

/** Mock-style display names for the catalog rows. */
private fun modelLabel(spec: com.itantra.utils.ModelSpec): String = when (spec.id) {
    "vad" -> "Silero VAD 1.8 MB"
    "asr" -> "IndicConformer-600M INT8"
    "mt" -> "IndicTrans2-320M INT8"
    "mt-dec" -> "IndicTrans2 MT Â· decoder"
    "emotion" -> "Emotion2Vec"
    "tts-hi" -> "Hindi voice Â· piper medium"
    "tts-ta" -> "Tamil voice Â· system fallback"
    "tts-pa" -> "Punjabi voice Â· system fallback"
    "mt-tok" -> "IndicTrans2 tokenizer Â· source"
    "mt-tok-tgt" -> "IndicTrans2 tokenizer Â· target"
    else -> spec.id
}

/** Honest label for models without a pinned download (see README for paths). */
private fun unconfiguredLabel(type: String): String = when (type) {
    "asr" -> "MANUAL INSTALL"
    "mt" -> "MANUAL INSTALL"
    "emotion" -> "HEURISTIC ACTIVE"
    "tts" -> "SYSTEM VOICE"
    else -> "MANUAL INSTALL"
}
