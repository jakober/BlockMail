package com.jakober.klarmail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jakober.klarmail.R
import com.jakober.klarmail.data.BillingManager

/**
 * Wiederverwendbarer Hinweis-Dialog für „BlockMail Pro“: erscheint überall
 * dort, wo eine KI-Funktion ohne Pro angestoßen wird (siehe
 * [com.jakober.klarmail.data.ProAccess]) — mit Kauf-Knopf, der den
 * Play-Kaufdialog öffnet.
 */
@Composable
fun ProUpsellDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.pro_title)) },
        text = {
            Text(
                stringResource(R.string.pro_upsell_text),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = {
                BillingManager.purchase(context)
                onDismiss()
            }) {
                Text(stringResource(R.string.pro_subscribe))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pro_ok))
            }
        }
    )
}
