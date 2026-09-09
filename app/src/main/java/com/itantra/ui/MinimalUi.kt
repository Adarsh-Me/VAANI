package com.itantra.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.itantra.models.AppState
import com.itantra.models.ConnectionState
import com.itantra.models.Languages
import com.itantra.models.TransportType

/**
 * Minimal 3-tab UI.
 *   Demo      â€” full-screen conversation; controls pinned to the bottom.
 *   Voice     â€” call surface: mesh status pill, tap-to-talk or hold, live thread.
 *   Languages â€” 11 regional languages, one-tap pair assignment.
 * Layout rules: no page-level scrolling over chat (chat owns the scroll),
 * one accent, no emoji, empty/loading states composed on purpose.
 */

/** The 11 major regional languages offered end-to-end. */
val MAJOR_LANGS = listOf(
    "hi" to "à¤¹à¤¿à¤¨à¥à¤¦à¥€",
    "bn" to "à¦¬à¦¾à¦‚à¦²à¦¾",
    "ta" to "à®¤à®®à®¿à®´à¯",
    "te" to "à°¤à±†à°²à±à°—à±",
    "mr" to "à¤®à¤°à¤¾à¤ à¥€",
    "gu" to "àª—à«àªœàª°àª¾àª¤à«€",
    "kn" to "à²•à²¨à³à²¨à²¡",
    "ml" to "à´®à´²à´¯à´¾à´³à´‚",
    "or" to "à¬“à¬¡à¬¼à¬¿à¬†",
    "pa" to "à¨ªà©°à¨œà¨¾à¨¬à©€",
    "as" to "à¦…à¦¸à¦®à§€à¦¯à¦¼à¦¾"
)

/** Languages with bundled flite voices (real offline neural-class TTS). */
val FLITE_LANGS = setOf("hi", "ta", "gu", "mr", "te")

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ shell â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun MiniShell(
    current: String,
    vm: MainViewModel,
    onNavigate: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(IT.Bg)
    ) {
        content()
        // bottom nav
        Row(
            Modifier
                .fillMaxWidth()
                .background(IT.Surface)
                .border(1.dp, IT.Border)
                .padding(vertical = 6.dp)
        ) {
            MiniTab("Demo", current == "main", Modifier.weight(1f)) { onNavigate("main") }
            MiniTab("Voice", current == "voice", Modifier.weight(1f)) { onNavigate("voice") }
            MiniTab("Languages", current == "languages", Modifier.weight(1f)) {
                onNavigate("languages")
            }
        }
    }
}

@Composable
private fun MiniTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable { onClick() }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = ITText.kv.copy(color = if (selected) IT.Ink else IT.Ink2)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(width = 18.dp, height = 2.dp)
                .background(if (selected) IT.Ink else IT.Bg)
        )
    }
}

/** App header strip: wordmark + active pair + transport dot. */
@Composable
private fun TopStrip(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val conn by vm.connection.collectAsState()
    val ble = settings.transportType == TransportType.BLE
    Row(
        Modifier
            .fillMaxWidth()
            .background(IT.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("iTantra", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Text(
            Languages.displayName(settings.sourceLanguage).substringBefore(" (") +
                " â†’ " +
                Languages.displayName(settings.targetLanguage).substringBefore(" ("),
            style = ITText.kv
        )
        Spacer(Modifier.size(10.dp))
        Dot(ble && conn == ConnectionState.CONNECTED)
        Spacer(Modifier.size(5.dp))
        Text(
            if (!ble) "DEMO" else when (conn) {
                ConnectionState.CONNECTED -> "LINKED"
                ConnectionState.SCANNING, ConnectionState.ADVERTISING,
                ConnectionState.CONNECTING -> "MESHâ€¦"
                else -> "MESH OFF"
            },
            style = ITText.eyebrow
        )
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ pair picker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun PairBar(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    var pickTo by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ITChip(
            "TO Â· " + Languages.displayName(settings.sourceLanguage).substringBefore(" ("),
            selected = true
        ) { pickTo = true }
        Spacer(Modifier.size(8.dp))
        Text("â‡„", style = ITText.kv)
        Spacer(Modifier.size(8.dp))
        ITChip(
            "FROM Â· " + Languages.displayName(settings.targetLanguage).substringBefore(" ("),
            selected = true
        ) { pickFrom = true }
    }
    if (pickTo) {
        LangPicker("Language you speak (TO)") { code ->
            if (code.isNotEmpty()) vm.updateSettings(settings.copy(sourceLanguage = code))
            pickTo = false
        }
    }
    if (pickFrom) {
        LangPicker("Language the other side speaks (FROM)") { code ->
            if (code.isNotEmpty()) vm.updateSettings(settings.copy(targetLanguage = code))
            pickFrom = false
        }
    }
}

@Composable
private fun LangPicker(title: String, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick("") },
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MAJOR_LANGS.forEach { (code, nativeName) ->
                    val en = Languages.ALL[code]!!.substringAfter("(").trimEnd(')')
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(code) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(nativeName, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        Text(en, style = ITText.meta)
                        Spacer(Modifier.weight(1f))
                        Text("select", style = ITText.eyebrow)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick("") }) { Text("Cancel") }
        }
    )
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ conversation core â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Full-height message thread with auto-scroll and a composed empty state.
 * Returns nothing visually outside its own area â€” parents place it with weight(1f).
 */
@Composable
private fun Thread(
    vm: MainViewModel,
    bigMic: Boolean,
    modifier: Modifier = Modifier
) {
    val messages by vm.messages.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(IT.Bg)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 12.dp, bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages.takeLast(60), key = { it.id }) { msg ->
                MessageBubble(msg, onReplay = { vm.replay(msg) })
            }
        }
        if (messages.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .border(1.dp, IT.Border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("mic", style = ITText.eyebrow)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (bigMic) "Mesh is quiet" else "Nothing said yet",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (bigMic)
                        "Tap or hold the mic and speak â€” your words travel as bytes and come back in their language."
                    else
                        "Hold the mic and speak, or type below. Translation happens on-device.",
                    style = ITText.meta,
                    modifier = Modifier.alpha(0.9f)
                )
            }
        }
    }
}

/** Bottom control dock: live strip + input row + mic control. */
@Composable
private fun Dock(vm: MainViewModel, bigPtt: Boolean, tapMode: Boolean = false) {
    val recording by vm.isRecording.collectAsState()
    val live by vm.liveTranscript.collectAsState()
    val state by vm.appState.collectAsState()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier
            .fillMaxWidth()
            .background(IT.Surface)
            .border(1.dp, IT.Border)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(true)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (live.isBlank()) "Listeningâ€¦" else live,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 64.dp)
                )
            }
        }
        var text by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .border(1.dp, IT.Border, RoundedCornerShape(24.dp))
                    .background(IT.Bg, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = IT.Ink),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                ) { deco ->
                    if (text.isEmpty()) {
                        Text(
                            "Type a messageâ€¦",
                            style = MaterialTheme.typography.bodyMedium,
                            color = IT.Ink2
                        )
                    }
                    deco()
                }
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .background(IT.Ink, RoundedCornerShape(24.dp))
                    .clickable {
                        if (text.isNotBlank()) {
                            vm.sendText(text)
                            text = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("send", style = ITText.kv.copy(color = IT.Bg))
            }
        }
        if (tapMode) {
            // Tap-to-talk: one tap opens the mic, the next tap (or the end of
            // the utterance) closes it. VAD flushes trailing speech on stop.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(
                        if (recording) IT.Ink else IT.Surface,
                        RoundedCornerShape(29.dp)
                    )
                    .border(1.dp, IT.Border, RoundedCornerShape(29.dp))
                    .clickable(enabled = state == AppState.READY || state == AppState.RECORDING) {
                        if (!vm.hasMicPermission()) {
                            mic.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else if (recording) {
                            vm.releasePtt()
                        } else {
                            vm.pressPtt()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (recording) "LISTENING â€” TAP TO SEND" else "TAP TO SPEAK",
                    style = ITText.kv.copy(
                        color = if (recording) IT.Bg else IT.Ink
                    )
                )
            }
        } else {
            PttButton(
                recording = recording,
                enabled = state == AppState.READY || state == AppState.RECORDING,
                onPress = {
                    if (!vm.hasMicPermission()) {
                        mic.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.pressPtt()
                    }
                },
                onRelease = { vm.releasePtt() }
            )
        }
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Demo tab â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun DemoScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.setTransport(TransportType.LOOPBACK) }
    MiniShell("main", vm, onNavigate) {
        TopStrip(vm)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp)) { PairBar(vm) }
        Spacer(Modifier.height(8.dp))
        Thread(vm, bigMic = false, modifier = Modifier.weight(1f))
        Dock(vm, bigPtt = false)
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Voice tab â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun VoiceScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsState()
    val conn by vm.connection.collectAsState()
    val recording by vm.isRecording.collectAsState()
    val state by vm.appState.collectAsState()
    var tapMode by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val bt = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val meshOn = settings.transportType == TransportType.BLE
    LaunchedEffect(Unit) { if (!meshOn) vm.setTransport(TransportType.BLE) }

    MiniShell("voice", vm, onNavigate) {
        TopStrip(vm)
        // mesh status pill (island style)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, IT.Border, RoundedCornerShape(18.dp))
                .background(IT.Surface, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(meshOn && conn == ConnectionState.CONNECTED)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !meshOn -> "Bluetooth mesh off"
                        conn == ConnectionState.CONNECTED -> "Linked â€” say something"
                        conn == ConnectionState.SCANNING ||
                            conn == ConnectionState.ADVERTISING ||
                            conn == ConnectionState.CONNECTING -> "Searching the meshâ€¦"
                        else -> "Idle â€” turn the mesh on"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "other phones with iTantra appear here automatically",
                    style = ITText.eyebrow
                )
            }
            ITMini(if (meshOn) "turn off" else "turn on") {
                if (!meshOn) {
                    bt.launch(
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_ADVERTISE
                        )
                    )
                    vm.setTransport(TransportType.BLE)
                } else {
                    vm.setTransport(TransportType.LOOPBACK)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PairBar(vm)
                Spacer(Modifier.weight(1f))
                ITChip("TAP-TO-TALK", selected = tapMode) { tapMode = !tapMode }
            }
        }
        Spacer(Modifier.height(6.dp))
        Thread(vm, bigMic = true, modifier = Modifier.weight(1f))
        Dock(vm, bigPtt = true, tapMode = tapMode)
    }
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Languages tab â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun LanguagesScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsState()
    MiniShell("languages", vm, onNavigate) {
        TopStrip(vm)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Engines already ship inside the app â€” pick the pair you " +
                    "speak. Speech recognition and translation cover all 11; " +
                    "flite voices exist for 5 of them.",
                style = ITText.meta
            )
            Spacer(Modifier.height(2.dp))
            MAJOR_LANGS.forEach { (code, nativeName) ->
                val isTo = settings.sourceLanguage == code
                val isFrom = settings.targetLanguage == code
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, IT.Border, RoundedCornerShape(14.dp))
                        .background(IT.Surface, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            nativeName + "  " +
                                Languages.ALL[code]!!.substringAfter("(").trimEnd(')'),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            KvText("SPEECH IN")
                            Text("|", style = ITText.eyebrow)
                            KvText("TRANSLATE")
                            Text("|", style = ITText.eyebrow)
                            KvText(
                                if (code in FLITE_LANGS) "VOICE OUT" else "VOICE OUT (phone)"
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        ITMini(if (isTo) "YOU" else "as you") {
                            vm.updateSettings(settings.copy(sourceLanguage = code))
                        }
                        Spacer(Modifier.height(6.dp))
                        ITMini(if (isFrom) "THEM" else "as them") {
                            vm.updateSettings(settings.copy(targetLanguage = code))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
