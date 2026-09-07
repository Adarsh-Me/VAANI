package com.itantra.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Fullscreen non-dismissible emergency alert (AppFlow §8) — mock .emg-takeover. */
@Composable
fun EmergencyOverlay(text: String, onAck: () -> Unit) {
    var flash by remember { mutableStateOf(true) }
    // Auto-repeat countdown ("auto-repeat 5…4…3…" in the prototype kv row).
    var tick by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            flash = !flash
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            for (t in 5 downTo 1) {
                tick = t
                delay(1000)
            }
        }
    }
    val flashAlpha by animateFloatAsState(
        targetValue = if (flash) 0.16f else 0f,
        animationSpec = tween(250), label = "flash"
    )
    // Playbar progress loops like the repeating alert playback.
    val playProgress by rememberInfiniteTransition(label = "emgPlay").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "playbar"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IT.EmgBg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 32.dp, start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TagChip("🚨 EMERGENCY ALERT", emergency = true)
            Text(
                "Distress detected.\nPlaying at max volume.",
                style = MaterialTheme.typography.displaySmall.copy(color = Color.White)
            )
            // Panel (mock .card on #230a0a): alert text + meta + playbar.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IT.EmgPanel)
                    .border(1.dp, IT.Danger, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (text.isNotBlank()) {
                        Text(
                            text,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = Color.White, fontSize = 19.sp
                            )
                        )
                    }
                    Text(
                        "Max volume · vibration on · auto-repeat every 5 s " +
                            "until acknowledged.",
                        style = MaterialTheme.typography.bodySmall.copy(color = IT.EmgSoftRed)
                    )
                    KvText("auto-repeat ${tick}…${(tick - 1).coerceAtLeast(0)}…", color = IT.EmgSoftRed)
                    Spacer(Modifier.height(4.dp))
                    // .playbar — replay affordance + looping progress.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A0A0A))
                                .clickable(enabled = false) { },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", style = MaterialTheme.typography.titleSmall.copy(color = Color.White))
                        }
                        ITProgress(playProgress, Modifier.weight(1f))
                    }
                }
            }
            Hint("Prototype simulation — no real alarm stream.", color = IT.EmgSoftRed)
            Spacer(Modifier.weight(1f))
            // Mock #emgAck — white block button, dark red text, 64dp.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .clickable(onClick = onAck),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✋ Tap to acknowledge and silence",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color(0xFF300000), fontSize = 17.sp
                    )
                )
            }
        }
        // Flash overlay (mock .emg-flash steps(2) animation).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFFF3B30).copy(alpha = flashAlpha))
        )
    }
}
