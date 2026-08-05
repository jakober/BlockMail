package com.jakober.klarmail

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jakober.klarmail.data.Prefs
import com.jakober.klarmail.service.MailSyncService
import com.jakober.klarmail.ui.ComposeScreen
import com.jakober.klarmail.ui.DetailScreen
import com.jakober.klarmail.ui.InboxScreen
import com.jakober.klarmail.ui.KlarMailTheme
import com.jakober.klarmail.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Von einer Benachrichtigung angeforderte Mail (direkt öffnen). */
    private val pendingOpenUid = androidx.compose.runtime.mutableStateOf<Long?>(null)

    /** Vom Launcher-Shortcut angefordert: neue Mail verfassen. */
    private val pendingCompose = androidx.compose.runtime.mutableStateOf(false)

    private fun handleOpenIntent(intent: android.content.Intent?) {
        val uid = intent?.getLongExtra("open_uid", -1L) ?: -1L
        if (uid > 0) pendingOpenUid.value = uid
        if (intent?.action == "com.jakober.klarmail.SHORTCUT_COMPOSE") pendingCompose.value = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /**
     * Abo-Stand bei jeder Rückkehr in die App neu bei Play erfragen.
     *
     * Ohne das merkt die App eine Kündigung erst beim nächsten Kaltstart:
     * Wer im Play Store kündigt und zurückwechselt, sähe weiter „Dein
     * aktueller Tarif“ — und der Wechsel-Knopf schickte Play einen
     * Kauf-Token, den es nicht mehr gibt („Bei uns ist ein Fehler
     * aufgetreten“).
     */
    override fun onResume() {
        super.onResume()
        runCatching { com.jakober.klarmail.data.BillingManager.refreshPurchases() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Prefs.isConfigured) {
            MailSyncService.start(this)
        }
        handleOpenIntent(intent)

        setContent {
            val scheme by Prefs.colorSchemeFlow.collectAsState()
            val darkMode by Prefs.darkModeFlow.collectAsState()
            KlarMailTheme(schemeId = scheme, darkMode = darkMode) {
                val nav = rememberNavController()
                val openUid = pendingOpenUid.value
                androidx.compose.runtime.LaunchedEffect(openUid) {
                    if (openUid != null) {
                        nav.navigate("detail/$openUid")
                        pendingOpenUid.value = null
                    }
                }
                val openCompose = pendingCompose.value
                androidx.compose.runtime.LaunchedEffect(openCompose) {
                    if (openCompose) {
                        nav.navigate("compose")
                        pendingCompose.value = false
                    }
                }
                // Erster Start: Willkommens-Bildschirm statt leerem Posteingang
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!Prefs.isConfigured && !Prefs.welcomeShown) {
                        nav.navigate("welcome")
                    }
                }
                NavHost(navController = nav, startDestination = "inbox") {
                    composable("inbox") {
                        val widthDp = androidx.compose.ui.platform.LocalConfiguration
                            .current.screenWidthDp
                        if (widthDp >= 600) {
                            // Tablet oder Smartphone im Querformat: zweispaltig
                            var selectedUid by androidx.compose.runtime.saveable.rememberSaveable {
                                androidx.compose.runtime.mutableStateOf(-1L)
                            }
                            com.jakober.klarmail.ui.TwoPaneScreen(
                                selectedUid = selectedUid,
                                onSelect = { selectedUid = it },
                                onCompose = { nav.navigate("compose") },
                                onSettings = { nav.navigate("settings") },
                                onReply = { uid -> nav.navigate("compose?replyTo=$uid") },
                                onOpenDraft = { id -> nav.navigate("compose?draft=$id") },
                                onOpenStats = { nav.navigate("stats") },
                                onOpenAttachments = { nav.navigate("attachments") }
                            )
                        } else {
                            InboxScreen(
                                onOpenMail = { uid -> nav.navigate("detail/$uid") },
                                onCompose = { nav.navigate("compose") },
                                onSettings = { nav.navigate("settings") },
                                onOpenDraft = { id -> nav.navigate("compose?draft=$id") },
                                onOpenStats = { nav.navigate("stats") },
                                onOpenAttachments = { nav.navigate("attachments") }
                            )
                        }
                    }
                    composable(
                        "detail/{uid}",
                        arguments = listOf(navArgument("uid") { type = NavType.LongType })
                    ) { entry ->
                        val uid = entry.arguments?.getLong("uid") ?: return@composable
                        // Rückfall-Objekt aus Suche/KI-Treffern: Die Mail kann
                        // außerhalb des geladenen Fensters liegen — ohne den
                        // Merker käme "Nachricht nicht gefunden"
                        val fallback = com.jakober.klarmail.data.MailRepository
                            .pendingOpen?.second?.takeIf { it.uid == uid }
                        DetailScreen(
                            uid = uid,
                            onBack = { nav.popBackStack() },
                            onReply = { nav.navigate("compose?replyTo=$uid") },
                            onForward = { nav.navigate("compose?forward=$uid") },
                            fallbackMail = fallback,
                            onEditAttachment = { nav.navigate("editor") }
                        )
                    }
                    composable("editor") {
                        // Die Quelle kommt ueber den Merker herein und wird
                        // dem Bildschirm als Parameter gereicht — er soll
                        // spaeter auch von aussen benutzbar sein
                        val src = com.jakober.klarmail.data.AttachmentEditing.pending
                        if (src == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
                            return@composable
                        }
                        com.jakober.klarmail.ui.AttachmentEditorScreen(
                            source = src,
                            onBack = { nav.popBackStack() },
                            onSend = { replyUid ->
                                // Editor aus dem Verlauf nehmen: Zurueck aus
                                // dem Verfassen-Fenster soll zur Mail fuehren
                                nav.popBackStack()
                                if (replyUid != null) nav.navigate("compose?replyTo=$replyUid")
                                else nav.navigate("compose")
                            }
                        )
                    }
                    composable(
                        "compose?replyTo={replyTo}&draft={draft}&forward={forward}",
                        arguments = listOf(
                            navArgument("replyTo") {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                            navArgument("draft") {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                            navArgument("forward") {
                                type = NavType.LongType
                                defaultValue = -1L
                            }
                        )
                    ) { entry ->
                        val replyTo = entry.arguments?.getLong("replyTo") ?: -1L
                        val draftId = entry.arguments?.getLong("draft") ?: -1L
                        val forward = entry.arguments?.getLong("forward") ?: -1L
                        ComposeScreen(
                            replyToUid = replyTo.takeIf { it != -1L },
                            onBack = { nav.popBackStack() },
                            draftId = draftId.takeIf { it != -1L },
                            forwardFromUid = forward.takeIf { it != -1L }
                        )
                    }
                    composable("stats") {
                        com.jakober.klarmail.ui.StatsScreen(
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("attachments") {
                        com.jakober.klarmail.ui.AttachmentsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenMail = { uid -> nav.navigate("detail/$uid") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenSetup = { nav.navigate("setup") },
                            onOpenTour = {
                                // Live-Tour läuft im Posteingang — dorthin
                                // zurück und Overlay starten
                                com.jakober.klarmail.ui.InboxTour.start()
                                nav.popBackStack("inbox", inclusive = false)
                            }
                        )
                    }
                    composable("setup") {
                        com.jakober.klarmail.ui.SetupWizardScreen(
                            onDone = {
                                nav.popBackStack("inbox", inclusive = false)
                                // Nach der ersten Einrichtung einmalig die
                                // Live-Tour im Posteingang starten
                                if (!Prefs.tourShown) {
                                    com.jakober.klarmail.ui.InboxTour.start()
                                }
                            },
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("welcome") {
                        com.jakober.klarmail.ui.WelcomeScreen(
                            onSetup = {
                                Prefs.welcomeShown = true
                                nav.navigate("setup")
                            },
                            onSkip = {
                                Prefs.welcomeShown = true
                                nav.popBackStack()
                            }
                        )
                    }
                    composable("nldetail") {
                        val po = com.jakober.klarmail.data.MailRepository.pendingOpen
                        if (po == null) {
                            androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
                        } else {
                            DetailScreen(
                                uid = po.second.uid,
                                onBack = { nav.popBackStack() },
                                onReply = {},
                                folder = po.first,
                                fallbackMail = po.second
                            )
                        }
                    }
                }
            }
        }
    }
}
