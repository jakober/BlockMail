package com.jakober.blockpdf.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jakober.blockpdf.PdfBilling
import com.jakober.blockpdf.R

/** Kauf-Hinweis: erscheint, wenn ohne Abo ein Werkzeug angefasst wird. */
@Composable
fun PdfUpsellDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val price by PdfBilling.priceFlow.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pro_title)) },
        text = {
            Text(
                stringResource(
                    R.string.upsell_text,
                    price ?: stringResource(R.string.pro_price_fallback)
                )
            )
        },
        confirmButton = {
            Button(onClick = {
                PdfBilling.purchase(context)
                onDismiss()
            }) { Text(stringResource(R.string.pro_subscribe)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.upsell_later))
            }
        }
    )
}
