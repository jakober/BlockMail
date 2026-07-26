package com.jakober.klarmail.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R

/**
 * Willkommens-Bildschirm beim allerersten Start: begrüßt neue Nutzer und
 * führt direkt in den Einrichtungsassistenten.
 */
@Composable
fun WelcomeScreen(onSetup: () -> Unit, onSkip: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo_color),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Willkommen bei BlockMail",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Die klare, schnelle Mail-App.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            WelcomeFeature(
                Icons.Filled.AutoAwesomeMosaic,
                "Fokus-Blöcke",
                "Der Posteingang sortiert sich nach Wichtigkeit: Braucht Antwort, " +
                    "Wichtig, Kann warten, Werbung — als übersichtliche Kacheln."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeFeature(
                Icons.Filled.Security,
                "Phishing-Wächter",
                "Erkennt Betrugs-Mails direkt auf dem Gerät und warnt mit rotem " +
                    "Rahmen — keine Daten verlassen dein Handy."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeFeature(
                Icons.Filled.Radar,
                "Antwort-Radar",
                "Erinnert an offene Fragen in deinem Postfach — und wenn du selbst " +
                    "zu lange auf Antwort wartest."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeFeature(
                Icons.Filled.NotificationsActive,
                "Neue Mails sofort",
                "Echtzeit-Push mit Schnellantwort direkt aus der Benachrichtigung."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeFeature(
                Icons.Filled.AllInbox,
                "Alle Konten, ein Überblick",
                "Mehrere Postfächer mit eigenen Farben — einzeln oder gemeinsam."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeFeature(
                Icons.Filled.AutoAwesome,
                "KI, die auf dem Gerät bleibt",
                "Zusammenfassen, Antworten entwerfen, Tages-Überblick und " +
                    "Newsletter-Ordnung."
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Konto einrichten")
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSkip) {
                Text("Später erkunden")
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
