package com.jakober.klarmail.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailRepository
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * Statistik im BlockMail-Kachel-Stil: Kennzahlen, Wochentags-Verteilung und
 * Top-Absender — berechnet aus den aktuell geladenen Mails des Posteingangs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val messages by MailRepository.messages.collectAsState()

    val total = messages.size
    val unread = messages.count { !it.seen }
    val withAttachment = messages.count { it.hasAttachments }

    // Mails je Wochentag (Mo–So)
    val weekdayCounts = remember(messages) {
        val counts = IntArray(7)
        val cal = Calendar.getInstance()
        messages.forEach { m ->
            cal.timeInMillis = m.date
            // Calendar: Sonntag=1 … Samstag=7 → Index Montag=0 … Sonntag=6
            val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            counts[idx]++
        }
        counts.toList()
    }

    // Aufkommen der letzten 4 Wochen (Woche 0 = aktuelle Woche)
    val weekCounts = remember(messages) {
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000
        (0 until 4).map { w ->
            messages.count { m ->
                val age = now - m.date
                age >= w * week && age < (w + 1) * week
            }
        }
    }

    val topSenders = remember(messages) {
        messages.groupBy { it.fromAddress.lowercase().ifBlank { it.from } }
            .map { (_, mails) ->
                Triple(mails.first().from, mails.first().fromAddress, mails.size)
            }
            .sortedByDescending { it.third }
            .take(8)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.stats_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.stats_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.stats_based_on, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Kennzahlen-Kacheln (2er-Raster wie in der Block-Ansicht)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_tile_loaded), "$total", Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_tile_unread), "$unread", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    stringResource(R.string.stats_tile_with_attachment),
                    if (total > 0) "${withAttachment * 100 / total} %" else "—",
                    Modifier.weight(1f)
                )
                StatTile(
                    stringResource(R.string.stats_tile_per_day),
                    if (weekCounts.sum() > 0) {
                        String.format(Locale.getDefault(), "%.1f", weekCounts.sum() / 28.0)
                    } else "—",
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))

            StatsCard(stringResource(R.string.stats_card_weekdays)) {
                // Kurze Wochentagsnamen der Systemsprache, in Reihenfolge Mo–So
                val weekdayLabels = remember {
                    val symbols = DateFormatSymbols(Locale.getDefault()).shortWeekdays
                    listOf(
                        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY,
                        Calendar.SUNDAY
                    ).map { symbols[it].removeSuffix(".") }
                }
                BarRow(
                    values = weekdayCounts,
                    labels = weekdayLabels
                )
            }
            Spacer(Modifier.height(14.dp))

            StatsCard(stringResource(R.string.stats_card_weeks)) {
                BarRow(
                    // Älteste Woche links, aktuelle rechts
                    values = weekCounts.reversed(),
                    labels = listOf(
                        R.string.stats_week_3_ago,
                        R.string.stats_week_2_ago,
                        R.string.stats_week_last,
                        R.string.stats_week_this
                    ).map { stringResource(it) }
                )
            }
            Spacer(Modifier.height(14.dp))

            StatsCard(stringResource(R.string.stats_card_top_senders)) {
                if (topSenders.isEmpty()) {
                    Text(
                        stringResource(R.string.stats_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val maxCount = topSenders.maxOfOrNull { it.third } ?: 1
                topSenders.forEachIndexed { i, (name, address, count) ->
                    if (i > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SenderAvatar(
                            name = name.ifBlank { address },
                            address = address,
                            size = 34.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name.ifBlank { address },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Kleiner Anteils-Balken im Kachel-Stil
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth(count.toFloat() / maxCount)
                                    .height(5.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "$count",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Kleine Kennzahl-Kachel im Block-Stil. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Abgerundete Karte für einen Statistik-Abschnitt. */
@Composable
private fun StatsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Einfaches Balkendiagramm aus Compose-Boxen (ohne Fremdbibliothek). */
@Composable
private fun BarRow(values: List<Int>, labels: List<String>) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$v",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((84.dp * v / max).coerceAtLeast(3.dp))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = 0.45f + 0.55f * v / max
                            ),
                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    labels.getOrElse(i) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
