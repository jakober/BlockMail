package com.jakober.klarmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

/**
 * Absender-Avatar. Quellen in dieser Reihenfolge:
 * 1. Marken-Logo-Dienst (hochauflösende Firmenlogos, wie z. B. Spark sie zeigt)
 * 2. Favicon der Domain (Fallback für Domains ohne Markenlogo)
 * 3. Initialen-Kreis in der Primärfarbe des Farbschemas
 */
private val freemailDomains = setOf(
    "gmail.com", "googlemail.com", "outlook.com", "outlook.de", "hotmail.com",
    "hotmail.de", "live.com", "live.de", "msn.com", "yahoo.com", "yahoo.de",
    "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "icloud.com", "me.com",
    "mac.com", "t-online.de", "freenet.de", "aol.com", "mail.de", "mail.com",
    "posteo.de", "proton.me", "protonmail.com", "tutanota.com", "tuta.io"
)

@Composable
fun SenderAvatar(name: String, address: String, size: Dp) {
    val domain = address.substringAfterLast("@", "").lowercase().trim()
    // Einstellung "Absender-Logos": farbig / gedämpft / Graustufen / aus.
    // Bunte Marken-Logos drängen sich sonst vor die gewählte App-Farbe.
    val logoStyle by com.jakober.klarmail.data.Prefs.logoStyleFlow.collectAsState()
    val logoFilter = remember(logoStyle) {
        when (logoStyle) {
            "gray" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            "muted" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.35f) })
            else -> null
        }
    }
    val candidates = remember(domain, logoStyle) {
        // "aus": gar kein Logo laden — überall der Buchstaben-Kreis in der
        // gewählten Farbe
        if (logoStyle != "off" && domain.isNotBlank() && domain !in freemailDomains) {
            // Gemeinsame Quellenliste mit der Mail-Detailansicht (der
            // Google-Favicon-Dienst ist raus — er liefert für unbekannte
            // Domains eine unscharfe Weltkugel statt eines Fehlers)
            com.jakober.klarmail.data.SenderIcon.candidatesFor(domain)
        } else {
            emptyList()
        }
    }
    var sourceIndex by remember(domain) { mutableIntStateOf(0) }

    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                // Buchstabe wächst/schrumpft mit dem Kreis (auch in den
                // kleinen Kacheln noch mittig und nicht abgeschnitten)
                fontSize = (size.value * 0.45f).sp,
                lineHeight = (size.value * 0.6f).sp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

    if (candidates.isEmpty()) {
        fallback()
    } else {
        SubcomposeAsyncImage(
            model = candidates[sourceIndex],
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
            loading = { fallback() },
            error = {
                if (sourceIndex < candidates.lastIndex) {
                    // Nächste Quelle probieren (Logo-Dienst → Favicon → Initial)
                    LaunchedEffect(sourceIndex) { sourceIndex++ }
                }
                fallback()
            },
            success = {
                SubcomposeAsyncImageContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(size * 0.23f)),
                    colorFilter = logoFilter,
                    alpha = if (logoStyle == "muted") 0.9f else 1f
                )
            }
        )
    }
}
