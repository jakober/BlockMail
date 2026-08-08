package com.jakober.blockpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Startbildschirm: PDF öffnen, zuletzt geöffnete Dokumente, Pro-Karte. */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.jakober.blockpdf.ui.BlockPdfTheme {
                HomeScreen(
                    onOpen = { uri, name ->
                        rememberRecent(uri, name)
                        startActivity(
                            Intent(this, PdfEditorActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .setData(uri)
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { PdfBilling.refreshPurchases() }
    }

    // ---- Zuletzt geöffnet (einfache JSON-Liste in den Einstellungen) ----

    private fun prefs() = getSharedPreferences("blockpdf", MODE_PRIVATE)

    fun recents(): List<Pair<String, String>> = runCatching {
        val arr = JSONArray(prefs().getString("recents", "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("u") to o.getString("n")
        }
    }.getOrDefault(emptyList())

    private fun rememberRecent(uri: Uri, name: String) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val list = recents().filter { it.first != uri.toString() }.toMutableList()
        list.add(0, uri.toString() to name)
        val arr = JSONArray()
        list.take(12).forEach {
            arr.put(JSONObject().put("u", it.first).put("n", it.second))
        }
        prefs().edit().putString("recents", arr.toString()).apply()
    }

    @Composable
    private fun HomeScreen(onOpen: (Uri, String) -> Unit) {
        val context = LocalContext.current
        var recentList by remember { mutableStateOf(recents()) }
        val isPro by PdfBilling.isProFlow.collectAsState()
        val price by PdfBilling.priceFlow.collectAsState()

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                }.getOrNull() ?: "Dokument.pdf"
                onOpen(uri, name)
                recentList = recents()
            }
        }

        // ---- Neues PDF aus Fotos / Kamera-Scan --------------------------
        val scope = rememberCoroutineScope()
        var busyCreate by remember { mutableStateOf(false) }

        fun stamp(): String = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmm", java.util.Locale.US
        ).format(java.util.Date())

        fun createFrom(uris: List<Uri>, name: String) {
            if (uris.isEmpty() || busyCreate) return
            busyCreate = true
            scope.launch {
                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val out = java.io.File(dir, name)
                val ok = com.jakober.klarmail.data.PdfPageOps
                    .createFromImages(context, uris, out)
                busyCreate = false
                if (ok) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.jakober.blockpdf.fileprovider", out
                    )
                    onOpen(uri, name)
                    recentList = recents()
                } else {
                    android.widget.Toast.makeText(
                        context, R.string.home_create_failed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val photosPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (!uris.isNullOrEmpty()) createFrom(uris, "Fotos-${stamp()}.pdf")
        }

        var camTarget by remember { mutableStateOf<Uri?>(null) }
        val camera = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { ok ->
            val u = camTarget
            if (ok && u != null) createFrom(listOf(u), "Scan-${stamp()}.pdf")
        }

        fun launchCamera() {
            val dir = java.io.File(context.cacheDir, "scans").apply { mkdirs() }
            val f = java.io.File(dir, "scan_${System.currentTimeMillis()}.jpg")
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.jakober.blockpdf.fileprovider", f
                )
                camTarget = uri
                camera.launch(uri)
            }.onFailure {
                android.widget.Toast.makeText(
                    context, R.string.home_create_failed,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Versteckte Entwickler-Freischaltung: siebenmal aufs Logo tippen
        // oeffnet ein Code-Feld; geprueft wird nur der SHA-256 des Codes.
        var devTaps by remember { mutableStateOf(0) }
        var devAsk by remember { mutableStateOf(false) }
        if (devAsk) {
            var devCode by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { devAsk = false },
                title = { Text(stringResource(R.string.dev_title)) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = devCode,
                        onValueChange = { devCode = it },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(devCode.trim().toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        devAsk = false
                        if (hash ==
                            "0345d7ff4b547f86aec4e630a737e9ec8f66947bc85f007795e7081d3072378f"
                        ) {
                            val on = !PdfBilling.isDevPro()
                            PdfBilling.setDevPro(context, on)
                            android.widget.Toast.makeText(
                                context,
                                if (on) R.string.dev_on else R.string.dev_off,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context, R.string.dev_wrong,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Text(stringResource(R.string.dev_apply)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { devAsk = false }) {
                        Text(stringResource(R.string.upsell_later))
                    }
                }
            )
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                devTaps++
                                if (devTaps >= 7) {
                                    devTaps = 0
                                    devAsk = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.home_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { picker.launch(arrayOf("application/pdf", "image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_open))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { photosPicker.launch(arrayOf("image/*")) },
                        enabled = !busyCreate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_create_photos), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { launchCamera() },
                        enabled = !busyCreate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_scan), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (!isPro) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.pro_title) + " — " +
                                    stringResource(
                                        R.string.pro_per_month,
                                        price ?: stringResource(R.string.pro_price_fallback)
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.pro_features),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { PdfBilling.purchase(context) }) {
                                Text(stringResource(R.string.pro_subscribe))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.pro_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.pro_active),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (recentList.isEmpty()) {
                    Text(
                        stringResource(R.string.home_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recentList) { (u, n) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpen(Uri.parse(u), n) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    n,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!isPro) {
                    Text(
                        stringResource(R.string.home_view_free),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
}
