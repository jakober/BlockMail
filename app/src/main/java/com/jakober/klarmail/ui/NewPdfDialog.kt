package com.jakober.klarmail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import kotlinx.coroutines.launch

/**
 * Letztes per KI erstelltes Dokument (nur im Arbeitsspeicher): Damit lässt
 * es sich beim nächsten Öffnen des Dialogs überarbeiten („mach den Ton
 * förmlicher“, „ergänze …“) — ein PDF speichert keinen bearbeitbaren
 * Textfluss, deshalb läuft Nacharbeit über die KI statt über ein Textfeld.
 */
private object LastAiPdf {
    var title: String = ""
    var body: String = ""
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank()
}

/**
 * „PDF erstellen“: eine leere A4-Seite sofort, oder — mit Pro-Abo — den
 * Inhalt von der KI schreiben lassen. Das fertige Dokument öffnet direkt
 * im Editor (Unterschrift, Stempel … wie bei jedem Anhang); danach lässt
 * es sich hier per Änderungswunsch an die KI überarbeiten.
 *
 * Erreichbar über das Posteingangs-Menü, die Einstellungen und den
 * Launcher-Shortcut „Neues PDF“ (langes Drücken auf das App-Icon).
 */
@Composable
fun NewPdfDialog(
    onDismiss: () -> Unit,
    onOpenEditor: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPro by com.jakober.klarmail.data.ProAccess.isProFlow.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showUpsell by remember { mutableStateOf(false) }
    if (showUpsell) {
        ProUpsellDialog(onDismiss = { showUpsell = false })
    }

    fun stamp(): String = java.text.SimpleDateFormat(
        "yyyyMMdd-HHmm", java.util.Locale.US
    ).format(java.util.Date())

    // Das neue Dokument geht denselben Weg wie ein Mail-Anhang in den
    // Editor: Bytes in den Merker, der Editor macht daraus die Arbeitsdatei
    fun openInEditor(file: java.io.File, name: String) {
        com.jakober.klarmail.data.AttachmentEditing.pending =
            com.jakober.klarmail.data.AttachmentEditing.Source(
                name, "application/pdf", file.readBytes(), null
            )
        onDismiss()
        onOpenEditor()
    }

    fun createBlank() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val dir = java.io.File(context.cacheDir, "newpdf").apply { mkdirs() }
            val name = "Dokument-${stamp()}.pdf"
            val f = java.io.File(dir, name)
            val ok = com.jakober.klarmail.data.PdfPageOps.createBlank(f)
            busy = false
            if (ok) openInEditor(f, name)
            else error = context.getString(R.string.newpdf_failed)
        }
    }

    // Gemeinsamer Endweg für Erstellen und Überarbeiten: Text setzen,
    // fürs nächste Überarbeiten merken, im Editor öffnen
    suspend fun renderAndOpen(title: String, body: String) {
        val dir = java.io.File(context.cacheDir, "newpdf").apply { mkdirs() }
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim().take(40)
        val name = (safe.ifBlank { "Dokument-${stamp()}" }) + ".pdf"
        val f = java.io.File(dir, name)
        val ok = com.jakober.klarmail.data.PdfTextDoc.create(title, body, f)
        if (ok) {
            LastAiPdf.title = title
            LastAiPdf.body = body
            openInEditor(f, name)
        } else {
            error = context.getString(R.string.newpdf_failed)
        }
    }

    fun createWithAi() {
        if (busy) return
        if (!isPro) {
            showUpsell = true
            return
        }
        if (prompt.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                val (title, body) = com.jakober.klarmail.ai.ClaudeClient
                    .composeDocument(prompt.trim())
                renderAndOpen(title, body)
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.newpdf_failed)
            } finally {
                busy = false
            }
        }
    }

    fun reviseWithAi(changes: String) {
        if (busy) return
        if (!isPro) {
            showUpsell = true
            return
        }
        if (changes.isBlank() || !LastAiPdf.hasContent) return
        busy = true
        error = null
        scope.launch {
            try {
                val (title, body) = com.jakober.klarmail.ai.ClaudeClient
                    .reviseDocument(LastAiPdf.title, LastAiPdf.body, changes.trim())
                renderAndOpen(title, body)
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.newpdf_failed)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.newpdf_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedButton(
                    onClick = { createBlank() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.NoteAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.newpdf_blank))
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(
                        if (isPro) R.string.newpdf_ai_label
                        else R.string.newpdf_ai_label_pro
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    enabled = !busy,
                    minLines = 2,
                    placeholder = { Text(stringResource(R.string.newpdf_ai_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { createWithAi() },
                    enabled = !busy && (prompt.isNotBlank() || !isPro),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.newpdf_ai_create))
                }
                // Nacharbeit am letzten KI-Dokument: Änderungswunsch an die
                // KI („förmlicher“, „ergänze Absatz über …“) — sie liefert
                // das komplette Dokument neu und der Editor öffnet es
                if (LastAiPdf.hasContent) {
                    var changes by remember { mutableStateOf("") }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))
                    val fallbackName = stringResource(R.string.newpdf_title)
                    Text(
                        stringResource(
                            R.string.newpdf_ai_revise_label,
                            LastAiPdf.title.ifBlank { fallbackName }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = changes,
                        onValueChange = { changes = it },
                        enabled = !busy,
                        minLines = 2,
                        placeholder = {
                            Text(stringResource(R.string.newpdf_ai_revise_hint))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { reviseWithAi(changes) },
                        enabled = !busy && changes.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.newpdf_ai_revise))
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val err = error
                if (err != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.newpdf_cancel))
            }
        }
    )
}
