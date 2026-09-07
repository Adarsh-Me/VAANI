package com.itantra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.R

/**
 * Design tokens lifted 1:1 from the reference mock (itantra.html :root).
 * Single source of truth for every screen — screens must read these, not
 * hard-code hex values.
 */
object IT {
    val Bg = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF4F4F4)
    val Surface3 = Color(0xFFECECEC)
    val Border = Color(0x14000000)   // rgba(0,0,0,.08)
    val Border2 = Color(0x1F000000)  // rgba(0,0,0,.12)
    val Ink = Color(0xFF0A0A0A)      // --text / --accent
    val Ink2 = Color(0x8C000000)     // --sec  rgba(0,0,0,.55)
    val Ink3 = Color(0x61000000)     // --ter  rgba(0,0,0,.38)
    val AccentInk = Color(0xFFFFFFFF)
    val Lime = Color(0xFF65A30D)
    val Danger = Color(0xFFD92D20)
    val PttGreen = Color(0xFF145A1F) // push-to-talk + send pill
    val PttRec = Color(0xFF7A1F1A)   // recording state
    val MsgIn = Color(0xFFE9E2F1)    // incoming bubble lavender
    val MsgInText = Color(0xFF1D1B20)
    val EmgBg = Color(0xFF1A0505)    // emergency takeover
    val EmgPanel = Color(0xFF230A0A)
    val EmgSoftRed = Color(0xFFFFB4AB)
    val Track = Color(0xFFE5E5E5)    // progress track / steps
    val Shadow = Color(0x1F000000)   // 0 1px 3px rgba(0,0,0,.12)
}

// ---- Downloadable Google Fonts (Space Grotesk / Inter Tight / Space Mono) ----
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val spaceGrotesk = GoogleFont("Space Grotesk")
private val interTight = GoogleFont("Inter Tight")
private val spaceMono = GoogleFont("Space Mono")

/** Headings/display — mock --font-d. */
val DisplayFamily = FontFamily(
    Font(googleFont = spaceGrotesk, fontProvider = fontProvider, weight = FontWeight.W500),
    Font(googleFont = spaceGrotesk, fontProvider = fontProvider, weight = FontWeight.W700)
)

/** Body/UI — mock --font-b. */
val BodyFamily = FontFamily(
    Font(googleFont = interTight, fontProvider = fontProvider, weight = FontWeight.W400),
    Font(googleFont = interTight, fontProvider = fontProvider, weight = FontWeight.W500),
    Font(googleFont = interTight, fontProvider = fontProvider, weight = FontWeight.W600)
)

/** Eyebrows, meta rows, nav labels — mock --font-m. */
val MonoFamily = FontFamily(
    Font(googleFont = spaceMono, fontProvider = fontProvider, weight = FontWeight.W400),
    Font(googleFont = spaceMono, fontProvider = fontProvider, weight = FontWeight.W700)
)

/** Non-Material text roles from the mock (eyebrow / kv / meta / nav). */
object ITText {
    val eyebrow = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.W500, fontSize = 11.sp,
        letterSpacing = 0.9.sp, color = IT.Ink2
    )
    val kv = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.W400, fontSize = 11.sp,
        letterSpacing = 0.4.sp, color = IT.Ink2
    )
    val meta = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.W400, fontSize = 10.5.sp,
        letterSpacing = 0.6.sp, color = IT.Ink3
    )
    val nav = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.W400, fontSize = 9.5.sp,
        letterSpacing = 0.6.sp
    )
}

private val ITTypography = Typography(
    displayMedium = TextStyle( // h1.display 44/1.1, ls -.03em
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp, color = IT.Ink
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.9).sp, color = IT.Ink
    ),
    headlineMedium = TextStyle( // h2.t 28/1.15, ls -.03em
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp, color = IT.Ink
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.5).sp, color = IT.Ink
    ),
    titleLarge = TextStyle( // topbar title 19, ls -.02em
        fontFamily = DisplayFamily, fontWeight = FontWeight.W700,
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp, color = IT.Ink
    ),
    titleMedium = TextStyle( // langcard name / bold rows 17
        fontFamily = BodyFamily, fontWeight = FontWeight.W600,
        fontSize = 17.sp, lineHeight = 22.sp, color = IT.Ink
    ),
    titleSmall = TextStyle( // buttons 15 w600
        fontFamily = BodyFamily, fontWeight = FontWeight.W600,
        fontSize = 15.sp, lineHeight = 20.sp, color = IT.Ink
    ),
    bodyLarge = TextStyle( // orig bubble text 16/1.5
        fontFamily = BodyFamily, fontWeight = FontWeight.W400,
        fontSize = 16.sp, lineHeight = 24.sp, color = IT.Ink
    ),
    bodyMedium = TextStyle( // p.body 15/1.6
        fontFamily = BodyFamily, fontWeight = FontWeight.W400,
        fontSize = 15.sp, lineHeight = 24.sp, color = IT.Ink
    ),
    bodySmall = TextStyle( // hint 13/1.5
        fontFamily = BodyFamily, fontWeight = FontWeight.W400,
        fontSize = 13.sp, lineHeight = 20.sp, color = IT.Ink2
    ),
    labelLarge = TextStyle( // label.fl 14 w600
        fontFamily = BodyFamily, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp, color = IT.Ink
    ),
    labelSmall = TextStyle( // mono tags
        fontFamily = MonoFamily, fontWeight = FontWeight.W400,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp
    )
)

private val ITShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),  // --r
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)    // --r-lg
)

private val ITColors = lightColorScheme(
    primary = IT.Ink,
    onPrimary = IT.AccentInk,
    secondary = IT.Ink,
    onSecondary = IT.AccentInk,
    background = IT.Bg,
    onBackground = IT.Ink,
    surface = IT.Surface,
    onSurface = IT.Ink,
    surfaceVariant = IT.Surface2,
    onSurfaceVariant = IT.Ink2,
    surfaceContainer = IT.Surface,
    surfaceContainerHigh = IT.Surface2,
    outline = IT.Border2,
    outlineVariant = IT.Border,
    error = IT.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/** Light-only theme per the mock. darkTheme kept for signature compatibility. */
@Composable
fun ITantraTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ITColors,
        typography = ITTypography,
        shapes = ITShapes,
        content = content
    )
}
