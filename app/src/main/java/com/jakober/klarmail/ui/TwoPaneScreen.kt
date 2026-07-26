package com.jakober.klarmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Zweispaltige Ansicht für Tablets und Smartphones im Querformat:
 * links die Mail-Liste, rechts die Detailansicht. Die Trennlinie lässt
 * sich verschieben — die Kachelspalten links passen sich automatisch an
 * die verfügbare Breite an (responsive).
 */
@Composable
fun TwoPaneScreen(
    selectedUid: Long,
    onSelect: (Long) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onReply: (Long) -> Unit,
    onOpenNewsletterLog: () -> Unit = {},
    onOpenDraft: (Long) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAttachments: () -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalWidth = maxWidth
        val totalPx = with(density) { totalWidth.toPx() }
        // Anteil der linken Spalte — bleibt über Drehungen hinweg erhalten
        var split by rememberSaveable { mutableFloatStateOf(0.42f) }

        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(totalWidth * split)
            ) {
                InboxScreen(
                    onOpenMail = { onSelect(it) },
                    onCompose = onCompose,
                    onSettings = onSettings,
                    onOpenNewsletterLog = onOpenNewsletterLog,
                    onOpenDraft = onOpenDraft,
                    onOpenStats = onOpenStats,
                    onOpenAttachments = onOpenAttachments
                )
            }
            // Verschiebbarer Griff: Linie mit kleinem Anfasser in der Mitte
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(16.dp)
                    .pointerInput(totalPx) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            split = (split + dragAmount / totalPx)
                                .coerceIn(0.22f, 0.72f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                VerticalDivider(Modifier.fillMaxHeight())
                Box(
                    Modifier
                        .width(5.dp)
                        .height(56.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
            Box(Modifier.weight(1f)) {
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
                    // key(uid): Beim Mail-Wechsel wird die Detailansicht komplett
                    // neu aufgebaut — sonst bleibt der alte WebView der vorherigen
                    // Mail hängen und füllt die Fläche weiß
                    androidx.compose.runtime.key(selectedUid) {
                        DetailScreen(
                            uid = selectedUid,
                            onBack = { onSelect(-1L) },
                            onReply = { onReply(selectedUid) }
                        )
                    }
                }
            }
        }
    }
}
