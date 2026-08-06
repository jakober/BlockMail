package com.jakober.blockpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.AttachmentEditing

/**
 * Nimmt PDFs und Bilder von außen an (Öffnen, Bearbeiten, Teilen) und zeigt
 * den gemeinsamen Dokument-Editor. Ansehen ist frei; sobald ein Werkzeug
 * angefasst wird, meldet sich der Kauf-Hinweis (siehe [BlockPdfApp]).
 */
class PdfEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val source = sourceFromIntent(intent)

        setContent {
            com.jakober.blockpdf.ui.BlockPdfTheme {
                if (source == null) {
                    MissingScreen()
                } else {
                    com.jakober.klarmail.ui.AttachmentEditorScreen(
                        source = source,
                        onBack = { finish() },
                        onSend = {
                            // "Als Mail senden": Ergebnis liegt als
                            // pendingResult bereit — Teilen-Blatt zeigen,
                            // dort waehlt man seine Mail-App
                            val r = AttachmentEditing.pendingResult
                            if (r != null) {
                                AttachmentEditing.pendingResult = null
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType(guessMime(r.name, null))
                                    .putExtra(Intent.EXTRA_STREAM, r.uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                startActivity(Intent.createChooser(send, null))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { PdfBilling.refreshPurchases() }
    }

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

        val name = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokument.pdf"

        val declared = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val mime = guessMime(name, declared)
        if (!AttachmentEditing.isEditable(mime, name)) return null

        val origin = when (intent.action) {
            Intent.ACTION_EDIT -> AttachmentEditing.Origin.EXTERNAL_EDIT
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                AttachmentEditing.Origin.EXTERNAL_SHARE
            else -> AttachmentEditing.Origin.EXTERNAL_VIEW
        }
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
    private fun MissingScreen() {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.open_failed),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { finish() }) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }

    companion object {
        /**
         * Dateityp bestimmen: Manche Apps deklarieren nur
         * application/octet-stream — dann entscheidet die Dateiendung.
         */
        fun guessMime(name: String, declared: String?): String {
            val d = declared?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            if (d != null) return d
            val n = name.lowercase()
            return when {
                n.endsWith(".pdf") -> "application/pdf"
                n.endsWith(".png") -> "image/png"
                n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
                n.endsWith(".webp") -> "image/webp"
                else -> declared ?: "application/octet-stream"
            }
        }
    }
}
