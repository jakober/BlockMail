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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import kotlinx.coroutines.launch

/** Anbieter-Voreinstellung für den Einrichtungsassistenten. */
private data class SetupProvider(
    val id: String,
    val label: String,
    val noteRes: Int,
    val helpUrl: String?,
    val helpLabelRes: Int?,
    val imap: String,
    val imapPort: Int,
    val smtp: String,
    val smtpPort: Int,
    val passwordLabelRes: Int
)

private val setupProviders = listOf(
    SetupProvider(
        "gmail", "Gmail",
        R.string.setup_note_gmail,
        "https://myaccount.google.com/apppasswords", R.string.setup_help_gmail,
        "imap.gmail.com", 993, "smtp.gmail.com", 465, R.string.setup_pw_app
    ),
    SetupProvider(
        "webde", "Web.de",
        R.string.setup_note_webde,
        "https://hilfe.web.de/pop-imap/einschalten.html", R.string.setup_help_guide,
        "imap.web.de", 993, "smtp.web.de", 587, R.string.setup_pw_normal
    ),
    SetupProvider(
        "gmx", "GMX",
        R.string.setup_note_gmx,
        "https://hilfe.gmx.net/pop-imap/einschalten.html", R.string.setup_help_guide,
        "imap.gmx.net", 993, "mail.gmx.net", 587, R.string.setup_pw_normal
    ),
    SetupProvider(
        "outlook", "Outlook / Hotmail",
        R.string.setup_note_outlook,
        "https://account.live.com/proofs/AppPassword", R.string.setup_help_outlook,
        "outlook.office365.com", 993, "smtp.office365.com", 587, R.string.setup_pw_outlook
    ),
    SetupProvider(
        "yahoo", "Yahoo Mail",
        R.string.setup_note_yahoo,
        "https://login.yahoo.com/myaccount/security", R.string.setup_help_yahoo,
        "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, R.string.setup_pw_app
    ),
    SetupProvider(
        "tonline", "T-Online",
        R.string.setup_note_tonline,
        "https://email.t-online.de", R.string.setup_help_tonline,
        "secureimap.t-online.de", 993, "securesmtp.t-online.de", 465, R.string.setup_pw_tonline
    ),
    SetupProvider(
        "icloud", "iCloud Mail",
        R.string.setup_note_icloud,
        "https://appleid.apple.com", R.string.setup_help_icloud,
        "imap.mail.me.com", 993, "smtp.mail.me.com", 587, R.string.setup_pw_icloud
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
    val context = LocalContext.current

    var provider by remember { mutableStateOf<SetupProvider?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordAutoFilled by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Abkuerzung fuer App-Passwoerter: Wer bei Google/Yahoo das frisch
    // erzeugte App-Passwort kopiert und zur App zurueckkehrt, bekommt es
    // hier automatisch eingetragen (16 Buchstaben, mit oder ohne
    // Leerzeichen). Der kleine Aufschub ist noetig, weil Android der App
    // die Zwischenablage erst nach dem Fokuswechsel freigibt.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    kotlinx.coroutines.delay(400)
                    if (provider == null || password.isNotBlank()) return@launch
                    val clip = runCatching {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip?.getItemAt(0)?.text?.toString()
                    }.getOrNull()?.trim().orEmpty()
                    val compact = clip.replace(" ", "")
                    if (Regex("^[a-zA-Z]{16}$").matches(compact)) {
                        password = compact
                        passwordAutoFilled = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider?.label ?: stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (provider != null) {
                            provider = null
                            error = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.setup_back)
                        )
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
                    stringResource(R.string.setup_choose_provider),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.setup_choose_provider_desc),
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
                    Text(stringResource(R.string.setup_other_provider))
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(stringResource(p.noteRes), style = MaterialTheme.typography.bodyMedium)
                        p.helpUrl?.let { url ->
                            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                                Text(stringResource(p.helpLabelRes ?: R.string.setup_help_open))
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
                    label = { Text(stringResource(R.string.setup_email_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; passwordAutoFilled = false },
                    label = { Text(stringResource(p.passwordLabelRes)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (passwordAutoFilled) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.setup_pw_autofilled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
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
                                error = context.getString(R.string.setup_connection_failed, err)
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
                        Text(stringResource(R.string.setup_testing))
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_connect))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
