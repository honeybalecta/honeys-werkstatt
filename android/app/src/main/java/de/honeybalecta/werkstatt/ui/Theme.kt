@file:OptIn(ExperimentalTextApi::class)

package de.honeybalecta.werkstatt.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Standardfarben (Anthrazit, Creme, Sand, Rot als einziger Akzent). */
object Farbe {
    val anthrazit = Color(0xFF131312)
    val creme = Color(0xFFEDE7DA)
    val sand = Color(0xFFCFC6B0)
    val rot = Color(0xFFE5402A)
    val rotAufHell = Color(0xFFB8301D)
    val grauAufDunkel = Color(0xFFA9A397)
    val grauAufHell = Color(0xFF5E584D)
    val gruen = Color(0xFF3E8A55)

    // abgeleitete Flächen
    val flaecheDunkel = Color(0xFF1D1D1B)
    val flaecheDunkel2 = Color(0xFF272725)
    val linieDunkel = Color(0xFF383733)
    val flaecheHell = Color(0xFFF6F2EA)
    val flaecheHell2 = Color(0xFFE3DCCB)
}

private val dunkel = darkColorScheme(
    primary = Farbe.rot, onPrimary = Farbe.anthrazit,
    primaryContainer = Farbe.flaecheDunkel2, onPrimaryContainer = Farbe.creme,
    secondary = Farbe.sand, onSecondary = Farbe.anthrazit,
    secondaryContainer = Farbe.flaecheDunkel2, onSecondaryContainer = Farbe.creme,
    tertiary = Farbe.sand, onTertiary = Farbe.anthrazit,
    tertiaryContainer = Farbe.flaecheDunkel2, onTertiaryContainer = Farbe.creme,
    background = Farbe.anthrazit, onBackground = Farbe.creme,
    surface = Farbe.anthrazit, onSurface = Farbe.creme,
    surfaceVariant = Farbe.flaecheDunkel2, onSurfaceVariant = Farbe.grauAufDunkel,
    surfaceTint = Color.Transparent,
    inverseSurface = Farbe.creme, inverseOnSurface = Farbe.anthrazit, inversePrimary = Farbe.rotAufHell,
    error = Farbe.rot, onError = Farbe.anthrazit, errorContainer = Farbe.flaecheDunkel2, onErrorContainer = Farbe.creme,
    outline = Farbe.linieDunkel, outlineVariant = Farbe.linieDunkel,
    scrim = Color.Black,
    surfaceBright = Farbe.flaecheDunkel2, surfaceDim = Farbe.anthrazit,
    surfaceContainerLowest = Farbe.anthrazit, surfaceContainerLow = Farbe.flaecheDunkel,
    surfaceContainer = Farbe.flaecheDunkel, surfaceContainerHigh = Farbe.flaecheDunkel2,
    surfaceContainerHighest = Farbe.flaecheDunkel2,
)

private val hell = lightColorScheme(
    primary = Farbe.rotAufHell, onPrimary = Farbe.creme,
    primaryContainer = Farbe.flaecheHell2, onPrimaryContainer = Farbe.anthrazit,
    secondary = Farbe.grauAufHell, onSecondary = Farbe.creme,
    secondaryContainer = Farbe.flaecheHell2, onSecondaryContainer = Farbe.anthrazit,
    tertiary = Farbe.grauAufHell, onTertiary = Farbe.creme,
    tertiaryContainer = Farbe.flaecheHell2, onTertiaryContainer = Farbe.anthrazit,
    background = Farbe.creme, onBackground = Farbe.anthrazit,
    surface = Farbe.creme, onSurface = Farbe.anthrazit,
    surfaceVariant = Farbe.flaecheHell2, onSurfaceVariant = Farbe.grauAufHell,
    surfaceTint = Color.Transparent,
    inverseSurface = Farbe.anthrazit, inverseOnSurface = Farbe.creme, inversePrimary = Farbe.rot,
    error = Farbe.rotAufHell, onError = Farbe.creme, errorContainer = Farbe.flaecheHell2, onErrorContainer = Farbe.anthrazit,
    outline = Farbe.sand, outlineVariant = Farbe.sand,
    scrim = Color.Black,
    surfaceBright = Farbe.flaecheHell, surfaceDim = Farbe.flaecheHell2,
    surfaceContainerLowest = Farbe.flaecheHell, surfaceContainerLow = Farbe.flaecheHell,
    surfaceContainer = Farbe.flaecheHell, surfaceContainerHigh = Farbe.flaecheHell2,
    surfaceContainerHighest = Farbe.flaecheHell2,
)

/** Inter aus den Assets (der Build legt sie ab); fehlt sie, bleibt die Systemschrift. */
private fun inter(context: Context): FontFamily {
    val vorhanden = runCatching { context.assets.list("fonts")?.toSet() }.getOrNull() ?: emptySet()
    if ("Inter-Regular.ttf" !in vorhanden) return FontFamily.Default
    val am = context.assets
    val liste = buildList {
        add(Font("fonts/Inter-Regular.ttf", am, FontWeight.Normal))
        if ("Inter-Medium.ttf" in vorhanden) add(Font("fonts/Inter-Medium.ttf", am, FontWeight.Medium))
        if ("Inter-SemiBold.ttf" in vorhanden) add(Font("fonts/Inter-SemiBold.ttf", am, FontWeight.SemiBold))
        if ("Inter-Bold.ttf" in vorhanden) add(Font("fonts/Inter-Bold.ttf", am, FontWeight.Bold))
    }
    return FontFamily(liste)
}

private fun typo(f: FontFamily): Typography {
    val t = Typography()
    fun TextStyle.mit() = copy(fontFamily = f)
    return Typography(
        displayLarge = t.displayLarge.mit(), displayMedium = t.displayMedium.mit(), displaySmall = t.displaySmall.mit(),
        headlineLarge = t.headlineLarge.mit(), headlineMedium = t.headlineMedium.mit(),
        headlineSmall = t.headlineSmall.mit().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.mit().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.mit().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.mit(),
        bodyLarge = t.bodyLarge.mit(), bodyMedium = t.bodyMedium.mit(), bodySmall = t.bodySmall.mit(),
        labelLarge = t.labelLarge.mit().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.mit(), labelSmall = t.labelSmall.mit(),
    )
}

@Composable
fun WerkstattTheme(inhalt: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val schrift = remember { inter(ctx) }
    val typografie = remember(schrift) { typo(schrift) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dunkel else hell, typography = typografie, content = inhalt)
}

/** Grün für „aktuell“, in beiden Modi gut lesbar. */
@Composable
fun gruen(): Color = Farbe.gruen

/** true im dunklen Modus (für Wabenfarben). */
@Composable
fun istDunkel(): Boolean = isSystemInDarkTheme()
