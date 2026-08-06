package com.jakober.klarmail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.AttachmentEditing
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import com.jakober.klarmail.document.R as DocR

/**
 * Einstieg von außen: BlockMail als Betrachter und Editor für PDFs und
 * Bilder — aus dem Dateimanager (Öffnen/Bearbeiten) und aus dem Teilen-Blatt
 * anderer Apps (WhatsApp & Co.).
 *
 * Bewusst eine EIGENE Activity statt MainActivity, aus drei Gründen:
 * MainActivity ist `singleTop` (das Dokument käme per onNewIntent an und
 * Zurück landete im Posteingang), beim Hochholen sähe man kurz fremde
 * Betreffzeilen, und `finish()` ist der einzige saubere Rückweg zur
 * aufrufenden App. `taskAffinity=""` hält die Dokument-Aufgabe von der
 * Mail-Aufgabe getrennt — beide erscheinen einzeln im App-Umschalter.
 */
class ViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val source = sourceFromIntent(intent)

        setContent {
            val scheme by Prefs.colorSchemeFlow.collectAsState()
            val darkMode by Prefs.darkModeFlow.collectAsState()
            com.jakober.klarmail.ui.KlarMailTheme(schemeId = scheme, darkMode = darkMode) {
                val isPro by com.jakober.klarmail.data.ProAccess.isProFlow.collectAsState()
                when {
                    // Betreiber-Entscheidung: ohne Abo auch kein Ansehen
                    // (umschaltbar ueber ProAccess.DOCUMENTS_VIEW_FREE)
                    !(com.jakober.klarmail.data.ProAccess.DOCUMENTS_VIEW_FREE || isPro) ->
                        LockedScreen()
                    source == null -> MissingScreen()
                    else -> com.jakober.klarmail.ui.AttachmentEditorScreen(
                        source = source,
                        onBack = { finish() },
                        onSend = {
                            // Verfassen-Fenster gehoert in die Mail-Aufgabe:
                            // explizit und mit NEW_TASK, sonst hinge der
                            // Posteingang in der Dokument-Aufgabe fest
                            startActivity(
                                Intent(this@ViewerActivity, MainActivity::class.java)
                                    .setAction(ACTION_COMPOSE_ATTACH)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    )
                            )
                            finish()
                        }
                    )
                }
            }
        }
    }

    /** Baut aus dem ankommenden Intent die Editor-Quelle. */
    private fun sourceFromIntent(intent: Intent?): AttachmentEditing.Source? {
        intent ?: return null
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> intent.data
        } ?: return null

        // Anzeigename: erst den Anbieter fragen, dann auf den Pfad zurueckfallen
        val name = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokument.pdf"

        // WhatsApp & Co. deklarieren gern application/octet-stream —
        // effectiveMime schaut dann auf die Dateiendung
        val declared = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val mime = MailRepository.effectiveMime(name, declared ?: "")
        if (!AttachmentEditing.isEditable(mime, name)) return null

        val origin = when (intent.action) {
            Intent.ACTION_EDIT -> AttachmentEditing.Origin.EXTERNAL_EDIT
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                AttachmentEditing.Origin.EXTERNAL_SHARE
            else -> AttachmentEditing.Origin.EXTERNAL_VIEW
        }
        // Ueberschreiben nur, wenn der Aufrufer Schreibrecht mitgegeben hat —
        // sonst glaubte der Nutzer, seine Datei sei geaendert
        val canWrite = origin == AttachmentEditing.Origin.EXTERNAL_EDIT &&
            (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        return AttachmentEditing.Source(
            name = name,
            mime = mime,
            bytes = null,
            replyUid = null,
            uri = uri,
            origin = origin,
            canOverwrite = canWrite
        )
    }

    @androidx.compose.runtime.Composable
    private fun LockedScreen() {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.editor_locked_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.editor_locked_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    startActivity(
                        Intent(this@ViewerActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                }) { Text(stringResource(R.string.editor_open_app)) }
                TextButton(onClick = { finish() }) {
                    Text(stringResource(DocR.string.editor_cancel))
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun MissingScreen() {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(DocR.string.editor_open_failed),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { finish() }) {
                    Text(stringResource(DocR.string.editor_back))
                }
            }
        }
    }

    companion object {
        /** Signal an MainActivity: Verfassen-Fenster mit wartendem Anhang öffnen. */
        const val ACTION_COMPOSE_ATTACH = "com.jakober.klarmail.action.COMPOSE_ATTACH"
    }
}
