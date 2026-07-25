package com.jakober.klarmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import kotlinx.coroutines.launch

/** Anbieter-Voreinstellung für den Einrichtungsassistenten. */
private data class SetupProvider(
    val id: String,
    val label: String,
    val note: String,
    val helpUrl: String?,
    val helpLabel: String?,
    val imap: String,
    val imapPort: Int,
    val smtp: String,
    val smtpPort: Int,
    val passwordLabel: String
)

private val setupProviders = listOf(
    SetupProvider(
        "gmail", "Gmail",
        "Gmail braucht ein App-Passwort: In deinem Google-Konto muss die " +
            "2-Faktor-Bestätigung aktiv sein. Erstelle über den Link unten ein " +
            "App-Passwort und füge es hier ein — dein normales Passwort funktioniert nicht.",
        "https://myaccount.google.com/apppasswords", "App-Passwort bei Google erstellen",
        "imap.gmail.com", 993, "smtp.gmail.com", 465, "App-Passwort"
    ),
    SetupProvider(
        "webde", "Web.de",
        "Bei Web.de muss einmalig „POP3/IMAP“ eingeschaltet sein " +
            "(Web.de-Postfach → Einstellungen → POP3/IMAP Abruf). Danach reicht dein " +
            "normales Passwort; mit 2-Faktor-Schutz brauchst du ein App-Passwort.",
        "https://hilfe.web.de/pop-imap/einschalten.html", "Anleitung öffnen",
        "imap.web.de", 993, "smtp.web.de", 587, "Passwort"
    ),
    SetupProvider(
        "gmx", "GMX",
        "Bei GMX muss einmalig „POP3/IMAP“ eingeschaltet sein " +
            "(GMX-Postfach → E-Mail → Einstellungen → POP3/IMAP Abruf). Danach reicht " +
            "dein normales Passwort; mit 2-Faktor-Schutz brauchst du ein App-Passwort.",
        "https://hilfe.gmx.net/pop-imap/einschalten.html", "Anleitung öffnen",
        "imap.gmx.net", 993, "mail.gmx.net", 587, "Passwort"
    ),
    SetupProvider(
        "outlook", "Outlook / Hotmail",
        "Mit 2-Faktor-Schutz brauchst du ein App-Passwort (Link unten). Hinweis: " +
            "Manche Firmen-Konten (Microsoft 365) erlauben keine Passwort-Anmeldung — " +
            "dann schlägt die Verbindung fehl.",
        "https://account.live.com/proofs/AppPassword", "App-Passwort bei Microsoft erstellen",
        "outlook.office365.com", 993, "smtp.office365.com", 587, "Passwort / App-Passwort"
    ),
    SetupProvider(
        "yahoo", "Yahoo Mail",
        "Yahoo verlangt ein App-Passwort: Erstelle es über den Link unten " +
            "(Konto-Sicherheit → App-Passwörter) und füge es hier ein.",
        "https://login.yahoo.com/myaccount/security", "App-Passwort bei Yahoo erstellen",
        "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, "App-Passwort"
    ),
    SetupProvider(
        "tonline", "T-Online",
        "T-Online braucht ein eigenes „E-Mail-Passwort“ (nicht dein " +
            "Kundencenter-Passwort). Du legst es im E-Mail-Center unter " +
            "„Passwörter“ fest.",
        "https://email.t-online.de", "E-Mail-Center öffnen",
        "secureimap.t-online.de", 993, "securesmtp.t-online.de", 465, "E-Mail-Passwort"
    ),
    SetupProvider(
        "icloud", "iCloud Mail",
        "Apple verlangt ein app-spezifisches Passwort: Erstelle es auf " +
            "appleid.apple.com unter „Anmeldung und Sicherheit“.",
        "https://appleid.apple.com", "Apple-ID-Einstellungen öffnen",
        "imap.mail.me.com", 993, "smtp.mail.me.com", 587, "App-spezifisches Passwort"
    )
)

/**
 * Einrichtungsassistent: Anbieter wählen, E-Mail + Passwort eingeben — Server
 * und Ports setzt die App selbst, die Verbindung wird vor dem Speichern getestet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var provider by remember { mutableStateOf<SetupProvider?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider?.label ?: "Konto einrichten") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (provider != null) {
                            provider = null
                            error = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            val p = provider
            if (p == null) {
                Text(
                    "Wähle deinen E-Mail-Anbieter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Server und Ports stellt BlockMail automatisch ein — du brauchst " +
                        "nur E-Mail-Adresse und Passwort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                setupProviders.forEach { sp ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                provider = sp
                                error = null
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Email,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(sp.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = onBack) {
                    Text("Anderer Anbieter — Server manuell in den Einstellungen eintragen")
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(p.note, style = MaterialTheme.typography.bodyMedium)
                        p.helpUrl?.let { url ->
                            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                                Text(p.helpLabel ?: "Hilfe öffnen")
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-Mail-Adresse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(p.passwordLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !testing && email.contains("@") && password.isNotBlank(),
                    onClick = {
                        scope.launch {
                            testing = true
                            error = null
                            val err = MailRepository.testConnection(
                                email, password, p.imap, p.imapPort
                            )
                            if (err == null) {
                                // Konto speichern und vollständig aktivieren
                                Prefs.snapshotActiveAccount()
                                Prefs.email = email.trim()
                                Prefs.appPassword = password
                                Prefs.imapHost = p.imap
                                Prefs.imapPort = p.imapPort
                                Prefs.smtpHost = p.smtp
                                Prefs.smtpPort = p.smtpPort
                                Prefs.authMethod = "password"
                                // Kein alter Google-Token darf am neuen Konto hängen
                                Prefs.refreshToken = ""
                                Prefs.snapshotActiveAccount()
                                MailRepository.switchAccount(
                                    Prefs.Account(
                                        Prefs.email, Prefs.authMethod, Prefs.appPassword,
                                        Prefs.refreshToken, Prefs.imapHost, Prefs.imapPort,
                                        Prefs.smtpHost, Prefs.smtpPort
                                    )
                                )
                                testing = false
                                onDone()
                            } else {
                                testing = false
                                error = "Verbindung fehlgeschlagen: $err"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Verbindung wird geprüft …")
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Verbinden")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
