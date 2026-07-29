package com.jakober.klarmail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jakober.klarmail.R

/**
 * Wiederverwendbarer Hinweis-Dialog für „BlockMail Pro“: erscheint überall
 * dort, wo eine KI-Funktion ohne Pro angestoßen wird (siehe
 * [com.jakober.klarmail.data.ProAccess]). Bewusst OHNE Kauf-Knopf — der
 * kommt später zusammen mit der Play-Billing-Anbindung.
 */
@Composable
fun ProUpsellDialog(onDismiss: () -> Unit) {
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pro_ok))
            }
        }
    )
}
