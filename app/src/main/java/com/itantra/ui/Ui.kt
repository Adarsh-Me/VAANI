package com.itantra.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mock shadow: 0 1px 3px rgba(0,0,0,.12). */
fun Modifier.itShadow(shape: Shape): Modifier = shadow(
    elevation = 2.dp, shape = shape, ambientColor = IT.Shadow, spotColor = IT.Shadow
)

/** .eyebrow — mono uppercase 11, ls .08em, sec. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = IT.Ink2) {
    Text(text, modifier = modifier, style = ITText.eyebrow.copy(color = color))
}

/** .hint — 13/1.5 sec. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = IT.Ink2) {
    Text(
        text, modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(color = color)
    )
}

/** .kv — mono 11 sec. */
@Composable
fun KvText(text: String, modifier: Modifier = Modifier, color: Color = IT.Ink2) {
    Text(text, modifier = modifier, style = ITText.kv.copy(color = color))
}

/** .card — surface, 1px border, r16, soft shadow. */
@Composable
fun ITCard(
    modifier: Modifier = Modifier,
    padding: Int = 20,
    container: Color = IT.Surface,
    border: Color = IT.Border,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .itShadow(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(padding.dp)
    ) { content() }
}

/** .dot — 8px circle, lime glow when on. */
@Composable
fun Dot(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (on) IT.Lime else IT.Ink3)
    )
}

/** .pill — mono uppercase chip with optional status dot. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dotOn: Boolean? = null) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(IT.Surface)
            .border(1.dp, IT.Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dotOn != null) Dot(dotOn)
        Text(text, style = ITText.eyebrow)
    }
}

/** .tag — mono bordered tag; emergency variant is solid red. */
@Composable
fun TagChip(text: String, emergency: Boolean = false) {
    val bg = if (emergency) IT.Danger else Color.Transparent
    val fg = if (emergency) Color.White else IT.Ink2
    val bc = if (emergency) IT.Danger else IT.Border2
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, bc, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = ITText.meta.copy(color = fg)
    )
}

/** .chip — mono 11 pill, 44dp touch, black when selected. */
@Composable
fun ITChip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) IT.Ink else IT.Surface)
            .border(
                1.dp, if (selected) IT.Ink else IT.Border2, RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .height(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = ITText.eyebrow.copy(
                color = if (selected) IT.AccentInk else IT.Ink,
                letterSpacing = 0.5.sp
            ),
            textAlign = TextAlign.Center
        )
    }
}

/** .mini — small secondary action, 44dp. */
@Composable
fun ITMini(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier = modifier
            .itShadow(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(IT.Surface2)
            .border(1.dp, IT.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(color = IT.Ink))
    }
}

/** .btn variants. */
@Composable
fun ITPrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IT.Ink, contentColor = IT.AccentInk
        )
    ) { Text(text, style = MaterialTheme.typography.titleSmall.copy(color = IT.AccentInk)) }
}

@Composable
fun ITGhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = IT.Ink
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, IT.Border)
    ) { Text(text, style = MaterialTheme.typography.titleSmall.copy(color = IT.Ink)) }
}

@Composable
fun ITSecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IT.Surface, contentColor = IT.Ink
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, IT.Border2)
    ) { Text(text, style = MaterialTheme.typography.titleSmall.copy(color = IT.Ink)) }
}

@Composable
fun ITDangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IT.Danger, contentColor = Color.White
        )
    ) { Text(text, style = MaterialTheme.typography.titleSmall.copy(color = Color.White)) }
}

/** .divider. */
@Composable
fun ITDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(IT.Border))
}

/** .prog — 8dp rounded bar. */
@Composable
fun ITProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(IT.Track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(IT.Ink)
        )
    }
}

/** .steps — segmented stage bars (pipeline card). */
@Composable
fun Steps(done: Int, total: Int = 4, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (i < done) IT.Ink else IT.Track)
            )
        }
    }
}

/** Animated waveform bars (transcript + PTT recording). */
@Composable
fun WaveBars(
    active: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = IT.Lime,
    barCount: Int = 18,
    maxHeight: Int = 22
) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(
        modifier.height(maxHeight.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(barCount) { i ->
            val h by transition.animateFloat(
                initialValue = 4f + (i % 3) * 3f,
                targetValue = (maxHeight - 2).toFloat(),
                animationSpec = infiniteRepeatable(
                    tween(280 + (i % 5) * 70), RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height(if (active) h.dp else 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor.copy(alpha = if (active) 1f else 0.4f))
            )
        }
    }
}

/** Settings toggle row: label + hint left, black switch right. */
@Composable
fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = IT.Ink,
                checkedThumbColor = IT.Surface,
                uncheckedTrackColor = Color(0xFFD4D4D4),
                uncheckedThumbColor = IT.Surface
            )
        )
    }
}

/**
 * Prototype .peer-head .tag — 13sp body-family chip, sentence case, radius 12.
 * Light variant sits on the lavender bubble; dark variant on the black out-bubble.
 */
@Composable
fun PeerTag(text: String, dark: Boolean = false, emergency: Boolean = false) {
    val bg = if (emergency) IT.Danger else Color.Transparent
    val fg = when {
        emergency -> Color.White
        dark -> Color(0xFFFAFAFA).copy(alpha = 0.7f)
        else -> IT.MsgInText
    }
    val bc = when {
        emergency -> IT.Danger
        dark -> Color(0xFFFAFAFA).copy(alpha = 0.3f)
        else -> IT.MsgInText.copy(alpha = 0.5f)
    }
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.5.dp, bc, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp, color = fg, letterSpacing = 0.sp
        )
    )
}

/** Prototype .peer-play — CSS-style triangle + mono status label (PENDING / REPLAY). */
@Composable
fun PeerPlay(label: String, modifier: Modifier = Modifier, color: Color = IT.MsgInText) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 12.dp, height = 14.dp)) {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h); close()
            }
            drawPath(path, color)
        }
        Text(label, style = ITText.eyebrow.copy(fontSize = 12.sp, color = color))
    }
}

/** Prototype error-banner icon: 40dp bordered square with "!" in danger color. */
@Composable
fun DangerBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, IT.Danger, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("!", style = MaterialTheme.typography.titleMedium.copy(color = IT.Danger))
    }
}

/** Prototype sub-screen header block: eyebrow over a 28sp display title. */
@Composable
fun ScreenTitle(eyebrow: String, title: String) {
    Column {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Onboarding / settings language card: radio + name + region subtitle. */
@Composable
fun LangCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .itShadow(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(IT.Surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) IT.Ink else IT.Border,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) IT.Ink else IT.Ink3, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(IT.Ink))
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(subtitle)
        }
    }
}
