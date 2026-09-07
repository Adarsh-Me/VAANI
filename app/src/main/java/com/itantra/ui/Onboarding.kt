package com.itantra.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.itantra.models.Languages

/** UI-only region subtitles for the demo languages (mock .langcard .s). */
private val regionByCode = mapOf(
    "hi" to "उत्तर प्रदेश, दिल्ली",
    "ta" to "தமிழ்நாடு",
    "pa" to "ਪੰਜਾਬ"
)

/**
 * First-launch flow (prototype onboarding-*): welcome → source → target →
 * models → permissions (AppFlow §3).
 */
@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var src by remember { mutableStateOf(Languages.DEFAULT_SOURCE) }
    var tgt by remember { mutableStateOf(Languages.DEFAULT_TARGET) }
    val modelStatus by vm.modelStatus.collectAsState()
    val downloads by vm.modelDownloader.states.collectAsState()
    val ctx = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var btGranted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 31 ||
                (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED)
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        btGranted = grants[Manifest.permission.BLUETOOTH_CONNECT] != false
    }
    fun requestPerms() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        permLauncher.launch(perms.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IT.Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (step) {
            0 -> {
                // Prototype centers the welcome card vertically in the screen.
                Box(Modifier.fillMaxWidth().padding(top = 120.dp)) {
                    ITCard {
                        Eyebrow("SIH26173 · Offline first")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Speak in your language. Be heard in theirs.",
                            style = MaterialTheme.typography.displayMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Walkie-talkie across Hindi, Tamil and Bengali. " +
                                "Speech → text (~200 bytes) → speech, with urgency " +
                                "preserved. No internet required.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = IT.Ink2)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("${Languages.ALL.size}-language roadmap")
                            Pill("BLE 10–30 m")
                            Pill("<2 s p50")
                        }
                        Spacer(Modifier.height(8.dp))
                        ITPrimaryButton("Get started", Modifier.fillMaxWidth()) { step = 1 }
                        Text(
                            "Demo build 1.0 · cascade ASR → MT → TTS",
                            style = ITText.meta,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
            1 -> {
                ITCard {
                    Eyebrow("Step 1 of 4 · Source")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "What language do you speak?",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Languages.DEMO.forEach { code ->
                            LangCard(
                                title = "🔊 " + Languages.displayName(code),
                                subtitle = regionByCode[code] ?: "",
                                selected = code == src,
                                onClick = { src = code }
                            )
                        }
                    }
                    Hint("Demo: Hindi, Tamil, Bengali. Auto-detect uses script detection.")
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ITGhostButton("Back", Modifier.weight(1f)) { step = 0 }
                        ITPrimaryButton("Continue", Modifier.weight(1f)) { step = 2 }
                    }
                }
            }
            2 -> {
                ITCard {
                    Eyebrow("Step 2 of 4 · Target")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Who do you talk to?",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Languages.DEMO.forEach { code ->
                            LangCard(
                                title = "🔊 " + Languages.displayName(code),
                                subtitle = regionByCode[code] ?: "",
                                selected = code == tgt,
                                onClick = { tgt = code }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ITGhostButton("Back", Modifier.weight(1f)) { step = 1 }
                        ITPrimaryButton("Continue", Modifier.weight(1f)) {
                            vm.updateSettings(
                                vm.settings.value.copy(
                                    sourceLanguage = src, targetLanguage = tgt
                                )
                            )
                            step = 3
                        }
                    }
                }
            }
            3 -> {
                ITCard {
                    Eyebrow("Step 3 of 4 · Models")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Setting up your languages",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    // Live pipeline readiness; prototype row copy kept verbatim.
                    val rows = listOf(
                        Triple("Speech Recognition · IndicConformer-600M INT8", "asr", "180 MB"),
                        Triple("Translation Engine · IndicTrans2-320M INT8", "mt", "160 MB"),
                        Triple("Hindi Voice (TTS) · piper · pratham medium", "tts-hi", "25 MB"),
                        Triple("Tamil Voice (TTS) · system fallback", "tts-ta", "25 MB")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { (label, key, size) ->
                            val ready = modelStatus[key] == true
                            val dl = downloads[key]
                            val running = dl?.status?.name == "RUNNING"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label, Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    when {
                                        ready -> "ready ✓"
                                        running -> "$size · ${((dl?.fraction ?: 0f) * 100).toInt()}%"
                                        else -> "$size · queued"
                                    },
                                    style = ITText.kv.copy(
                                        color = if (ready) IT.Lime else IT.Ink2
                                    )
                                )
                            }
                            ITProgress(
                                when {
                                    ready -> 1f
                                    running -> dl?.fraction ?: 0f
                                    else -> 0f
                                }
                            )
                        }
                    }
                    Hint(
                        "⚠️ Keep the app open during download. Text chat works now; " +
                            "voice lights up after VAD (2 MB) + voices."
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ITGhostButton("Back", Modifier.weight(1f)) { step = 2 }
                        ITPrimaryButton("Continue", Modifier.weight(1f)) { step = 4 }
                    }
                }
            }
            4 -> {
                // Prototype onboarding-permissions: "Step 4 of 4 · Permissions".
                ITCard {
                    Eyebrow("Step 4 of 4 · Permissions")
                    Spacer(Modifier.height(8.dp))
                    Text("Mic + Bluetooth", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Grant microphone for talk and Bluetooth for the two-phone " +
                            "link. Denying drops to text-only mode — nothing breaks.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = IT.Ink2)
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToggleRow(
                            "Microphone",
                            "For push-to-talk capture",
                            micGranted
                        ) { _ -> requestPerms() }
                        ToggleRow(
                            "Bluetooth",
                            "For BLE 200-byte packets",
                            btGranted
                        ) { _ -> requestPerms() }
                    }
                    Spacer(Modifier.height(12.dp))
                    ITPrimaryButton("Start using iTantra", Modifier.fillMaxWidth()) { onDone() }
                }
            }
        }
    }
}

@Composable
fun ErrorDialog(msg: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Something went wrong") },
        text = { Text(msg) }
    )
}

/** Message tag: emotion label, solid red for emergency (mock .tag / .tag.emg). */
@Composable
fun EmotionChip(label: String, isEmergency: Boolean) {
    TagChip(
        text = if (isEmergency) "EMERGENCY" else label.uppercase(),
        emergency = isEmergency
    )
}
