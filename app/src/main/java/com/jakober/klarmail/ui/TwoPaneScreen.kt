package com.jakober.klarmail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Zweispaltige Ansicht für Tablets und Smartphones im Querformat:
 * links die Mail-Liste, rechts die Detailansicht.
 */
@Composable
fun TwoPaneScreen(
    selectedUid: Long,
    onSelect: (Long) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onReply: (Long) -> Unit,
    onOpenNewsletterLog: () -> Unit = {}
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f)) {
            InboxScreen(
                onOpenMail = { onSelect(it) },
                onCompose = onCompose,
                onSettings = onSettings,
                onOpenNewsletterLog = onOpenNewsletterLog
            )
        }
        VerticalDivider()
        Box(Modifier.weight(0.58f)) {
            if (selectedUid <= 0L) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.MailOutline,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Wähle links eine E-Mail aus",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                DetailScreen(
                    uid = selectedUid,
                    onBack = { onSelect(-1L) },
                    onReply = { onReply(selectedUid) }
                )
            }
        }
    }
}
