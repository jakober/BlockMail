package com.jakober.klarmail.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.jakober.klarmail.R
import com.jakober.klarmail.data.Prefs

data class SchemeDef(
    val id: String,
    /** Anzeigename als String-Ressource (uebersetzbar de/en). */
    val labelRes: Int,
    val preview: Color,
    val light: ColorScheme,
    val dark: ColorScheme
)

private fun makeScheme(
    id: String,
    labelRes: Int,
    primary: Color,
    primaryDark: Color,
    container: Color,
    onContainer: Color,
    containerDark: Color,
    onContainerDark: Color
) = SchemeDef(
    id = id,
    labelRes = labelRes,
    preview = primary,
    light = lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = primary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        surfaceTint = primary
    ),
    dark = darkColorScheme(
        primary = primaryDark,
        onPrimary = Color(0xFF10131A),
        primaryContainer = containerDark,
        onPrimaryContainer = onContainerDark,
        secondary = primaryDark,
        secondaryContainer = containerDark,
        onSecondaryContainer = onContainerDark,
        surfaceTint = primaryDark
    )
)

val colorSchemes = listOf(
    SchemeDef(
        id = "klarmail",
        labelRes = R.string.scheme_default,
        preview = Color(0xFFEE5F0F),
        light = lightColorScheme(
            primary = Color(0xFFD9530A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDCC7),
            onPrimaryContainer = Color(0xFF351000),
            secondary = Color(0xFF9C6F00),
            secondaryContainer = Color(0xFFFFE9AE),
            onSecondaryContainer = Color(0xFF302400),
            tertiary = Color(0xFFF2C230),
            surfaceTint = Color(0xFFD9530A)
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB68B),
            onPrimary = Color(0xFF541F00),
            primaryContainer = Color(0xFF9A3B00),
            onPrimaryContainer = Color(0xFFFFDCC7),
            secondary = Color(0xFFF2C230),
            secondaryContainer = Color(0xFF5C4300),
            onSecondaryContainer = Color(0xFFFFE9AE),
            tertiary = Color(0xFFF2C230),
            surfaceTint = Color(0xFFFFB68B)
        )
    ),
    makeScheme(
        "ozean", R.string.scheme_ocean,
        primary = Color(0xFF2F5FD0), primaryDark = Color(0xFFAEC6FF),
        container = Color(0xFFDBE1FF), onContainer = Color(0xFF00174B),
        containerDark = Color(0xFF10448F), onContainerDark = Color(0xFFDBE1FF)
    ),
    makeScheme(
        "wald", R.string.scheme_forest,
        primary = Color(0xFF2E6B3F), primaryDark = Color(0xFF95D5A2),
        container = Color(0xFFB1F1BC), onContainer = Color(0xFF00210C),
        containerDark = Color(0xFF15522A), onContainerDark = Color(0xFFB1F1BC)
    ),
    makeScheme(
        "violett", R.string.scheme_violet,
        primary = Color(0xFF6B4FA8), primaryDark = Color(0xFFD0BCFF),
        container = Color(0xFFE9DDFF), onContainer = Color(0xFF22005D),
        containerDark = Color(0xFF503786), onContainerDark = Color(0xFFE9DDFF)
    ),
    makeScheme(
        "sonne", R.string.scheme_sunset,
        primary = Color(0xFFB4491F), primaryDark = Color(0xFFFFB59A),
        container = Color(0xFFFFDBCE), onContainer = Color(0xFF390C00),
        containerDark = Color(0xFF8A3313), onContainerDark = Color(0xFFFFDBCE)
    ),
    makeScheme(
        "mono", R.string.scheme_mono,
        primary = Color(0xFF3C3C43), primaryDark = Color(0xFFC9C9D0),
        container = Color(0xFFE2E2E9), onContainer = Color(0xFF17171C),
        containerDark = Color(0xFF47474E), onContainerDark = Color(0xFFE2E2E9)
    )
)

/**
 * Baut aus einer frei gewählten Akzentfarbe ein vollständiges Schema
 * (Hell + Dunkel) — Container- und Kontrasttöne werden abgeleitet.
 */
fun customScheme(base: Color): SchemeDef = makeScheme(
    id = "custom",
    labelRes = R.string.settings_custom_color,
    primary = base,
    primaryDark = lerp(base, Color.White, 0.45f),
    container = lerp(base, Color.White, 0.82f),
    onContainer = lerp(base, Color.Black, 0.72f),
    containerDark = lerp(base, Color.Black, 0.45f),
    onContainerDark = lerp(base, Color.White, 0.82f)
)

/**
 * Kräftige Grundfarbe des gewählten Schemas — im Gegensatz zu
 * colorScheme.primary auch im Dunkelmodus NICHT aufgehellt.
 */
val LocalAccent = androidx.compose.runtime.staticCompositionLocalOf { Color(0xFFD9530A) }

@Composable
fun KlarMailTheme(
    schemeId: String,
    darkMode: String = "system",
    content: @Composable () -> Unit
) {
    val customColor by Prefs.customColorFlow.collectAsState()
    val def = if (schemeId == "custom") {
        customScheme(Color(customColor))
    } else {
        colorSchemes.find { it.id == schemeId } ?: colorSchemes.first()
    }
    val dark = when (darkMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) def.dark else def.light

    // Untere Systemleiste (Gesten-/Navigationsleiste) deckend in App-Farbe
    // einfärben: Bei voll transparenter Leiste blenden manche Geräte den
    // Gesten-Balken (auch für „Circle to Search“) aus oder er verschwindet
    // optisch im Inhalt — mit eigenem Hintergrund bleibt er sichtbar.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
                ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.navigationBarColor = scheme.surface.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(window, view)
                .isAppearanceLightNavigationBars = !dark
        }
    }

    // Schriftgröße (Dreipunkt-Menü im Posteingang, 50–150 %): skaliert
    // alle sp-Texte der App über den fontScale der Dichte
    val fontScalePercent by Prefs.fontScaleFlow.collectAsState()
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAccent provides def.light.primary,
        androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(
                baseDensity.density,
                baseDensity.fontScale * fontScalePercent / 100f
            )
    ) {
        MaterialTheme(
            colorScheme = scheme,
            content = content
        )
    }
}
