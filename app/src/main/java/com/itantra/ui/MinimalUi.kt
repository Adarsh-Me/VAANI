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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.itantra.models.AppState
import com.itantra.models.ChatMessage
import com.itantra.models.Languages
import com.itantra.models.TransportType

/**
 * Minimal 3-tab UI: Demo (chat rehearsal) · Voice (real BLE mesh) ·
 * Languages (pick your pair). No onboarding, no extra screens.
 */

/** The 11 major regional languages offered on the Languages tab. */
val MAJOR_LANGS = listOf(
    "hi" to "हिन्दी",
    "bn" to "বাংলা",
    "ta" to "தமிழ்",
    "te" to "తెలుగు",
    "mr" to "मराठी",
    "gu" to "ગુજરાતી",
    "kn" to "ಕನ್ನಡ",
    "ml" to "മലയാളം",
    "or" to "ଓଡ଼ିଆ",
    "pa" to "ਪੰਜਾਬੀ",
    "as" to "অসমীয়া"
)

/** Languages with bundled flite voices (real offline TTS). */
val FLITE_LANGS = setOf("hi", "ta", "gu", "mr", "te")

@Composable
fun MiniShell(
    current: String,
    vm: MainViewModel,
    onNavigate: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val settings by vm.settings.collectAsState()
    val conn by vm.connection.collectAsState()
    val ble = settings.transportType == TransportType.BLE
    val link = when {
        !ble -> "demo · loopback"
        conn == com.itantra.models.ConnectionState.CONNECTED -> "BLE · connected"
        conn == com.itantra.models.ConnectionState.SCANNING ||
            conn == com.itantra.models.ConnectionState.ADVERTISING ||
            conn == com.itantra.models.ConnectionState.CONNECTING -> "BLE · linking…"
        else -> "BLE · idle"
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(IT.Bg)
    ) {
        // slim top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("iTantra", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                settings.sourceLanguage.uppercase() + " → " +
                    settings.targetLanguage.uppercase() + "  ·  " + link,
                style = ITText.meta
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
        // bottom nav: 3 tabs
        Row(
            Modifier
                .fillMaxWidth()
                .background(IT.Surface)
                .border(1.dp, IT.Border)
                .padding(vertical = 8.dp)
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
    Box(modifier.clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 8.dp),
            style = ITText.kv.copy(
                color = if (selected) IT.Ink else IT.Ink2
            )
        )
    }
}

/** FROM/TO pair picker chips shared by Demo + Voice tabs. */
@Composable
fun PairBar(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    var pickTo by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ITChip(
            "TO · " + Languages.displayName(settings.targetLanguage).substringBefore(" ("),
            selected = true
        ) { pickTo = true }
        Spacer(Modifier.size(8.dp))
        Text("⇄", style = ITText.kv)
        Spacer(Modifier.size(8.dp))
        ITChip(
            "FROM · " + Languages.displayName(settings.sourceLanguage).substringBefore(" ("),
            selected = true
        ) { pickFrom = true }
    }
    if (pickTo) {
        LangPicker("Language you speak (TO)") { code ->
            vm.updateSettings(settings.copy(sourceLanguage = code))
            pickTo = false
        }
    }
    if (pickFrom) {
        LangPicker("Language the other side speaks (FROM)") { code ->
            vm.updateSettings(settings.copy(targetLanguage = code))
            pickFrom = false
        }
    }
}

@Composable
private fun LangPicker(title: String, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MAJOR_LANGS.forEach { (code, native) ->
                    Text(
                        "$native  ·  ${Languages.ALL[code]?.substringAfter("(")?.trimEnd(')') ?: code}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(code) }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick("") }) { Text("Cancel") }
        }
    )
}

/** Shared conversation + controls used by Demo and Voice tabs. */
@Composable
fun ConversationPane(vm: MainViewModel, bigPtt: Boolean) {
    val messages by vm.messages.collectAsState()
    val recording by vm.isRecording.collectAsState()
    val live by vm.liveTranscript.collectAsState()
    val state by vm.appState.collectAsState()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (messages.isNotEmpty()) {
        ITCard(padding = 12) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                messages.takeLast(30).forEach { msg ->
                    MessageBubble(msg, onReplay = { vm.replay(msg) })
                }
            }
        }
    }

    if (recording) {
        ITCard(padding = 12) {
            Column {
                Text("LIVE", style = ITText.eyebrow)
                Text(if (live.isBlank()) "…" else live, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // controls row: input + send
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .border(1.dp, IT.Border, RoundedCornerShape(14.dp))
                .background(IT.Surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = IT.Ink),
                modifier = Modifier.fillMaxWidth()
            ) { decoration ->
                if (text.isEmpty()) {
                    Text("Type a message…", style = MaterialTheme.typography.bodyMedium, color = IT.Ink2)
                }
                decoration()
            }
        }
        Spacer(Modifier.size(8.dp))
        ITMini("Send") {
            if (text.isNotBlank()) {
                vm.sendText(text)
                text = ""
            }
        }
    }

    // PTT
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
    Hint(
        if (bigPtt) "Hold to talk — your speech is transcribed, translated and " +
            "sent over Bluetooth. The peer's reply appears translated here."
        else "Hold to talk or type — rehearsal mode keeps everything on this phone."
    )
}

@Composable
fun DemoScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.setTransport(TransportType.LOOPBACK) }
    MiniShell("main", vm, onNavigate) {
        PairBar(vm)
        ConversationPane(vm, bigPtt = false)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun VoiceScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.setTransport(TransportType.BLE) }
    val conn by vm.connection.collectAsState()
    MiniShell("voice", vm, onNavigate) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(conn == com.itantra.models.ConnectionState.CONNECTED)
            Spacer(Modifier.size(8.dp))
            Text(
                when (conn) {
                    com.itantra.models.ConnectionState.CONNECTED ->
                        "Connected — both phones paired over BLE mesh"
                    com.itantra.models.ConnectionState.SCANNING,
                    com.itantra.models.ConnectionState.ADVERTISING,
                    com.itantra.models.ConnectionState.CONNECTING ->
                        "Looking for another iTantra phone…"
                    else -> "Bluetooth idle — open this tab on both phones"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        PairBar(vm)
        ConversationPane(vm, bigPtt = true)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LanguagesScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsState()
    MiniShell("languages", vm, onNavigate) {
        Text(
            "All engines ship inside the app — nothing to download. " +
                "Pick the pair you speak; STT + translation work for all 11.",
            style = ITText.meta
        )
        MAJOR_LANGS.forEach { (code, nativeName) ->
            val isTo = settings.targetLanguage == code
            val isFrom = settings.sourceLanguage == code
            ITCard(padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            nativeName + "  " +
                                Languages.ALL[code]!!.substringAfter("(").trimEnd(')'),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row {
                            TagChip("STT ✓")
                            Spacer(Modifier.size(6.dp))
                            TagChip("MT ✓")
                            Spacer(Modifier.size(6.dp))
                            if (code in FLITE_LANGS) TagChip("TTS ✓ voice")
                            else Text(
                                "TTS: phone voice",
                                style = ITText.meta.copy(color = IT.Ink2),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        ITMini(if (isTo) "TO ✓" else "Set TO") {
                            vm.updateSettings(settings.copy(targetLanguage = code))
                        }
                        Spacer(Modifier.height(6.dp))
                        ITMini(if (isFrom) "FROM ✓" else "Set FROM") {
                            vm.updateSettings(settings.copy(sourceLanguage = code))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

