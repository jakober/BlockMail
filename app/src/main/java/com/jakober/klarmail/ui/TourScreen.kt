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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import kotlinx.coroutines.launch

/**
 * Einführungs-Tour für neue Nutzer (Tester-Feedback): durchblätterbare
 * Seiten mit den wichtigsten Funktionen. Erscheint einmal nach dem
 * Einrichtungsassistenten; jederzeit erneut über Einstellungen →
 * Daten & Feedback → "Einführung ansehen".
 */
private data class TourPage(
    val icon: ImageVector,
    val titleRes: Int,
    val textRes: Int
)

@Composable
fun TourScreen(onDone: () -> Unit) {
    val pages = listOf(
        TourPage(Icons.Filled.Inbox, R.string.tour_1_title, R.string.tour_1_text),
        TourPage(Icons.Filled.AutoAwesome, R.string.tour_2_title, R.string.tour_2_text),
        TourPage(Icons.Filled.Swipe, R.string.tour_3_title, R.string.tour_3_text),
        TourPage(Icons.Filled.Newspaper, R.string.tour_4_title, R.string.tour_4_text),
        TourPage(
            Icons.Filled.NotificationsActive,
            R.string.tour_5_title, R.string.tour_5_text
        )
    )
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.tour_skip))
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val p = pages[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            p.icon,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        stringResource(p.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(p.textRes),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Punkte-Anzeige: aktuelle Seite hervorgehoben
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pages.size) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == pagerState.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
            Button(
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) {
                        onDone()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    if (pagerState.currentPage == pages.lastIndex) {
                        stringResource(R.string.tour_done)
                    } else {
                        stringResource(R.string.tour_next)
                    }
                )
            }
        }
    }
}
