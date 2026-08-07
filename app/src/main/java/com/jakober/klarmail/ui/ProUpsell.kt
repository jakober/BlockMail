package com.jakober.klarmail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.BillingManager

/**
 * Tarif-Auswahl für „BlockMail Pro“: beide Stufen mit Preis aus dem Play
 * Store, enthaltenen KI-Anfragen und Hinweis, was als Anfrage zählt. Wird im
 * Hinweis-Dialog ([ProUpsellDialog]) und in den Einstellungen benutzt, damit
 * überall dasselbe steht.
 *
 * @param currentPlan laufender Basis-Tarif ("" = kein Abo)
 */
@Composable
fun ProPlanChooser(
    currentPlan: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Sobald Play die Angebotsdaten geliefert hat, stehen hier die echten
    // Preise in Landeswährung; bis dahin die hinterlegten Richtpreise.
    val details by BillingManager.productDetails.collectAsState()
    // details wird bewusst mitgelesen: sobald Play die Angebotsdaten
    // nachliefert, zeichnet Compose die Preise neu
    val proPrice = details?.let { BillingManager.priceFor(BillingManager.BASE_PLAN_PRO) }
        ?: stringResource(R.string.pro_plan_pro_price_fallback)
    val plusPrice = details?.let { BillingManager.priceFor(BillingManager.BASE_PLAN_PLUS) }
        ?: stringResource(R.string.pro_plan_plus_price_fallback)
    Column(modifier = modifier.fillMaxWidth()) {
        ProPlanCard(
            name = stringResource(R.string.pro_plan_pro_name),
            price = proPrice,
            requests = BillingManager.REQUESTS_PRO,
            current = currentPlan == BillingManager.BASE_PLAN_PRO,
            hasOther = currentPlan.isNotBlank(),
            onPick = { onPick(BillingManager.BASE_PLAN_PRO) }
        )
        Spacer(Modifier.height(8.dp))
        ProPlanCard(
            name = stringResource(R.string.pro_plan_plus_name),
            price = plusPrice,
            requests = BillingManager.REQUESTS_PLUS,
            current = currentPlan == BillingManager.BASE_PLAN_PLUS,
            hasOther = currentPlan.isNotBlank(),
            onPick = { onPick(BillingManager.BASE_PLAN_PLUS) }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.pro_what_counts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.pro_billing_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProPlanCard(
    name: String,
    price: String,
    requests: Int,
    current: Boolean,
    hasOther: Boolean,
    onPick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.pro_plan_price_month, price),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pro_plan_requests, requests),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            if (current) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.pro_plan_current),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (hasOther) {
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pro_plan_switch))
                }
            } else {
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pro_plan_choose))
                }
            }
        }
    }
}

/**
 * Werbekarte für BlockMail Pro im Posteingang — nur für Nutzer ohne Abo.
 *
 * Bewusst zurückhaltend: Sie steht in der Liste statt als Dialog davor, sie
 * lässt sich mit einem Tipp für die nächsten Tage wegräumen, und mit
 * „Nicht mehr anzeigen“ dauerhaft abschalten (wieder einschaltbar in den
 * Einstellungen).
 */
@Composable
fun ProAdCard(
    onOpen: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.pro_ad_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onLater) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.pro_ad_later)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pro_ad_text),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen) {
                    Text(stringResource(R.string.pro_ad_open))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onNever) {
                    Text(stringResource(R.string.pro_ad_never))
                }
            }
        }
    }
}

/**
 * Hinweis, wenn das Monatskontingent aufgebraucht ist: sagt, wann es neues
 * gibt, und bietet den größeren Tarif an. Die KI-Funktionen bleiben bis
 * dahin gesperrt, alles andere in der App läuft normal weiter.
 */
@Composable
fun AiQuotaExhaustedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val quota by com.jakober.klarmail.data.AiQuota.info.collectAsState()
    val plan by BillingManager.activePlan.collectAsState()
    val resetText = com.jakober.klarmail.data.AiQuota
        .formatReset(quota?.resetsAt ?: 0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.quota_out_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(
                        R.string.quota_out_text, quota?.limit
                            ?: BillingManager.REQUESTS_PRO
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (resetText.isNotBlank()) {
                    Text(
                        stringResource(R.string.quota_out_reset, resetText),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ProPlanChooser(
                    currentPlan = plan,
                    onPick = { basePlan ->
                        BillingManager.purchase(context, basePlan)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pro_ok))
            }
        }
    )
}

/**
 * Wiederverwendbarer Hinweis-Dialog für „BlockMail Pro“: erscheint überall
 * dort, wo eine KI-Funktion ohne Pro angestoßen wird (siehe
 * [com.jakober.klarmail.data.ProAccess]) — mit beiden Tarifen zur Auswahl,
 * damit beim Kauf klar ist, was man bekommt.
 */
@Composable
fun ProUpsellDialog(onDismiss: () -> Unit, showLifetime: Boolean = false) {
    val context = LocalContext.current
    val plan by BillingManager.activePlan.collectAsState()
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
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.pro_upsell_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (showLifetime) {
                    // Alternative ohne Abo GANZ OBEN, damit sie ohne
                    // Blaettern sichtbar ist: Einmalkauf nur fuer den
                    // Editor — KI bleibt Abo-exklusiv (Serverkosten)
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            BillingManager.purchaseLifetime(context)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                R.string.pro_lifetime_button,
                                BillingManager.lifetimePrice()
                                    ?: stringResource(R.string.pro_lifetime_price_fallback)
                            )
                        )
                    }
                    Text(
                        stringResource(R.string.pro_lifetime_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.HorizontalDivider()
                }
                ProPlanChooser(
                    currentPlan = plan,
                    onPick = { basePlan ->
                        BillingManager.purchase(context, basePlan)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pro_ok))
            }
        }
    )
}
