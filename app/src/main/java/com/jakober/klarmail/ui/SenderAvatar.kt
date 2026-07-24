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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    val candidates = remember(domain) {
        if (domain.isNotBlank() && domain !in freemailDomains) {
            listOf(
                "https://logo.clearbit.com/$domain?size=256",
                "https://www.google.com/s2/favicons?domain=$domain&sz=256"
            )
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
                        .clip(RoundedCornerShape(10.dp))
                )
            }
        )
    }
}
