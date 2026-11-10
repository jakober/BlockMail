package com.jakober.klarmail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import kotlinx.coroutines.launch

/** Anbieter-Voreinstellungen für das Konto-Popup ("" = eigene Server). */
private data class DlgProvider(
    val id: String,
    val label: String,
    val imap: String,
    val imapPort: Int,
    val smtp: String,
    val smtpPort: Int
)

private val dlgProviders = listOf(
    DlgProvider("gmail", "Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465),
    DlgProvider("webde", "Web.de", "imap.web.de", 993, "smtp.web.de", 587),
    DlgProvider("gmx", "GMX", "imap.gmx.net", 993, "mail.gmx.net", 587),
    DlgProvider(
        "outlook", "Outlook / Hotmail",
        "outlook.office365.com", 993, "smtp.office365.com", 587
    ),
    DlgProvider(
        "yahoo", "Yahoo Mail",
        "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465
    ),
    DlgProvider(
        "tonline", "T-Online",
        "secureimap.t-online.de", 993, "securesmtp.t-online.de", 465
    ),
    DlgProvider("icloud", "iCloud Mail", "imap.mail.me.com", 993, "smtp.mail.me.com", 587),
    DlgProvider("custom", "", "", 993, "", 587)
)

/**
 * Eigenständiges Popup „Konto hinzufügen“: Anbieter wählen (oder eigene
 * Server eintragen), Zugangsdaten samt optionalem abweichendem
 * Benutzernamen eingeben, Verbindung testen — erst bei Erfolg wird das
 * Konto gespeichert und aktiviert. Ersetzt die frühere Bearbeitung „in
 * den Feldern des aktiven Kontos“, die ohne sichtbaren Speichern-Knopf
 * verwirrte.
 *
 * Mit [edit] bearbeitet dasselbe Popup die Zugangsdaten eines bereits
 * gespeicherten Kontos — auch eines gerade NICHT aktiven: Die Felder sind
 * vorbelegt, die Adresse bleibt fest (sie ist der Schlüssel des Kontos),
 * und nach bestandenem Verbindungstest werden nur dessen Daten ersetzt;
 * das aktive Konto wechselt dabei nicht.
 */
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    edit: Prefs.Account? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bearbeiten: Passt alles zu einer Voreinstellung, gilt der Anbieter —
    // sonst "Eigener Server", damit abweichende Hosts/Ports erhalten bleiben
    val editPreset = remember(edit) {
        edit?.let { acc ->
            dlgProviders.firstOrNull {
                it.id != "custom" && it.imap.equals(acc.imapHost, ignoreCase = true) &&
                    it.imapPort == acc.imapPort &&
                    it.smtp.equals(acc.smtpHost, ignoreCase = true) &&
                    it.smtpPort == acc.smtpPort
            }?.id ?: "custom"
        }
    }
    var providerId by remember { mutableStateOf(editPreset ?: "gmail") }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(edit?.email.orEmpty()) }
    var password by remember { mutableStateOf(edit?.appPassword.orEmpty()) }
    var loginUser by remember { mutableStateOf(edit?.loginUser.orEmpty()) }
    var imapHost by remember { mutableStateOf(edit?.imapHost.orEmpty()) }
    var imapPort by remember { mutableStateOf((edit?.imapPort ?: 993).toString()) }
    var smtpHost by remember { mutableStateOf(edit?.smtpHost.orEmpty()) }
    var smtpPort by remember { mutableStateOf((edit?.smtpPort ?: 587).toString()) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val customLabel = stringResource(R.string.settings_provider_custom)
    fun labelOf(id: String): String =
        dlgProviders.firstOrNull { it.id == id }?.label?.ifBlank { customLabel }
            ?: customLabel

    fun resolvedImapHost(): String =
        if (providerId == "custom") imapHost.trim()
        else dlgProviders.first { it.id == providerId }.imap

    fun resolvedImapPort(): Int =
        if (providerId == "custom") imapPort.trim().toIntOrNull() ?: 993
        else dlgProviders.first { it.id == providerId }.imapPort

    fun connectAndSave() {
        if (testing) return
        scope.launch {
            testing = true
            error = null
            val err = MailRepository.testConnection(
                email, password, resolvedImapHost(), resolvedImapPort(),
                loginUser = loginUser.trim()
            )
            if (err == null && edit != null) {
                val p = dlgProviders.first { it.id == providerId }
                // Nur die Daten DIESES Kontos ersetzen — kein Kontowechsel
                Prefs.updateAccount(
                    edit.copy(
                        authMethod = "password",
                        appPassword = password,
                        imapHost = resolvedImapHost(),
                        imapPort = resolvedImapPort(),
                        smtpHost = if (providerId == "custom") smtpHost.trim() else p.smtp,
                        smtpPort =
                            if (providerId == "custom") smtpPort.trim().toIntOrNull() ?: 587
                            else p.smtpPort,
                        loginUser = loginUser.trim()
                    )
                )
                // Push-Verbindungen mit den neuen Daten neu aufbauen
                com.jakober.klarmail.service.MailSyncService.restart(context)
                testing = false
                onDone()
            } else if (err == null) {
                val p = dlgProviders.first { it.id == providerId }
                // Bisheriges Konto sichern, neues aktivieren (wie im
                // Einrichtungsassistenten)
                Prefs.snapshotActiveAccount()
                Prefs.email = email.trim()
                Prefs.appPassword = password
                Prefs.imapHost = resolvedImapHost()
                Prefs.imapPort = resolvedImapPort()
                Prefs.smtpHost =
                    if (providerId == "custom") smtpHost.trim() else p.smtp
                Prefs.smtpPort =
                    if (providerId == "custom") smtpPort.trim().toIntOrNull() ?: 587
                    else p.smtpPort
                Prefs.authMethod = "password"
                Prefs.loginUser = loginUser.trim()
                Prefs.refreshToken = ""
                Prefs.snapshotActiveAccount()
                MailRepository.switchAccount(
                    Prefs.Account(
                        Prefs.email, Prefs.authMethod, Prefs.appPassword,
                        Prefs.refreshToken, Prefs.imapHost, Prefs.imapPort,
                        Prefs.smtpHost, Prefs.smtpPort,
                        loginUser = Prefs.loginUser
                    )
                )
                testing = false
                onDone()
            } else {
                testing = false
                error = context.getString(R.string.setup_connection_failed, err)
            }
        }
    }

    AlertDialog(
        // Eingaben duerfen nicht durch ein versehentliches Tippen NEBEN das
        // Popup (oder die Zurueck-Geste) verloren gehen: Schliessen geht
        // ausschliesslich ueber den Abbrechen-Knopf
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        onDismissRequest = { },
        title = {
            Text(
                stringResource(
                    if (edit != null) R.string.settings_edit_connection
                    else R.string.add_account_title
                )
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedTextField(
                        value = labelOf(providerId),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_provider)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { providerMenuOpen = true }
                    )
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false }
                    ) {
                        dlgProviders.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label.ifBlank { customLabel }) },
                                onClick = {
                                    providerMenuOpen = false
                                    providerId = p.id
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
                    // Beim Bearbeiten fest: Die Adresse ist der Schlüssel des
                    // Kontos (Farbe, Ordner, Merkliste hängen daran)
                    enabled = edit == null,
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
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = loginUser,
                    onValueChange = { loginUser = it },
                    label = { Text(stringResource(R.string.setup_login_user)) },
                    supportingText = {
                        Text(stringResource(R.string.setup_login_user_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (providerId == "custom") {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedTextField(
                            value = imapHost,
                            onValueChange = { imapHost = it },
                            label = { Text(stringResource(R.string.settings_imap_server)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = imapPort,
                            onValueChange = { imapPort = it },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedTextField(
                            value = smtpHost,
                            onValueChange = { smtpHost = it },
                            label = { Text(stringResource(R.string.settings_smtp_server)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = smtpPort,
                            onValueChange = { smtpPort = it },
                            label = { Text(stringResource(R.string.settings_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.3f)
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
                if (testing) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.setup_testing))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing && email.contains("@") && password.isNotBlank() &&
                    (providerId != "custom" ||
                        (imapHost.isNotBlank() && smtpHost.isNotBlank())),
                onClick = { connectAndSave() }
            ) { Text(stringResource(R.string.add_account_connect)) }
        },
        dismissButton = {
            TextButton(enabled = !testing, onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}
