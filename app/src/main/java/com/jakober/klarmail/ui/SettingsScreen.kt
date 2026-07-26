package com.jakober.klarmail.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.GoogleAuth
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import com.jakober.klarmail.service.MailSyncService
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

/** Vordefinierte Mail-Anbieter (Server werden automatisch gesetzt). */
private data class MailProvider(
    val id: String,
    val label: String,
    val imap: String,
    val imapPort: Int,
    val smtp: String,
    val smtpPort: Int
)

private val mailProviders = listOf(
    MailProvider("gmail", "Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465),
    MailProvider("webde", "Web.de", "imap.web.de", 993, "smtp.web.de", 587),
    MailProvider("gmx", "GMX", "imap.gmx.net", 993, "mail.gmx.net", 587),
    MailProvider("outlook", "Outlook / Office 365", "outlook.office365.com", 993, "smtp.office365.com", 587),
    MailProvider("custom", "Eigenes (IMAP)", "", 0, "", 0)
)

private fun providerIdFor(imapHost: String): String =
    mailProviders.firstOrNull { it.imap.isNotBlank() && it.imap.equals(imapHost, ignoreCase = true) }
        ?.id ?: "custom"

/** Wählbare Konto-Farben (Balken vorne an den Mail-Karten). */
private val accountPalette = listOf(
    0xFFE53935, // Rot
    0xFFFB8C00, // Orange
    0xFFFBC02D, // Gelb
    0xFF43A047, // Grün
    0xFF00ACC1, // Türkis
    0xFF1E88E5, // Blau
    0xFF8E24AA, // Violett
    0xFFD81B60 // Pink
).map { it.toInt() }

/** Wählbare Wisch-Aktionen für den Posteingang. */
private val swipeActionLabels = listOf(
    "delete" to "Löschen",
    "archive" to "Archivieren",
    "read" to "Gelesen/Ungelesen",
    "snooze" to "Erinnern (morgen 8 Uhr)"
)

/**
 * Einfacher Farbwähler: Farbton, Sättigung und Helligkeit als Regler mit
 * großer Live-Vorschau — ohne Zusatz-Bibliothek.
 */
@Composable
private fun ColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val startHsv = remember(initial) {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, arr)
        arr
    }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var bright by remember { mutableStateOf(startHsv[2]) }
    val color = Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), bright.coerceIn(0f, 1f))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eigene Farbe wählen") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(color)
                )
                Spacer(Modifier.height(16.dp))
                Text("Farbton", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f
                )
                Text("Sättigung", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = sat,
                    onValueChange = { sat = it },
                    valueRange = 0f..1f
                )
                Text("Helligkeit", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = bright,
                    onValueChange = { bright = it },
                    valueRange = 0f..1f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color.toArgb()) }) { Text("Übernehmen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun SwipeActionPicker(title: String, value: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { open = true }
            ) {
                Text(
                    swipeActionLabels.firstOrNull { it.first == value }?.second ?: "Löschen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                swipeActionLabels.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(id)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenNewsletterLog: () -> Unit = {},
    onOpenSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var email by remember { mutableStateOf(Prefs.email) }
    var password by remember { mutableStateOf(Prefs.appPassword) }
    var claudeKey by remember { mutableStateOf(Prefs.claudeApiKey) }
    var googleConnected by remember {
        mutableStateOf(Prefs.authMethod == "oauth" && Prefs.refreshToken.isNotBlank())
    }
    var addingAccount by remember { mutableStateOf(false) }
    var newsletterRunning by remember { mutableStateOf(false) }
    var newsletterResult by remember { mutableStateOf<String?>(null) }
    var connectedEmail by remember { mutableStateOf(Prefs.email) }
    val selectedScheme by Prefs.colorSchemeFlow.collectAsState()
    val darkMode by Prefs.darkModeFlow.collectAsState()
    val conversationView by Prefs.conversationViewFlow.collectAsState()
    val devMode by Prefs.devModeFlow.collectAsState()
    var accountList by remember { mutableStateOf(Prefs.accounts()) }
    var providerId by remember { mutableStateOf(providerIdFor(Prefs.imapHost)) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var imapHostField by remember { mutableStateOf(Prefs.imapHost) }
    var imapPortField by remember { mutableStateOf(Prefs.imapPort.toString()) }
    var smtpHostField by remember { mutableStateOf(Prefs.smtpHost) }
    var smtpPortField by remember { mutableStateOf(Prefs.smtpPort.toString()) }
    var signatureText by remember { mutableStateOf(Prefs.signature) }
    var templates by remember { mutableStateOf(Prefs.mailTemplates()) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    if (showTemplateDialog) {
        var tplTitle by remember { mutableStateOf("") }
        var tplText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Vorlage hinzufügen") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tplTitle,
                        onValueChange = { tplTitle = it },
                        label = { Text("Titel") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tplText,
                        onValueChange = { tplText = it },
                        label = { Text("Text") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = tplTitle.isNotBlank() && tplText.isNotBlank(),
                    onClick = {
                        templates = templates + (tplTitle.trim() to tplText)
                        Prefs.saveMailTemplates(templates)
                        showTemplateDialog = false
                    }
                ) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    val authService = remember { AuthorizationService(context) }
    DisposableEffect(Unit) {
        onDispose { authService.dispose() }
    }

    fun onSignedIn(newEmail: String) {
        googleConnected = true
        connectedEmail = newEmail
        email = newEmail
        addingAccount = false
        scope.launch {
            snackbar.showSnackbar("Mit Google verbunden: $newEmail")
            // Vollständiger Kontowechsel: Caches leeren, Posteingang laden,
            // Push-Dienst auf das neue Konto verbinden
            MailRepository.switchAccount(
                Prefs.Account(
                    Prefs.email, Prefs.authMethod, Prefs.appPassword, Prefs.refreshToken,
                    Prefs.imapHost, Prefs.imapPort, Prefs.smtpHost, Prefs.smtpPort
                )
            )
        }
    }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            if (data != null) {
                AuthorizationException.fromIntent(data)?.let { ex ->
                    scope.launch { snackbar.showSnackbar("Anmeldung abgebrochen: ${ex.errorDescription ?: ex.error ?: ""}") }
                }
            }
            return@rememberLauncherForActivityResult
        }
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) {
            scope.launch {
                snackbar.showSnackbar("Anmeldung fehlgeschlagen: ${ex?.errorDescription ?: ex?.error ?: "unbekannt"}")
            }
            return@rememberLauncherForActivityResult
        }
        authService.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
            if (tokenResp?.accessToken != null) {
                // Bisheriges Konto in der Kontenliste sichern, bevor die
                // aktiven Zugangsdaten überschrieben werden (Mehrkonten)
                Prefs.snapshotActiveAccount()
                Prefs.accessToken = tokenResp.accessToken ?: ""
                Prefs.accessTokenExpiry = tokenResp.accessTokenExpirationTime ?: 0L
                tokenResp.refreshToken?.let { Prefs.refreshToken = it }
                val mail = GoogleAuth.emailFromIdToken(tokenResp.idToken)
                if (mail != null) Prefs.email = mail
                Prefs.authMethod = "oauth"
                // Google-Anmeldung nutzt immer die Gmail-Server
                Prefs.imapHost = "imap.gmail.com"
                Prefs.imapPort = 993
                Prefs.smtpHost = "smtp.gmail.com"
                Prefs.smtpPort = 465
                // Neues Konto ebenfalls in die Kontenliste aufnehmen
                Prefs.snapshotActiveAccount()
                onSignedIn(mail ?: Prefs.email)
            } else {
                scope.launch {
                    snackbar.showSnackbar(
                        "Token-Austausch fehlgeschlagen: ${tokenEx?.errorDescription ?: tokenEx?.error ?: "unbekannt"}"
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Ganz oben: Erscheinungsbild (Hell/Dunkel + Farbwelt)
            SectionCard(
                "Erscheinungsbild", Icons.Filled.Palette,
                subtitle = "Hell/Dunkel und die Farben der App"
            ) {
            listOf(
                "system" to "Wie das Gerät (automatisch)",
                "light" to "Hell",
                "dark" to "Dunkel"
            ).forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { Prefs.darkMode = id }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = darkMode == id,
                        onClick = { Prefs.darkMode = id }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Farbschema")
            colorSchemes.forEach { scheme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { Prefs.colorScheme = scheme.id }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedScheme == scheme.id,
                        onClick = { Prefs.colorScheme = scheme.id }
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(scheme.preview, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(scheme.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Frei wählbare Akzentfarbe mit eigenem Farbwähler
            val customColor by Prefs.customColorFlow.collectAsState()
            var showColorPicker by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (selectedScheme == "custom") showColorPicker = true
                        else Prefs.colorScheme = "custom"
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedScheme == "custom",
                    onClick = { Prefs.colorScheme = "custom" }
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color(customColor), CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Eigene Farbe",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showColorPicker = true }) { Text("Ändern") }
            }
            if (showColorPicker) {
                ColorPickerDialog(
                    initial = customColor,
                    onDismiss = { showColorPicker = false },
                    onPick = { picked ->
                        Prefs.customColor = picked
                        Prefs.colorScheme = "custom"
                        showColorPicker = false
                    }
                )
            }

            }

            SectionCard(
                "Konto verbinden", Icons.Filled.AccountCircle,
                subtitle = "Neues Postfach hinzufügen oder Zugangsdaten ändern"
            ) {

            // Empfohlener Weg: Assistent mit fertigen Server-Vorlagen — nur
            // Anbieter wählen, E-Mail und Passwort eingeben.
            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Einrichtungsassistent starten")
            }
            Text(
                "Empfohlen: Anbieter wählen, E-Mail und Passwort eingeben — fertig. " +
                    "Server und Ports werden automatisch gesetzt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            if (googleConnected && !addingAccount) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Mit Google verbunden",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                connectedEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        TextButton(onClick = {
                            GoogleAuth.signOut()
                            googleConnected = false
                            scope.launch { snackbar.showSnackbar("Google-Konto getrennt") }
                        }) { Text("Trennen") }
                    }
                }
                // Weiteres Konto anlegen, OHNE das aktuelle zu trennen
                TextButton(onClick = {
                    addingAccount = true
                    email = ""
                    password = ""
                    providerId = "gmail"
                    imapHostField = "imap.gmail.com"
                    imapPortField = "993"
                    smtpHostField = "smtp.gmail.com"
                    smtpPortField = "465"
                }) { Text("＋ Weiteres Konto hinzufügen") }
            } else {
                if (addingAccount) {
                    Text(
                        "Weiteres Konto hinzufügen — „$connectedEmail“ bleibt dabei " +
                            "verbunden und du kannst danach im Ordner-Menü wechseln.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        addingAccount = false
                        email = Prefs.email
                        password = Prefs.appPassword
                        providerId = providerIdFor(Prefs.imapHost)
                        imapHostField = Prefs.imapHost
                        imapPortField = Prefs.imapPort.toString()
                        smtpHostField = Prefs.smtpHost
                        smtpPortField = Prefs.smtpPort.toString()
                    }) { Text("Abbrechen — aktuelles Konto behalten") }
                    Spacer(Modifier.height(4.dp))
                }
                // Google-Anmeldung nur im Entwicklermodus (7-mal auf die
                // Versionsnummer tippen): Ohne Google-Überprüfung funktioniert
                // sie ausschließlich für eingetragene Testnutzer — normale
                // Nutzer würden nur eine Google-Fehlermeldung sehen.
                if (devMode) {
                    Button(
                        onClick = {
                            try {
                                authLauncher.launch(
                                    authService.getAuthorizationRequestIntent(GoogleAuth.buildAuthRequest())
                                )
                            } catch (e: Exception) {
                                scope.launch { snackbar.showSnackbar("Konnte Anmeldung nicht starten: ${e.message}") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mit Google anmelden")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Es öffnet sich das Google-Fenster, in dem du dein Konto auswählst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    if (devMode) "Alternative: manuell mit Anbieter & App-Passwort"
                    else "Manuell mit Anbieter & App-Passwort",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Anbieter-Auswahl: setzt die IMAP-/SMTP-Server automatisch
                Box {
                    OutlinedTextField(
                        value = mailProviders.firstOrNull { it.id == providerId }?.label ?: "Eigenes (IMAP)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Anbieter") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Unsichtbare Klickfläche über dem schreibgeschützten Feld
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { providerMenuOpen = true }
                    )
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false }
                    ) {
                        mailProviders.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    providerMenuOpen = false
                                    providerId = p.id
                                    if (p.imap.isNotBlank()) {
                                        imapHostField = p.imap
                                        imapPortField = p.imapPort.toString()
                                        smtpHostField = p.smtp
                                        smtpPortField = p.smtpPort.toString()
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                    label = { Text("Passwort / App-Passwort") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (providerId == "custom") {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedTextField(
                            value = imapHostField,
                            onValueChange = { imapHostField = it },
                            label = { Text("IMAP-Server") },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = imapPortField,
                            onValueChange = { imapPortField = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedTextField(
                            value = smtpHostField,
                            onValueChange = { smtpHostField = it },
                            label = { Text("SMTP-Server") },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = smtpPortField,
                            onValueChange = { smtpPortField = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
                        )
                    }
                    Text(
                        "SMTP-Port 465 = TLS, 587 = STARTTLS (wird automatisch passend verwendet).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (providerId == "gmail") {
                    TextButton(onClick = { uriHandler.openUri("https://myaccount.google.com/apppasswords") }) {
                        Text("App-Passwort bei Google erstellen")
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            }

            SectionCard(
                "Konten", Icons.Filled.People,
                subtitle = "Deine Postfächer: Farbe und sichtbare Ordner je Konto"
            ) {
            Text(
                "Zwischen den Konten wechselst du oben im Posteingang über das " +
                    "Ordner-Menü. „Farbe wählen“ gibt dem Konto eine eigene Farbe — " +
                    "sie erscheint als Balken vorne an jeder Mail dieses Kontos. " +
                    "„Sichtbare Ordner“ legt fest, welche Ordner das Konto im " +
                    "Ordner-Menü zeigt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            val colorsVersion by Prefs.accountColorsFlow.collectAsState()
            var colorPickerFor by remember { mutableStateOf<String?>(null) }
            colorPickerFor?.let { accEmail ->
                AlertDialog(
                    onDismissRequest = { colorPickerFor = null },
                    title = { Text("Farbe für dieses Konto") },
                    text = {
                        Column {
                            Text(
                                accEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            accountPalette.chunked(4).forEach { rowColors ->
                                Row {
                                    rowColors.forEach { c ->
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .padding(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(c))
                                                .clickable {
                                                    Prefs.setAccountColor(accEmail, c)
                                                    colorPickerFor = null
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            Prefs.setAccountColor(accEmail, null)
                            colorPickerFor = null
                        }) { Text("Keine Farbe") }
                    },
                    dismissButton = {
                        TextButton(onClick = { colorPickerFor = null }) { Text("Abbrechen") }
                    }
                )
            }
            // Sichtbare Ordner je Konto (abgewählte verschwinden aus dem Ordner-Menü)
            var folderPickerFor by remember { mutableStateOf<String?>(null) }
            val hiddenVersion by Prefs.hiddenFoldersFlow.collectAsState()
            folderPickerFor?.let { accEmail ->
                val hidden = remember(hiddenVersion, accEmail) { Prefs.hiddenFolders(accEmail) }
                AlertDialog(
                    onDismissRequest = { folderPickerFor = null },
                    title = { Text("Sichtbare Ordner") },
                    text = {
                        Column {
                            Text(
                                accEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Abgewählte Ordner erscheinen nicht im Ordner-Menü dieses " +
                                    "Kontos. Der Posteingang ist immer sichtbar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            MailRepository.MailFolder.entries
                                .filter { it != MailRepository.MailFolder.INBOX }
                                .forEach { f ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        androidx.compose.material3.Checkbox(
                                            checked = f.name !in hidden,
                                            onCheckedChange = { show ->
                                                val newHidden =
                                                    if (show) hidden - f.name else hidden + f.name
                                                Prefs.setHiddenFolders(accEmail, newHidden)
                                                // Wird der gerade geöffnete Ordner ausgeblendet,
                                                // zurück in den Posteingang wechseln
                                                if (!show &&
                                                    accEmail.equals(Prefs.email, ignoreCase = true) &&
                                                    MailRepository.currentFolder.value == f
                                                ) {
                                                    scope.launch {
                                                        MailRepository.switchFolder(
                                                            MailRepository.MailFolder.INBOX
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                        Text(f.label, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { folderPickerFor = null }) { Text("Fertig") }
                    }
                )
            }
            accountList.forEach { acc ->
                val active = acc.email.equals(Prefs.email, ignoreCase = true)
                val dotColor = remember(colorsVersion, acc.email) {
                    Prefs.accountColor(acc.email)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Farbpunkt zeigt die aktuell gewählte Konto-Farbe
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    dotColor?.let { Color(it) }
                                        ?: MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (active) "${acc.email} (aktiv)" else acc.email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (!active) {
                            IconButton(onClick = {
                                Prefs.removeAccount(acc.email)
                                accountList = Prefs.accounts()
                            }) {
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Konto entfernen",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Klar beschriftete Aktionen statt kleiner Symbole
                    Row(
                        modifier = Modifier.padding(start = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { colorPickerFor = acc.email },
                            label = { Text("Farbe wählen") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Palette,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        AssistChip(
                            onClick = { folderPickerFor = acc.email },
                            label = { Text("Sichtbare Ordner") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Standard-Absender für neue Mails
            if (accountList.size > 1) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                SectionTitle("Standard-Absender")
                Text(
                    "Neue Mails werden über dieses Konto geschrieben (im " +
                        "Verfassen-Fenster jederzeit umschaltbar). Antworten gehen " +
                        "immer über das Konto, in dem die Mail ankam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                var defaultSender by remember { mutableStateOf(Prefs.defaultSendAccount) }
                var senderMenuOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Neue Mails senden über",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { senderMenuOpen = true }
                        ) {
                            Text(
                                defaultSender.ifBlank { "Aktives Konto" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = senderMenuOpen,
                            onDismissRequest = { senderMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Aktives Konto") },
                                onClick = {
                                    senderMenuOpen = false
                                    defaultSender = ""
                                    Prefs.defaultSendAccount = ""
                                }
                            )
                            accountList.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc.email) },
                                    onClick = {
                                        senderMenuOpen = false
                                        defaultSender = acc.email
                                        Prefs.defaultSendAccount = acc.email
                                    }
                                )
                            }
                        }
                    }
                }
            }

            }

            SectionCard(
                "Echtzeit-Push", Icons.Filled.Sync,
                subtitle = "Sofortige Benachrichtigungen bei neuen Mails"
            ) {
            val pushStatus by MailSyncService.pushStatus.collectAsState()
            Text(
                pushStatus,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = {
                    MailSyncService.restart(context)
                    scope.launch { snackbar.showSnackbar("Push-Dienst neu gestartet") }
                }) { Text("Dienst neu starten") }
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                if (pm?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (e: Exception) {
                            scope.launch { snackbar.showSnackbar("Bitte manuell in den Akku-Einstellungen freigeben") }
                        }
                    }) { Text("Akku-Ausnahme erteilen") }
                }
            }
            Text(
                "Tipp: Die Akku-Ausnahme verhindert, dass Android die Push-Verbindung im Standby trennt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // Sparmodus: kein Dauer-Dienst, dafür Prüfung alle ~15 Minuten
            val pushMode by Prefs.pushModeFlow.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sparmodus — ohne Dauer-Benachrichtigung",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Statt der permanenten Verbindung prüft BlockMail alle " +
                            "~15 Minuten auf neue Mails. Die stille Dienst-Meldung " +
                            "entfällt; Benachrichtigungen können sich um bis zu " +
                            "15 Minuten verzögern.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = pushMode == "eco",
                    onCheckedChange = { eco ->
                        Prefs.pushMode = if (eco) "eco" else "push"
                        if (eco) {
                            context.stopService(
                                android.content.Intent(context, MailSyncService::class.java)
                            )
                            scope.launch {
                                snackbar.showSnackbar("Sparmodus aktiv — Prüfung alle ~15 Minuten")
                            }
                        } else {
                            MailSyncService.start(context)
                            scope.launch {
                                snackbar.showSnackbar("Echtzeit-Push wieder aktiv")
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SectionTitle("Knöpfe in der Benachrichtigung")
            Text(
                "Welche Aktionen bei einer neuen Mail direkt in der " +
                    "Benachrichtigung erscheinen. Android zeigt höchstens 3 an.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val notifActions by Prefs.notifActionsFlow.collectAsState()
            listOf(
                "reply" to "Antworten (mit Direkteingabe)",
                "read" to "Als gelesen markieren",
                "archive" to "Archivieren",
                "delete" to "Löschen"
            ).forEach { (key, label) ->
                val checked = key in notifActions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            if (on && notifActions.size >= 3) {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        "Maximal 3 Knöpfe möglich — bitte erst einen abwählen"
                                    )
                                }
                            } else {
                                Prefs.notifActions =
                                    if (on) notifActions + key else notifActions - key
                            }
                        }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            val radarOn by Prefs.radarFlow.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Antwort-Radar",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Erinnert einmal täglich an Mails mit offener Frage, die du " +
                            "noch nicht beantwortet hast — und meldet, wenn du selbst " +
                            "seit Tagen auf eine Antwort wartest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = radarOn,
                    onCheckedChange = { Prefs.radarEnabled = it }
                )
            }

            }

            SectionCard(
                "KI-Status", Icons.Filled.AutoAwesome,
                subtitle = "Welche KI gerade aktiv ist und was sie übernimmt"
            ) {
            var deviceAiStatus by remember { mutableStateOf<String?>(null) }
            var aiTestRunning by remember { mutableStateOf(false) }
            val aiEngine by Prefs.aiEngineFlow.collectAsState()
            LaunchedEffect(Unit) {
                deviceAiStatus = com.jakober.klarmail.ai.GeminiNano.statusText()
            }
            val deviceAiUsable = deviceAiStatus?.startsWith("Nicht verfügbar") == false
            val activeAi = when {
                aiEngine == "gemini" ->
                    if (deviceAiUsable) "Geräte-KI (Gemini Nano) — kostenlos, läuft lokal"
                    else "Keine — Geräte-KI auf diesem Gerät nicht verfügbar"
                aiEngine == "claude" ->
                    if (claudeKey.isNotBlank()) "Claude (eigener API-Schlüssel)"
                    else "Keine — API-Schlüssel fehlt"
                claudeKey.isNotBlank() -> "Claude (eigener API-Schlüssel)"
                deviceAiUsable -> "Geräte-KI (Gemini Nano) — kostenlos, läuft lokal"
                else -> "Keine — KI-Funktionen sind ausgeblendet"
            }
            Text(
                "Aktive KI: $activeAi",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "auto" to "Automatisch (Claude, wenn Schlüssel vorhanden — sonst Geräte-KI)",
                "claude" to "Immer Claude (braucht API-Schlüssel)",
                "gemini" to "Immer Geräte-KI (Gemini Nano)"
            ).forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { Prefs.aiEngine = id }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = aiEngine == id,
                        onClick = { Prefs.aiEngine = id }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Geräte-KI (Gemini Nano): ${deviceAiStatus ?: "wird geprüft …"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Mit Claude laufen alle KI-Funktionen inklusive der täglichen " +
                    "Newsletter-Erkennung. Die Geräte-KI übernimmt Zusammenfassen, " +
                    "Antwort entwerfen, Mail formulieren und Rechtschreibprüfung — " +
                    "komplett auf dem Gerät; die Newsletter-Erkennung nutzt dann die " +
                    "Abmelde-Header-Regel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (deviceAiUsable) {
                TextButton(
                    enabled = !aiTestRunning,
                    onClick = {
                        scope.launch {
                            aiTestRunning = true
                            val result = try {
                                com.jakober.klarmail.ai.GeminiNano.selfTest()
                            } catch (e: Exception) {
                                "Test fehlgeschlagen: ${e.message?.take(80)}"
                            }
                            aiTestRunning = false
                            deviceAiStatus = com.jakober.klarmail.ai.GeminiNano.statusText()
                            snackbar.showSnackbar(result)
                        }
                    }
                ) { Text(if (aiTestRunning) "Geräte-KI wird getestet …" else "Geräte-KI jetzt testen") }
            }

            }

            SectionCard(
                "Claude-API-Schlüssel", Icons.Filled.Key,
                subtitle = "Optional: eigener Schlüssel für die Claude-KI"
            ) {
            OutlinedTextField(
                value = claudeKey,
                onValueChange = { claudeKey = it },
                label = { Text("Claude API-Schlüssel") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Optional: Mit einem API-Schlüssel von console.anthropic.com kann Claude " +
                    "E-Mails formulieren, Antworten entwerfen und die Rechtschreibung prüfen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            }

            SectionCard(
                "Newsletter-Aufräumen (KI)", Icons.Filled.Newspaper,
                subtitle = "Werbung landet automatisch im Newsletter-Ordner"
            ) {
            Text(
                "Täglich um 20 Uhr erkennt die KI Newsletter der letzten 24 Stunden und " +
                    "verschiebt sie in den Ordner „Newsletter“. Im Protokoll findest du alle " +
                    "verschobenen Mails samt Abmelde-Links.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = onOpenNewsletterLog) { Text("Protokoll anzeigen") }
                TextButton(
                    enabled = !newsletterRunning,
                    onClick = {
                        scope.launch {
                            newsletterRunning = true
                            newsletterResult = try {
                                com.jakober.klarmail.data.NewsletterCleaner.run(context)
                            } catch (e: Exception) {
                                "Fehler: ${e.message ?: e.javaClass.simpleName}"
                            }
                            newsletterRunning = false
                        }
                    }
                ) { Text(if (newsletterRunning) "Läuft …" else "Jetzt ausführen") }
            }

            }

            SectionCard(
                "Absender-Regeln", Icons.Filled.Block,
                subtitle = "Stumm, blockiert und VIP — wer darf dich stören?"
            ) {
            val muted by Prefs.mutedFlow.collectAsState()
            val blocked by Prefs.blockedFlow.collectAsState()
            val senderSuggestions = remember {
                val set = LinkedHashSet<String>()
                MailRepository.messages.value.forEach {
                    if (it.fromAddress.contains("@")) set.add(it.fromAddress.lowercase())
                }
                Prefs.knownRecipients().keys.forEach { set.add(it) }
                set.toList()
            }
            SenderListSection(
                title = "Stumm geschaltete Absender",
                description = "Mails dieser Absender werden automatisch als gelesen markiert – ohne Benachrichtigung. Sie bleiben im Posteingang.",
                entries = muted,
                suggestions = senderSuggestions,
                onAdd = { Prefs.addMuted(it) },
                onRemove = { Prefs.removeMuted(it) }
            )

            Spacer(Modifier.height(12.dp))
            SenderListSection(
                title = "Blockierte Absender",
                description = "Mails dieser Absender werden nach Ankunft sofort gelöscht – ohne Benachrichtigung.",
                entries = blocked,
                suggestions = senderSuggestions,
                onAdd = { Prefs.addBlocked(it) },
                onRemove = { Prefs.removeBlocked(it) }
            )

            Spacer(Modifier.height(12.dp))
            val vip by Prefs.vipFlow.collectAsState()
            val vipOnly by Prefs.vipOnlyFlow.collectAsState()
            SenderListSection(
                title = "VIP-Absender",
                description = "Deine wichtigsten Absender. Mit dem Schalter unten " +
                    "benachrichtigt BlockMail nur noch bei Mails von ihnen — " +
                    "alles andere kommt lautlos an.",
                entries = vip,
                suggestions = senderSuggestions,
                onAdd = { Prefs.addVip(it) },
                onRemove = { Prefs.removeVip(it) }
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nur VIP-Absender benachrichtigen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Neue Mails anderer Absender erscheinen weiter im Posteingang, " +
                            "aber ohne Benachrichtigung.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = vipOnly,
                    onCheckedChange = { Prefs.vipOnlyNotifications = it }
                )
            }

            }

            SectionCard(
                "Kontakte", Icons.Filled.Contacts,
                subtitle = "Häufige Empfänger — als Vorschläge beim Verfassen"
            ) {
            var contactsVersion by remember { mutableStateOf(0) }
            val knownContacts = remember(contactsVersion) {
                Prefs.knownRecipients().toList().sortedBy { it.first }
            }
            Text(
                "BlockMail merkt sich automatisch, an wen du schreibst, und schlägt " +
                    "diese Adressen beim Verfassen vor. Hier kannst du Kontakte " +
                    "ergänzen oder entfernen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            var newContactAddr by remember { mutableStateOf("") }
            var newContactName by remember { mutableStateOf("") }
            OutlinedTextField(
                value = newContactAddr,
                onValueChange = { newContactAddr = it },
                label = { Text("E-Mail-Adresse") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newContactName,
                    onValueChange = { newContactName = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = newContactAddr.contains("@"),
                    onClick = {
                        Prefs.addKnownRecipients(
                            listOf(newContactAddr.trim() to newContactName.trim())
                        )
                        newContactAddr = ""
                        newContactName = ""
                        contactsVersion++
                    }
                ) { Text("Hinzufügen") }
            }
            Spacer(Modifier.height(8.dp))
            if (knownContacts.isEmpty()) {
                Text(
                    "Noch keine Kontakte — sie entstehen automatisch beim Senden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                knownContacts.forEach { (address, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SenderAvatar(
                            name = name.ifBlank { address },
                            address = address,
                            size = 34.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (name.isNotBlank()) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            Prefs.removeKnownRecipient(address)
                            contactsVersion++
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Kontakt entfernen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }

            SectionCard(
                "Posteingang", Icons.Filled.Inbox,
                subtitle = "Darstellung, Konversationen und Wischgesten"
            ) {
            SectionTitle("Darstellung")
            val inboxLayout by Prefs.inboxLayoutFlow.collectAsState()
            listOf(
                Triple("list", "Liste (klassisch)", "Mails untereinander, wie gewohnt."),
                Triple(
                    "blocks", "Blöcke (BlockMail-Stil)",
                    "Mails als gleich große Blöcke im 2-Spalten-Raster mit Vorschautext " +
                        "— passend zum Logo."
                ),
                Triple(
                    "blocks3", "Kompakte Blöcke (3 Spalten)",
                    "Noch mehr Mails auf einen Blick: kleinere Blöcke ohne Vorschautext. " +
                        "Alle Ansichten auch oben im Posteingang durchschaltbar."
                )
            ).forEach { (id, title, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { Prefs.inboxLayout = id },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = inboxLayout == id,
                        onClick = { Prefs.inboxLayout = id }
                    )
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Konversations-Ansicht", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Mails mit gleichem Betreff werden als ein Gespräch gebündelt " +
                            "(antippen zum Aufklappen) — in Liste und Kachel-Ansicht.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = conversationView,
                    onCheckedChange = { Prefs.conversationView = it }
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SectionTitle("Wischgesten")
            Text(
                "Lege fest, was beim Wischen einer Mail nach links oder rechts passiert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            val swipeLeftAction by Prefs.swipeLeftFlow.collectAsState()
            val swipeRightAction by Prefs.swipeRightFlow.collectAsState()
            SwipeActionPicker("Nach links wischen", swipeLeftAction) {
                Prefs.swipeLeftAction = it
            }
            SwipeActionPicker("Nach rechts wischen", swipeRightAction) {
                Prefs.swipeRightAction = it
            }

            }

            SectionCard(
                "Signatur & Vorlagen", Icons.Filled.Edit,
                subtitle = "Bausteine fürs Schreiben"
            ) {
            SectionTitle("Signatur")
            Text(
                "Wird beim Verfassen automatisch unter den Text gesetzt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = signatureText,
                onValueChange = {
                    signatureText = it
                    Prefs.signature = it
                },
                label = { Text("Signatur (leer = keine)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("Textvorlagen")
            Text(
                "Wiederverwendbare Texte fürs Verfassen-Fenster (dort über das Vorlagen-Symbol einfügbar).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            templates.forEachIndexed { index, (title, _) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        templates = templates.filterIndexed { i, _ -> i != index }
                        Prefs.saveMailTemplates(templates)
                    }) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Vorlage löschen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (templates.isEmpty()) {
                Text(
                    "Noch keine Vorlagen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showTemplateDialog = true }) { Text("Vorlage hinzufügen") }

            }

            SectionCard(
                "Backup & Umzug", Icons.Filled.ImportExport,
                subtitle = "Einstellungen als Datei sichern und auf einem neuen Gerät einspielen"
            ) {
            Text(
                "Gesichert werden Farben, Darstellung, Wischgesten, Signatur, " +
                    "Vorlagen, Regeln (stumm/blockiert/VIP), Kontakte und " +
                    "Benachrichtigungs-Einstellungen. Zugangsdaten und Passwörter " +
                    "sind aus Sicherheitsgründen NICHT enthalten — Konten müssen " +
                    "auf dem neuen Gerät einmal neu verbunden werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(Prefs.exportSettingsJson().toByteArray())
                        }
                        scope.launch { snackbar.showSnackbar("Sicherung gespeichert") }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbar.showSnackbar("Export fehlgeschlagen: ${e.message}")
                        }
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    try {
                        val json = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().decodeToString() }
                            ?: throw IllegalStateException("Datei nicht lesbar")
                        val count = Prefs.importSettingsJson(json)
                        scope.launch {
                            snackbar.showSnackbar("$count Einstellungen übernommen")
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbar.showSnackbar("Import fehlgeschlagen: ${e.message}")
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    exportLauncher.launch("blockmail-einstellungen.json")
                }) { Text("Sichern") }
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                }) { Text("Sicherung einspielen") }
            }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    // Manuelle Zugangsdaten speichern: im Hinzufügen-Modus auch
                    // dann, wenn gerade ein Google-Konto verbunden ist
                    val manualSave = (addingAccount || !googleConnected) &&
                        email.isNotBlank() && password.isNotBlank()
                    if (manualSave) {
                        Prefs.snapshotActiveAccount()
                        Prefs.email = email
                        Prefs.appPassword = password
                        Prefs.imapHost = imapHostField.trim()
                        Prefs.imapPort = imapPortField.trim().toIntOrNull() ?: 993
                        Prefs.smtpHost = smtpHostField.trim()
                        Prefs.smtpPort = smtpPortField.trim().toIntOrNull() ?: 465
                        Prefs.authMethod = "password"
                        Prefs.snapshotActiveAccount()
                        googleConnected = false
                        addingAccount = false
                        accountList = Prefs.accounts()
                    }
                    Prefs.claudeApiKey = claudeKey
                    if (manualSave) {
                        // Vollständiger Kontowechsel auf das neue Konto
                        scope.launch {
                            MailRepository.switchAccount(
                                Prefs.Account(
                                    Prefs.email, Prefs.authMethod, Prefs.appPassword,
                                    Prefs.refreshToken, Prefs.imapHost, Prefs.imapPort,
                                    Prefs.smtpHost, Prefs.smtpPort
                                )
                            )
                        }
                    } else if (Prefs.isConfigured) {
                        MailSyncService.start(context)
                        scope.launch { MailRepository.refresh() }
                    }
                    scope.launch { snackbar.showSnackbar("Gespeichert") }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Speichern")
            }
            Spacer(Modifier.height(16.dp))
            // 7-mal tippen schaltet den Entwicklermodus um (Google-Anmeldung)
            var versionTaps by remember { mutableStateOf(0) }
            Text(
                "BlockMail Version ${com.jakober.klarmail.BuildConfig.VERSION_NAME}" +
                    if (devMode) " · Entwicklermodus" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        versionTaps++
                        if (versionTaps >= 7) {
                            versionTaps = 0
                            val newState = !Prefs.devMode
                            Prefs.devMode = newState
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (newState) {
                                        "Entwicklermodus aktiviert — Google-Anmeldung sichtbar"
                                    } else {
                                        "Entwicklermodus deaktiviert"
                                    }
                                )
                            }
                        }
                    },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    newsletterResult?.let { result ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newsletterResult = null },
            title = { Text("Newsletter-Aufräumen") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = {
                    newsletterResult = null
                    onOpenNewsletterLog()
                }) { Text("Protokoll öffnen") }
            },
            dismissButton = {
                TextButton(onClick = { newsletterResult = null }) { Text("OK") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SenderListSection(
    title: String,
    description: String,
    entries: Set<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    SectionTitle(title)
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    // Vorhandene Einträge
    entries.forEach { addr ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(addr, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onRemove(addr) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Entfernen",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    if (entries.isEmpty()) {
        Text(
            "Noch keine Einträge.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(8.dp))
    var input by remember { mutableStateOf("") }
    var suggestOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("E-Mail-Adresse") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = input.contains("@"),
            onClick = { onAdd(input); input = "" }
        ) { Text("Hinzufügen") }
    }
    Box {
        TextButton(onClick = { suggestOpen = true }) { Text("Aus bekannten Absendern wählen") }
        androidx.compose.material3.DropdownMenu(
            expanded = suggestOpen,
            onDismissRequest = { suggestOpen = false }
        ) {
            val available = suggestions.filter { it !in entries }
            if (available.isEmpty()) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Keine Vorschläge") },
                    onClick = { suggestOpen = false },
                    enabled = false
                )
            } else {
                available.take(30).forEach { addr ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(addr) },
                        onClick = { onAdd(addr); suggestOpen = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
}

/** Abgerundete Einstellungs-Karte mit Symbol, Titel und optionalem Untertitel. */
@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}
