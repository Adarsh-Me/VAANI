package com.itantra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.itantra.models.AppState
import com.itantra.models.ChatMessage
import com.itantra.models.ConnectionState
import com.itantra.models.DeliveryStatus
import com.itantra.models.Languages
import com.itantra.models.MessageDirection
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main walkie-talkie screen (AppFlow §4-7): status, history, PTT, text mode. */
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onNavigate: (String) -> Unit
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val messages by vm.messages.collectAsState()
    val recording by vm.isRecording.collectAsState()
    val live by vm.liveTranscript.collectAsState()
    val conn by vm.connection.collectAsState()
    val appState by vm.appState.collectAsState()
    var text by remember { mutableStateOf("") }
    var sheetText by remember { mutableStateOf("") }
    var sheetOpen by remember { mutableStateOf(false) }
    var recSec by remember { mutableIntStateOf(0) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // External grants (Settings/adb) must reflect without restart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
    }
    fun requestPerms() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        permLauncher.launch(perms.toTypedArray())
    }

    // Recording elapsed ticker → "● recording 00:12" + countdown hint.
    LaunchedEffect(recording) {
        recSec = 0
        while (vm.isRecording.value) {
            delay(1000)
            recSec++
        }
    }
    // Mic denied → prototype error-limited state: banner + text-mode sheet.
    LaunchedEffect(micGranted) {
        sheetOpen = !micGranted
    }

    val ble = settings.transportType == com.itantra.models.TransportType.BLE
    val pairLabel = settings.sourceLanguage.uppercase() + " → " +
        settings.targetLanguage.uppercase() + " · " + if (ble) "BLE" else "Loopback"
    // Loopback rehearses as CONNECTED — only call it BLE live on the real transport.
    val linkLabel = when {
        !ble -> "Ready"
        conn == ConnectionState.CONNECTED -> "Connected"
        conn == ConnectionState.DISCONNECTED -> "Ready"
        else -> "Linking…"
    }

    Box(Modifier.fillMaxSize().background(IT.Bg)) {
        Column(Modifier.fillMaxSize().background(IT.Bg)) {
            AppTopBar(pairLabel, linkLabel, conn == ConnectionState.CONNECTED) {
                onNavigate("settings")
            }
            // Prototype error-limited banner + fallback ladder (mic denied).
            if (!micGranted) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ITCard(container = IT.Surface, border = IT.Danger) {
                        Row(verticalAlignment = Alignment.Top) {
                            DangerBadge()
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Microphone unavailable",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Hint(
                                    "Mic access is needed for voice communication. " +
                                        "You are in text-only limited mode — translate " +
                                        "and send still work."
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                ITPrimaryButton("Grant mic & retry", Modifier.fillMaxWidth()) {
                                    requestPerms()
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                ITGhostButton("Keep text mode", Modifier.fillMaxWidth()) {
                                    sheetOpen = true
                                }
                            }
                        }
                    }
                    ITCard {
                        Eyebrow("Fallback ladder")
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LadderRow("L2", "Text only · mic denied (now)")
                            LadderRow("L1", "System TTS if neural voice fails")
                            LadderRow("L3", "Monolingual if MT fails")
                            LadderRow("L4", "SOS beacon if models fail")
                        }
                    }
                }
            }
            // Language pair row (mock .langrow).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    Languages.displayName(settings.sourceLanguage),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp, fontWeight = FontWeight.W500
                    ),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    "⇄",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { vm.swapLanguages() }
                        .wrapContentSize(Alignment.Center),
                    textAlign = TextAlign.Center,
                    color = IT.Ink
                )
                Text(
                    Languages.displayName(settings.targetLanguage),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp, fontWeight = FontWeight.W500
                    ),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                Pill(
                    if (settings.transportType == com.itantra.models.TransportType.BLE) "BLE" else "Loopback",
                    dotOn = true
                )
            }
            // History (newest first). Jump to top whenever a new message lands.
            val listState = rememberLazyListState()
            LaunchedEffect(messages.firstOrNull()?.id) {
                if (messages.isNotEmpty()) listState.scrollToItem(0)
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = true,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg, onReplay = { vm.replay(msg) })
                }
            }
            // Live transcript (mock .transcript). Error state copies the prototype copy.
            ITCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                padding = 14
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Live transcript", Modifier.weight(1f))
                    KvText(
                        if (recording) "● recording " + "%02d:%02d".format(recSec / 60, recSec % 60)
                        else "00:00 / 00:30"
                    )
                }
                Text(
                    when {
                        live.isNotBlank() -> live
                        !micGranted -> "Microphone off — type instead."
                        else -> "Hold the button and speak."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                )
                Spacer(Modifier.height(4.dp))
                WaveBars(active = recording, maxHeight = 22)
            }
            // Processing card (mock #procCard): stage steps while pipeline runs.
            if (appState == AppState.PROCESSING || appState == AppState.TRANSMITTING ||
                appState == AppState.PLAYBACK || appState == AppState.RECEIVING
            ) {
                val stage = when (appState) {
                    AppState.PROCESSING -> 1
                    AppState.RECEIVING -> 2
                    AppState.TRANSMITTING -> 3
                    else -> 4
                }
                val stageName = when (appState) {
                    AppState.PROCESSING -> "Transcribing"
                    AppState.RECEIVING -> "Receiving"
                    AppState.TRANSMITTING -> "Transmitting"
                    else -> "Speaking"
                }
                ITCard(modifier = Modifier.padding(horizontal = 16.dp), padding = 16) {
                    Eyebrow("Stage $stage/4 · $stageName…")
                    Spacer(Modifier.height(6.dp))
                    Steps(stage)
                    Spacer(Modifier.height(8.dp))
                    ITProgress(
                        when (stage) {
                            1 -> 0.4f
                            2 -> 0.55f
                            3 -> 0.72f
                            else -> 0.9f
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Hint(
                        when (appState) {
                            AppState.PROCESSING -> "Converting speech to text…"
                            AppState.TRANSMITTING -> "BLE packet ~200 B · text + prosody…"
                            AppState.RECEIVING -> "Incoming transmission…"
                            else -> "TTS with preserved urgency…"
                        }
                    )
                }
            }
            // Text mode (works with zero models). Green composer per mock.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...", color = IT.Ink3) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) { vm.sendText(text); text = "" }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IT.PttGreen,
                        unfocusedBorderColor = IT.PttGreen,
                        focusedContainerColor = IT.Surface,
                        unfocusedContainerColor = IT.Surface,
                        cursorColor = IT.PttGreen
                    )
                )
                Box(
                    modifier = Modifier
                        .size(height = 56.dp, width = 84.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (text.isNotBlank()) IT.PttGreen else Color(0xFFE2E2E2))
                        .clickable(enabled = text.isNotBlank()) {
                            vm.sendText(text); text = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Send",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = if (text.isNotBlank()) Color.White else IT.Ink3
                        )
                    )
                }
            }
            // Push-to-talk (hidden while mic denied — banner handles recovery).
            if (micGranted) {
                Column(Modifier.fillMaxWidth()) {
                    PttButton(
                        recording = recording,
                        maxDurationSec = settings.maxRecordingDurationSec,
                        remainingSec = (settings.maxRecordingDurationSec - recSec).coerceAtLeast(0),
                        enabled = appState == AppState.READY || appState == AppState.RECORDING,
                        onPress = { vm.pressPtt() },
                        onRelease = { vm.releasePtt() }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            BottomNav(
                current = "main",
                onTalk = {},
                onHistory = { onNavigate("history") },
                onLink = { onNavigate("connection") },
                onModels = { onNavigate("models") },
                onSetup = { onNavigate("settings") }
            )
        }
        // Prototype .text-mode sheet — text-only fallback when mic is denied/noisy.
        if (sheetOpen) {
            TextModeSheet(
                text = sheetText,
                onText = { sheetText = it },
                onSend = {
                    if (sheetText.isNotBlank()) {
                        vm.sendText(sheetText); sheetText = ""
                    }
                },
                onDismiss = { sheetOpen = false }
            )
        }
    }
}

/** Prototype fallback-ladder row: mono tag + hint. */
@Composable
private fun LadderRow(tag: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TagChip(tag)
        Hint(text, Modifier.weight(1f))
    }
}

/** Prototype .sheet — bottom sheet with quick chips and a block send button. */
@Composable
private fun TextModeSheet(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    val quickChips = listOf("Help · flood", "உதவி!", "আসছি")
    Box(Modifier.fillMaxSize()) {
        // .scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss)
        )
        // .sheet
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(IT.Surface)
                .border(1.dp, IT.Border2, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Text mode",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
                Box(
                    modifier = Modifier
                        .itIconButton()
                        .size(44.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall)
                }
            }
            Hint(
                "Text-only fallback when mic is denied or it is too noisy. " +
                    "Same translation + packet path."
            )
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type in your language…", color = IT.Ink3) },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IT.Border2,
                    unfocusedBorderColor = IT.Border2,
                    focusedContainerColor = IT.Surface,
                    unfocusedContainerColor = IT.Surface,
                    cursorColor = IT.Ink
                )
            )
            // .od-cluster quick chips — one-tap phrases fill the input.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickChips.forEach { phrase ->
                    ITChip(phrase) { onText(phrase) }
                }
            }
            ITPrimaryButton("Translate & send", Modifier.fillMaxWidth()) { onSend() }
        }
    }
}

/** Prototype .bottomnav — 5 mono items. */
@Composable
fun BottomNav(
    current: String,
    onTalk: () -> Unit,
    onHistory: () -> Unit,
    onLink: () -> Unit,
    onModels: () -> Unit,
    onSetup: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IT.Surface)
            .border(1.dp, IT.Border, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        NavItem("Talk", Icons.Outlined.Mic, current == "main", Modifier.weight(1f), onTalk)
        NavItem("History", Icons.Outlined.History, current == "history", Modifier.weight(1f), onHistory)
        NavItem("Link", Icons.Outlined.Wifi, current == "connection", Modifier.weight(1f), onLink)
        NavItem("Models", Icons.Outlined.Memory, current == "models", Modifier.weight(1f), onModels)
        NavItem("Setup", Icons.Outlined.Settings, current == "settings", Modifier.weight(1f), onSetup)
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            icon, label,
            modifier = Modifier.size(21.dp),
            tint = if (selected) IT.Ink else IT.Ink3
        )
        Text(
            label.uppercase(),
            style = ITText.nav.copy(color = if (selected) IT.Ink else IT.Ink3)
        )
    }
}

/** Emotion tag copy: incoming "Urgent", outgoing "Calm 87%" (prototype style). */
private fun tagText(msg: ChatMessage, withConfidence: Boolean): String {
    if (msg.isEmergency) return "EMERGENCY"
    val label = msg.emotionLabel.replaceFirstChar { it.uppercase() }
    val pct = (msg.emotionConfidence * 100).toInt()
    return if (withConfidence && msg.emotionConfidence > 0f) "$label $pct%" else label
}

/** Mock .msg — in: lavender full-width card; out: black, 88% right-aligned. */
@Composable
fun MessageBubble(msg: ChatMessage, onReplay: () -> Unit) {
    val incoming = msg.direction == MessageDirection.RECEIVED
    val time = remember(msg.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(msg.timestamp))
    }
    if (incoming) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(IT.MsgIn)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // .peer-head
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Peer", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp, color = IT.MsgInText.copy(alpha = 0.6f)
                        )
                    )
                    PeerTag(tagText(msg, withConfidence = false), emergency = msg.isEmergency)
                }
                Text(
                    msg.displayText,
                    style = MaterialTheme.typography.bodyLarge.copy(color = IT.MsgInText)
                )
                if (msg.originalText != msg.displayText) {
                    Text(
                        msg.originalText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp, color = IT.MsgInText.copy(alpha = 0.6f)
                        )
                    )
                }
                // .peer-play — ▶ + mono status (PENDING / REPLAY).
                PeerPlay(
                    if (msg.audioPath != null) "REPLAY" else msg.deliveryStatus.name,
                    modifier = Modifier.clickable(enabled = msg.audioPath != null, onClick = onReplay)
                )
                if (msg.translationFailed) {
                    Text(
                        "Translation unavailable — showing original",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = IT.MsgInText.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }
    } else {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(IT.Ink)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "You · $time", Modifier.weight(1f),
                            style = ITText.meta.copy(color = Color(0xFFFAFAFA).copy(alpha = 0.5f))
                        )
                        PeerTag(tagText(msg, withConfidence = true), dark = true, emergency = msg.isEmergency)
                    }
                    Text(
                        msg.originalText,
                        style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFFAFAFA))
                    )
                    if (msg.translatedText != null && msg.translatedText != msg.originalText) {
                        Text(
                            msg.translatedText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp, color = Color(0xFFFAFAFA).copy(alpha = 0.65f)
                            )
                        )
                    }
                    if (msg.translationFailed) {
                        Text(
                            "Translation unavailable — showing original",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFFAFAFA).copy(alpha = 0.5f)
                            )
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.clickable(enabled = msg.audioPath != null, onClick = onReplay)
                    ) {
                        Text(
                            listOfNotNull(
                                if (!msg.translationFailed && msg.translatedText != null)
                                    if (msg.mtFallback) "⚠ fallback" else "✓ Translated"
                                else null,
                                if (msg.deliveryStatus == DeliveryStatus.PENDING) "Pending"
                                else "✓ " + msg.deliveryStatus.name.lowercase().replaceFirstChar { it.uppercase() }
                            ).joinToString(" · "),
                            style = ITText.meta.copy(color = Color(0xFFFAFAFA).copy(alpha = 0.5f))
                        )
                        if (msg.audioPath != null) {
                            Icon(
                                Icons.Filled.PlayArrow, "Replay",
                                tint = Color(0xFFFAFAFA), modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mock .ptt — full-width 64dp pill; green idle, red pulse while recording.
 * Recording state shows white wave bars inside + countdown hint below.
 */
@Composable
fun PttButton(
    recording: Boolean,
    maxDurationSec: Int = 30,
    remainingSec: Int = 30,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val pulse by animateFloatAsState(
        targetValue = if (recording) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "pttPulse"
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = if (recording) pulse else 1f
                    scaleY = if (recording) pulse else 1f
                }
                .height(64.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (recording) IT.PttRec else IT.PttGreen)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onPress()
                            try {
                                awaitRelease()
                            } finally {
                                onRelease()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (recording) {
                    WaveBars(
                        active = true, maxHeight = 22,
                        barColor = Color(0xCCFFFFFF), barCount = 12
                    )
                }
                Text(
                    if (recording) "RELEASE TO SEND" else "HOLD TO TALK",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFamily, fontWeight = FontWeight.W600,
                        fontSize = 17.sp, letterSpacing = (-0.34).sp, color = Color.White
                    )
                )
            }
        }
        if (recording) {
            Spacer(Modifier.height(2.dp))
            Text(
                "⏱ $remainingSec s remaining · auto-sends at $maxDurationSec s",
                style = MaterialTheme.typography.bodySmall.copy(color = IT.Ink2),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
