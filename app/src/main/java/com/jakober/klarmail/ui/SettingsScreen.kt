package com.jakober.klarmail.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ManageSearch
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
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
    val smtpPort: Int,
    val labelRes: Int? = null
)

private val mailProviders = listOf(
    MailProvider("gmail", "Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465),
    MailProvider("webde", "Web.de", "imap.web.de", 993, "smtp.web.de", 587),
    MailProvider("gmx", "GMX", "imap.gmx.net", 993, "mail.gmx.net", 587),
    MailProvider("outlook", "Outlook / Office 365", "outlook.office365.com", 993, "smtp.office365.com", 587),
    MailProvider("custom", "", "", 0, "", 0, labelRes = R.string.settings_provider_custom)
)

private fun providerIdFor(imapHost: String): String =
    mailProviders.firstOrNull { it.imap.isNotBlank() && it.imap.equals(imapHost, ignoreCase = true) }
        ?.id ?: "custom"

@Composable
private fun MailProvider.displayLabel(): String =
    labelRes?.let { stringResource(it) } ?: label

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
    "delete" to R.string.settings_swipe_delete,
    "archive" to R.string.settings_swipe_archive,
    "read" to R.string.settings_swipe_read,
    "snooze" to R.string.settings_swipe_snooze
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
        title = { Text(stringResource(R.string.settings_color_picker_title)) },
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
                Text(stringResource(R.string.settings_color_hue), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f
                )
                Text(stringResource(R.string.settings_color_saturation), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = sat,
                    onValueChange = { sat = it },
                    valueRange = 0f..1f
                )
                Text(stringResource(R.string.settings_color_brightness), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = bright,
                    onValueChange = { bright = it },
                    valueRange = 0f..1f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color.toArgb()) }) { Text(stringResource(R.string.settings_color_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
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
                    stringResource(
                        swipeActionLabels.firstOrNull { it.first == value }?.second
                            ?: R.string.settings_swipe_delete
                    ),
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
                        text = { Text(stringResource(label)) },
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
            title = { Text(stringResource(R.string.settings_template_add)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = tplTitle,
                        onValueChange = { tplTitle = it },
                        label = { Text(stringResource(R.string.settings_template_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tplText,
                        onValueChange = { tplText = it },
                        label = { Text(stringResource(R.string.settings_template_text)) },
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
                ) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text(stringResource(R.string.settings_cancel)) }
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
            snackbar.showSnackbar(
                context.getString(R.string.settings_google_connected_snack, newEmail)
            )
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
                    scope.launch {
                        snackbar.showSnackbar(
                            context.getString(
                                R.string.settings_auth_cancelled,
                                ex.errorDescription ?: ex.error ?: ""
                            )
                        )
                    }
                }
            }
            return@rememberLauncherForActivityResult
        }
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) {
            scope.launch {
                snackbar.showSnackbar(
                    context.getString(
                        R.string.settings_auth_failed,
                        ex?.errorDescription ?: ex?.error
                            ?: context.getString(R.string.settings_unknown)
                    )
                )
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
                        context.getString(
                            R.string.settings_token_exchange_failed,
                            tokenEx?.errorDescription ?: tokenEx?.error
                                ?: context.getString(R.string.settings_unknown)
                        )
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
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
            GroupHeader(stringResource(R.string.settings_group_accounts))
            SectionCard(
                stringResource(R.string.settings_connect_title), Icons.Filled.AccountCircle,
                subtitle = stringResource(R.string.settings_connect_subtitle)
            ) {

            // Empfohlener Weg: Assistent mit fertigen Server-Vorlagen — nur
            // Anbieter wählen, E-Mail und Passwort eingeben.
            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_setup_wizard_start))
            }
            Text(
                stringResource(R.string.settings_setup_wizard_hint),
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
                                stringResource(R.string.settings_google_connected),
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
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(R.string.settings_google_disconnected_snack)
                                )
                            }
                        }) { Text(stringResource(R.string.settings_disconnect)) }
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
                }) { Text(stringResource(R.string.settings_add_account)) }
            } else {
                if (addingAccount) {
                    Text(
                        stringResource(R.string.settings_add_account_hint, connectedEmail),
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
                    }) { Text(stringResource(R.string.settings_add_account_cancel)) }
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
                                scope.launch {
                                    snackbar.showSnackbar(
                                        context.getString(
                                            R.string.settings_google_signin_failed, e.message
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_google_signin))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_google_signin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    if (devMode) stringResource(R.string.settings_manual_alt)
                    else stringResource(R.string.settings_manual),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Anbieter-Auswahl: setzt die IMAP-/SMTP-Server automatisch
                Box {
                    OutlinedTextField(
                        value = mailProviders.firstOrNull { it.id == providerId }?.displayLabel()
                            ?: stringResource(R.string.settings_provider_custom),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_provider)) },
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
                                text = { Text(p.displayLabel()) },
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
                    label = { Text(stringResource(R.string.settings_email_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_password_label)) },
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
                            label = { Text(stringResource(R.string.settings_imap_server)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = imapPortField,
                            onValueChange = { imapPortField = it },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedTextField(
                            value = smtpHostField,
                            onValueChange = { smtpHostField = it },
                            label = { Text(stringResource(R.string.settings_smtp_server)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = smtpPortField,
                            onValueChange = { smtpPortField = it },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
                        )
                    }
                    Text(
                        stringResource(R.string.settings_smtp_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (providerId == "gmail") {
                    TextButton(onClick = { uriHandler.openUri("https://myaccount.google.com/apppasswords") }) {
                        Text(stringResource(R.string.settings_gmail_app_password))
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
                stringResource(R.string.settings_accounts_title), Icons.Filled.People,
                subtitle = stringResource(R.string.settings_accounts_subtitle)
            ) {
            Text(
                stringResource(R.string.settings_accounts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            val colorsVersion by Prefs.accountColorsFlow.collectAsState()
            var colorPickerFor by remember { mutableStateOf<String?>(null) }
            colorPickerFor?.let { accEmail ->
                AlertDialog(
                    onDismissRequest = { colorPickerFor = null },
                    title = { Text(stringResource(R.string.settings_account_color_title)) },
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
                        }) { Text(stringResource(R.string.settings_no_color)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { colorPickerFor = null }) { Text(stringResource(R.string.settings_cancel)) }
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
                    title = { Text(stringResource(R.string.settings_visible_folders)) },
                    text = {
                        Column {
                            Text(
                                accEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_visible_folders_desc),
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
                        TextButton(onClick = { folderPickerFor = null }) { Text(stringResource(R.string.settings_done)) }
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
                            if (active) {
                                stringResource(R.string.settings_account_active, acc.email)
                            } else acc.email,
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
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.settings_account_remove),
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
                            label = { Text(stringResource(R.string.settings_choose_color)) },
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
                            label = { Text(stringResource(R.string.settings_visible_folders)) },
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
                SectionTitle(stringResource(R.string.settings_default_sender))
                Text(
                    stringResource(R.string.settings_default_sender_desc),
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
                        stringResource(R.string.settings_send_new_via),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { senderMenuOpen = true }
                        ) {
                            Text(
                                if (defaultSender.isBlank()) {
                                    stringResource(R.string.settings_active_account)
                                } else defaultSender,
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
                                text = { Text(stringResource(R.string.settings_active_account)) },
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

            GroupHeader(stringResource(R.string.settings_group_appearance))
            SectionCard(
                stringResource(R.string.settings_appearance_title), Icons.Filled.Palette,
                subtitle = stringResource(R.string.settings_appearance_subtitle)
            ) {
            listOf(
                "system" to stringResource(R.string.settings_darkmode_system),
                "light" to stringResource(R.string.settings_darkmode_light),
                "dark" to stringResource(R.string.settings_darkmode_dark)
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
            SectionTitle(stringResource(R.string.settings_color_scheme))
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
                    stringResource(R.string.settings_custom_color),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showColorPicker = true }) { Text(stringResource(R.string.settings_change)) }
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
                stringResource(R.string.settings_inbox_title), Icons.Filled.Inbox,
                subtitle = stringResource(R.string.settings_inbox_subtitle)
            ) {
            SectionTitle(stringResource(R.string.settings_display))
            val inboxLayout by Prefs.inboxLayoutFlow.collectAsState()
            listOf(
                Triple(
                    "list",
                    stringResource(R.string.settings_layout_list_title),
                    stringResource(R.string.settings_layout_list_desc)
                ),
                Triple(
                    "blocks",
                    stringResource(R.string.settings_layout_blocks_title),
                    stringResource(R.string.settings_layout_blocks_desc)
                ),
                Triple(
                    "blocks3",
                    stringResource(R.string.settings_layout_blocks3_title),
                    stringResource(R.string.settings_layout_blocks3_desc)
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
                    Text(
                        stringResource(R.string.settings_conversation_view),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_conversation_view_desc),
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
            SectionTitle(stringResource(R.string.settings_swipe_gestures))
            Text(
                stringResource(R.string.settings_swipe_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            val swipeLeftAction by Prefs.swipeLeftFlow.collectAsState()
            val swipeRightAction by Prefs.swipeRightFlow.collectAsState()
            SwipeActionPicker(stringResource(R.string.settings_swipe_left), swipeLeftAction) {
                Prefs.swipeLeftAction = it
            }
            SwipeActionPicker(stringResource(R.string.settings_swipe_right), swipeRightAction) {
                Prefs.swipeRightAction = it
            }

            }

            GroupHeader(stringResource(R.string.settings_group_notifications))
            SectionCard(
                stringResource(R.string.settings_push_title), Icons.Filled.Sync,
                subtitle = stringResource(R.string.settings_push_subtitle)
            ) {
            val pushStatus by MailSyncService.pushStatus.collectAsState()
            // Der Dienst liefert nur den sprachneutralen Zustand — die
            // Übersetzung passiert hier live in der aktuellen App-Sprache,
            // damit die Statuszeile auch nach einem Sprachwechsel stimmt
            val shownPushStatus = when (pushStatus.kind) {
                MailSyncService.PushKind.NOT_STARTED ->
                    stringResource(R.string.svc_push_not_started)
                MailSyncService.PushKind.NO_ACCOUNT ->
                    stringResource(R.string.svc_no_account)
                MailSyncService.PushKind.NET_CHANGE ->
                    stringResource(R.string.svc_push_net_change, pushStatus.time)
                MailSyncService.PushKind.CONNECTING ->
                    stringResource(R.string.svc_push_connecting, pushStatus.time)
                MailSyncService.PushKind.WAITING ->
                    stringResource(R.string.svc_push_connected_waiting, pushStatus.time)
                MailSyncService.PushKind.PROCESSED ->
                    stringResource(R.string.svc_push_connected_processed, pushStatus.time)
                MailSyncService.PushKind.DISCONNECTED ->
                    stringResource(R.string.svc_push_disconnected_retry, pushStatus.time)
                MailSyncService.PushKind.DISCONNECTED_ERROR ->
                    stringResource(
                        R.string.svc_push_disconnected_error,
                        pushStatus.time,
                        pushStatus.detail.ifBlank { stringResource(R.string.svc_error_generic) },
                        pushStatus.retrySeconds
                    )
                MailSyncService.PushKind.STOPPED ->
                    stringResource(R.string.svc_push_stopped)
            }
            Text(
                shownPushStatus,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = {
                    MailSyncService.restart(context)
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.settings_push_restarted))
                    }
                }) { Text(stringResource(R.string.settings_push_restart)) }
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
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(R.string.settings_battery_manual)
                                )
                            }
                        }
                    }) { Text(stringResource(R.string.settings_battery_exempt)) }
                }
            }
            Text(
                stringResource(R.string.settings_battery_tip),
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
                        stringResource(R.string.settings_eco_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_eco_desc),
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
                                snackbar.showSnackbar(
                                    context.getString(R.string.settings_eco_on_snack)
                                )
                            }
                        } else {
                            MailSyncService.start(context)
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(R.string.settings_push_on_snack)
                                )
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SectionTitle(stringResource(R.string.settings_notif_buttons))
            Text(
                stringResource(R.string.settings_notif_buttons_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val notifActions by Prefs.notifActionsFlow.collectAsState()
            val actionLabels = mapOf(
                "reply" to stringResource(R.string.settings_notif_reply),
                "read" to stringResource(R.string.settings_notif_read),
                "archive" to stringResource(R.string.settings_swipe_archive),
                "delete" to stringResource(R.string.settings_swipe_delete)
            )
            // Gewählte zuerst — in gespeicherter Reihenfolge (= Reihenfolge
            // in der Benachrichtigung), danach die abgewählten
            val orderedActions = notifActions +
                listOf("reply", "read", "archive", "delete").filter { it !in notifActions }
            var draggingAction by remember { mutableStateOf<String?>(null) }
            var dragOffsetY by remember { mutableStateOf(0f) }
            val actionRowHeight = 52.dp
            val actionRowPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                actionRowHeight.toPx()
            }
            fun moveAction(key: String, dir: Int) {
                val list = Prefs.notifActions.toMutableList()
                val i = list.indexOf(key)
                val j = i + dir
                if (i != -1 && j in list.indices) {
                    val tmp = list[j]
                    list[j] = list[i]
                    list[i] = tmp
                    Prefs.notifActions = list
                }
            }
            orderedActions.forEach { key ->
                val checked = key in notifActions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(actionRowHeight)
                        .graphicsLayer {
                            translationY = if (draggingAction == key) dragOffsetY else 0f
                        }
                        .zIndex(if (draggingAction == key) 1f else 0f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            if (on && notifActions.size >= 3) {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        context.getString(R.string.settings_notif_max3)
                                    )
                                }
                            } else {
                                Prefs.notifActions =
                                    if (on) notifActions + key else notifActions - key
                            }
                        }
                    )
                    Text(
                        actionLabels[key] ?: key,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (checked) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.settings_notif_drag),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.pointerInput(key) {
                                detectVerticalDragGestures(
                                        onDragStart = {
                                            draggingAction = key
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggingAction = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingAction = null
                                            dragOffsetY = 0f
                                        }
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffsetY += amount
                                        while (dragOffsetY > actionRowPx * 0.6f) {
                                            moveAction(key, 1)
                                            dragOffsetY -= actionRowPx
                                        }
                                        while (dragOffsetY < -actionRowPx * 0.6f) {
                                            moveAction(key, -1)
                                            dragOffsetY += actionRowPx
                                        }
                                    }
                            }
                        )
                    }
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
                        stringResource(R.string.settings_radar_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_radar_desc),
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

            GroupHeader(stringResource(R.string.settings_group_ai))
            SectionCard(
                stringResource(R.string.settings_ai_status_title), Icons.Filled.AutoAwesome,
                subtitle = stringResource(R.string.settings_ai_status_subtitle)
            ) {
            var deviceAiStatus by remember { mutableStateOf<String?>(null) }
            var aiTestRunning by remember { mutableStateOf(false) }
            val aiEngine by Prefs.aiEngineFlow.collectAsState()
            LaunchedEffect(Unit) {
                deviceAiStatus = com.jakober.klarmail.ai.GeminiNano.statusText()
            }
            val deviceAiUsable = deviceAiStatus?.let {
                !it.startsWith("Nicht verfügbar") && !it.startsWith("Not available")
            } == true
            val activeAi = when {
                aiEngine == "gemini" ->
                    if (deviceAiUsable) stringResource(R.string.settings_ai_gemini_active)
                    else stringResource(R.string.settings_ai_none_gemini)
                aiEngine == "claude" ->
                    if (claudeKey.isNotBlank()) stringResource(R.string.settings_ai_claude_active)
                    else stringResource(R.string.settings_ai_none_key)
                claudeKey.isNotBlank() -> stringResource(R.string.settings_ai_claude_active)
                deviceAiUsable -> stringResource(R.string.settings_ai_gemini_active)
                else -> stringResource(R.string.settings_ai_none_hidden)
            }
            Text(
                stringResource(R.string.settings_ai_active, activeAi),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "auto" to stringResource(R.string.settings_ai_mode_auto),
                "claude" to stringResource(R.string.settings_ai_mode_claude),
                "gemini" to stringResource(R.string.settings_ai_mode_gemini)
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
                stringResource(
                    R.string.settings_ai_device_status,
                    deviceAiStatus ?: stringResource(R.string.settings_ai_checking)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_ai_desc),
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
                                context.getString(
                                    R.string.settings_ai_test_failed, e.message?.take(80)
                                )
                            }
                            aiTestRunning = false
                            deviceAiStatus = com.jakober.klarmail.ai.GeminiNano.statusText()
                            snackbar.showSnackbar(result)
                        }
                    }
                ) {
                    Text(
                        if (aiTestRunning) stringResource(R.string.settings_ai_testing)
                        else stringResource(R.string.settings_ai_test_now)
                    )
                }
            }

            }

            SectionCard(
                stringResource(R.string.settings_claude_key_title), Icons.Filled.Key,
                subtitle = stringResource(R.string.settings_claude_key_subtitle)
            ) {
            OutlinedTextField(
                value = claudeKey,
                onValueChange = { claudeKey = it },
                label = { Text(stringResource(R.string.settings_claude_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_claude_key_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            }

            SectionCard(
                stringResource(R.string.settings_newsletter_title), Icons.Filled.Newspaper,
                subtitle = stringResource(R.string.settings_newsletter_subtitle)
            ) {
            val newsletterAuto by Prefs.newsletterAutoFlow.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_newsletter_daily),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_newsletter_daily_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = newsletterAuto,
                    onCheckedChange = { on ->
                        Prefs.newsletterAutoEnabled = on
                        scope.launch {
                            snackbar.showSnackbar(
                                if (on) context.getString(R.string.settings_newsletter_on_snack)
                                else context.getString(R.string.settings_newsletter_off_snack)
                            )
                        }
                    }
                )
            }
            Row {
                TextButton(onClick = onOpenNewsletterLog) { Text(stringResource(R.string.settings_newsletter_log)) }
                TextButton(
                    enabled = !newsletterRunning,
                    onClick = {
                        scope.launch {
                            newsletterRunning = true
                            newsletterResult = try {
                                com.jakober.klarmail.data.NewsletterCleaner.run(context)
                            } catch (e: Exception) {
                                context.getString(
                                    R.string.settings_error_prefix,
                                    e.message ?: e.javaClass.simpleName
                                )
                            }
                            newsletterRunning = false
                        }
                    }
                ) {
                    Text(
                        if (newsletterRunning) stringResource(R.string.settings_newsletter_running)
                        else stringResource(R.string.settings_newsletter_run_now)
                    )
                }
            }

            }

            GroupHeader(stringResource(R.string.settings_group_rules))
            SectionCard(
                stringResource(R.string.settings_sender_rules_title), Icons.Filled.Block,
                subtitle = stringResource(R.string.settings_sender_rules_subtitle)
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
                title = stringResource(R.string.settings_muted_title),
                description = stringResource(R.string.settings_muted_desc),
                entries = muted,
                suggestions = senderSuggestions,
                onAdd = { Prefs.addMuted(it) },
                onRemove = { Prefs.removeMuted(it) }
            )

            Spacer(Modifier.height(12.dp))
            SenderListSection(
                title = stringResource(R.string.settings_blocked_title),
                description = stringResource(R.string.settings_blocked_desc),
                entries = blocked,
                suggestions = senderSuggestions,
                onAdd = { Prefs.addBlocked(it) },
                onRemove = { Prefs.removeBlocked(it) }
            )

            Spacer(Modifier.height(12.dp))
            val vip by Prefs.vipFlow.collectAsState()
            val vipOnly by Prefs.vipOnlyFlow.collectAsState()
            SenderListSection(
                title = stringResource(R.string.settings_vip_title),
                description = stringResource(R.string.settings_vip_desc),
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
                        stringResource(R.string.settings_vip_only),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_vip_only_desc),
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
                stringResource(R.string.settings_contacts_title), Icons.Filled.Contacts,
                subtitle = stringResource(R.string.settings_contacts_subtitle)
            ) {
            var contactsVersion by remember { mutableStateOf(0) }
            val knownContacts = remember(contactsVersion) {
                Prefs.knownRecipients().toList().sortedBy { it.first }
            }
            Text(
                stringResource(R.string.settings_contacts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            var newContactAddr by remember { mutableStateOf("") }
            var newContactName by remember { mutableStateOf("") }
            OutlinedTextField(
                value = newContactAddr,
                onValueChange = { newContactAddr = it },
                label = { Text(stringResource(R.string.settings_email_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newContactName,
                    onValueChange = { newContactName = it },
                    label = { Text(stringResource(R.string.settings_contact_name)) },
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
                ) { Text(stringResource(R.string.settings_add)) }
            }
            Spacer(Modifier.height(8.dp))
            if (knownContacts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_contacts_empty),
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
                                contentDescription = stringResource(R.string.settings_contact_remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }

            GroupHeader(stringResource(R.string.settings_group_writing))
            SectionCard(
                stringResource(R.string.settings_signature_title), Icons.Filled.Edit,
                subtitle = stringResource(R.string.settings_signature_subtitle)
            ) {
            SectionTitle(stringResource(R.string.settings_signature))
            Text(
                stringResource(R.string.settings_signature_desc),
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
                label = { Text(stringResource(R.string.settings_signature_label)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.settings_templates))
            Text(
                stringResource(R.string.settings_templates_desc),
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
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_template_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (templates.isEmpty()) {
                Text(
                    stringResource(R.string.settings_templates_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showTemplateDialog = true }) { Text(stringResource(R.string.settings_template_add)) }

            }

            GroupHeader(stringResource(R.string.settings_group_data))
            SectionCard(
                stringResource(R.string.settings_index_title), Icons.Filled.ManageSearch,
                subtitle = stringResource(R.string.settings_index_subtitle)
            ) {
            var indexStats by remember {
                mutableStateOf<com.jakober.klarmail.data.MailIndex.IndexStats?>(null)
            }
            var showClearIndexDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                com.jakober.klarmail.data.MailIndex.init(context)
                indexStats = com.jakober.klarmail.data.MailIndex.stats()
            }
            Text(
                stringResource(R.string.settings_index_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            indexStats?.let { stats ->
                Text(
                    if (stats.mailCount == 0) stringResource(R.string.settings_index_empty)
                    else stringResource(
                        R.string.settings_index_status, stats.mailCount, formatDbSize(stats.dbBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
            }
            val indexOn by Prefs.indexEnabledFlow.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_index_auto),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = indexOn,
                    onCheckedChange = { on -> Prefs.indexEnabled = on }
                )
            }
            TextButton(onClick = { showClearIndexDialog = true }) {
                Text(stringResource(R.string.settings_index_clear))
            }
            if (showClearIndexDialog) {
                AlertDialog(
                    onDismissRequest = { showClearIndexDialog = false },
                    title = { Text(stringResource(R.string.settings_index_clear_title)) },
                    text = { Text(stringResource(R.string.settings_index_clear_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearIndexDialog = false
                            scope.launch {
                                com.jakober.klarmail.data.MailIndex.clearAll()
                                indexStats = com.jakober.klarmail.data.MailIndex.stats()
                                snackbar.showSnackbar(
                                    context.getString(R.string.settings_index_cleared)
                                )
                            }
                        }) { Text(stringResource(R.string.settings_index_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearIndexDialog = false }) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                    }
                )
            }
            }

            SectionCard(
                stringResource(R.string.settings_backup_title), Icons.Filled.ImportExport,
                subtitle = stringResource(R.string.settings_backup_subtitle)
            ) {
            Text(
                stringResource(R.string.settings_backup_desc),
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
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.settings_backup_saved))
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.settings_export_failed, e.message)
                            )
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
                            ?: throw IllegalStateException(
                                context.getString(R.string.settings_file_unreadable)
                            )
                        val count = Prefs.importSettingsJson(json)
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.settings_import_count, count)
                            )
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.settings_import_failed, e.message)
                            )
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    exportLauncher.launch("blockmail-einstellungen.json")
                }) { Text(stringResource(R.string.settings_backup_export)) }
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                }) { Text(stringResource(R.string.settings_backup_import)) }
            }
            }

            SectionCard(
                stringResource(R.string.settings_feedback_title), Icons.Filled.Feedback,
                subtitle = stringResource(R.string.settings_feedback_subtitle)
            ) {
            var showFeedbackDialog by remember { mutableStateOf(false) }
            var feedbackText by remember { mutableStateOf("") }
            var feedbackSending by remember { mutableStateOf(false) }
            Text(
                stringResource(R.string.settings_feedback_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                if (Prefs.isConfigured) {
                    showFeedbackDialog = true
                } else {
                    scope.launch {
                        snackbar.showSnackbar(
                            context.getString(R.string.settings_feedback_connect_first)
                        )
                    }
                }
            }) { Text(stringResource(R.string.settings_feedback_write)) }
            if (showFeedbackDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { if (!feedbackSending) showFeedbackDialog = false },
                    title = { Text(stringResource(R.string.settings_feedback_title)) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.settings_feedback_dialog_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = feedbackText,
                                onValueChange = { feedbackText = it },
                                placeholder = { Text(stringResource(R.string.settings_feedback_placeholder)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = feedbackText.isNotBlank() && !feedbackSending,
                            onClick = {
                                scope.launch {
                                    feedbackSending = true
                                    try {
                                        MailRepository.send(
                                            to = "mat.jakober@gmail.com",
                                            subject = context.getString(
                                                R.string.settings_feedback_subject,
                                                com.jakober.klarmail.BuildConfig.VERSION_NAME
                                            ),
                                            body = feedbackText.trim() + "\n\n" +
                                                context.getString(
                                                    R.string.settings_feedback_device_info,
                                                    com.jakober.klarmail.BuildConfig.VERSION_NAME,
                                                    android.os.Build.VERSION.RELEASE,
                                                    android.os.Build.MANUFACTURER,
                                                    android.os.Build.MODEL
                                                )
                                        )
                                        showFeedbackDialog = false
                                        feedbackText = ""
                                        snackbar.showSnackbar(
                                            context.getString(R.string.settings_feedback_sent)
                                        )
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(
                                            context.getString(
                                                R.string.settings_feedback_send_failed, e.message
                                            )
                                        )
                                    } finally {
                                        feedbackSending = false
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (feedbackSending) stringResource(R.string.settings_feedback_sending)
                                else stringResource(R.string.settings_feedback_send)
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !feedbackSending,
                            onClick = { showFeedbackDialog = false }
                        ) { Text(stringResource(R.string.settings_cancel)) }
                    }
                )
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
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_saved)) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save))
            }
            Spacer(Modifier.height(16.dp))
            // 7-mal tippen schaltet den Entwicklermodus um (Google-Anmeldung)
            var versionTaps by remember { mutableStateOf(0) }
            Text(
                if (devMode) {
                    stringResource(
                        R.string.settings_version_devmode,
                        com.jakober.klarmail.BuildConfig.VERSION_NAME
                    )
                } else {
                    stringResource(
                        R.string.settings_version,
                        com.jakober.klarmail.BuildConfig.VERSION_NAME
                    )
                },
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
                                        context.getString(R.string.settings_devmode_on)
                                    } else {
                                        context.getString(R.string.settings_devmode_off)
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
            title = { Text(stringResource(R.string.settings_newsletter_dialog_title)) },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = {
                    newsletterResult = null
                    onOpenNewsletterLog()
                }) { Text(stringResource(R.string.settings_newsletter_open_log)) }
            },
            dismissButton = {
                TextButton(onClick = { newsletterResult = null }) { Text(stringResource(R.string.settings_ok)) }
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
                    contentDescription = stringResource(R.string.settings_remove),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.settings_no_entries),
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
            label = { Text(stringResource(R.string.settings_email_address)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = input.contains("@"),
            onClick = { onAdd(input); input = "" }
        ) { Text(stringResource(R.string.settings_add)) }
    }
    Box {
        TextButton(onClick = { suggestOpen = true }) { Text(stringResource(R.string.settings_pick_known)) }
        androidx.compose.material3.DropdownMenu(
            expanded = suggestOpen,
            onDismissRequest = { suggestOpen = false }
        ) {
            val available = suggestions.filter { it !in entries }
            if (available.isEmpty()) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_no_suggestions)) },
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

/** Große Gruppen-Überschrift zwischen den Einstellungs-Karten. */
@Composable
private fun GroupHeader(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** Menschenlesbare Größe der Index-Datenbank (MB mit einer Nachkommastelle). */
private fun formatDbSize(bytes: Long): String =
    if (bytes < 1_000_000) {
        String.format(java.util.Locale.getDefault(), "%d kB", bytes / 1000)
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
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
