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

    private val pendingOpenLog = androidx.compose.runtime.mutableStateOf(false)

    /** Vom Launcher-Shortcut angefordert: neue Mail verfassen. */
    private val pendingCompose = androidx.compose.runtime.mutableStateOf(false)

    private fun handleOpenIntent(intent: android.content.Intent?) {
        val uid = intent?.getLongExtra("open_uid", -1L) ?: -1L
        if (uid > 0) pendingOpenUid.value = uid
        if (intent?.getBooleanExtra("open_log", false) == true) pendingOpenLog.value = true
        if (intent?.action == "com.jakober.klarmail.SHORTCUT_COMPOSE") pendingCompose.value = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
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
                val openLog = pendingOpenLog.value
                androidx.compose.runtime.LaunchedEffect(openLog) {
                    if (openLog) {
                        nav.navigate("newsletterlog")
                        pendingOpenLog.value = false
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
                                onOpenNewsletterLog = { nav.navigate("newsletterlog") }
                            )
                        } else {
                            InboxScreen(
                                onOpenMail = { uid -> nav.navigate("detail/$uid") },
                                onCompose = { nav.navigate("compose") },
                                onSettings = { nav.navigate("settings") },
                                onOpenNewsletterLog = { nav.navigate("newsletterlog") },
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
                        DetailScreen(
                            uid = uid,
                            onBack = { nav.popBackStack() },
                            onReply = { nav.navigate("compose?replyTo=$uid") }
                        )
                    }
                    composable(
                        "compose?replyTo={replyTo}&draft={draft}",
                        arguments = listOf(
                            navArgument("replyTo") {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                            navArgument("draft") {
                                type = NavType.LongType
                                defaultValue = -1L
                            }
                        )
                    ) { entry ->
                        val replyTo = entry.arguments?.getLong("replyTo") ?: -1L
                        val draftId = entry.arguments?.getLong("draft") ?: -1L
                        ComposeScreen(
                            replyToUid = replyTo.takeIf { it != -1L },
                            onBack = { nav.popBackStack() },
                            draftId = draftId.takeIf { it != -1L }
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
                            onOpenNewsletterLog = { nav.navigate("newsletterlog") },
                            onOpenSetup = { nav.navigate("setup") }
                        )
                    }
                    composable("setup") {
                        com.jakober.klarmail.ui.SetupWizardScreen(
                            onDone = { nav.popBackStack("inbox", inclusive = false) },
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
                    composable("newsletterlog") {
                        com.jakober.klarmail.ui.NewsletterLogScreen(
                            onBack = { nav.popBackStack() },
                            onOpenMail = { item ->
                                com.jakober.klarmail.data.MailRepository.pendingOpen =
                                    com.jakober.klarmail.data.MailRepository.MailFolder.NEWSLETTER to
                                        com.jakober.klarmail.data.MailMessage(
                                            uid = item.uid,
                                            subject = item.subject,
                                            from = item.from,
                                            fromAddress = item.address,
                                            date = item.date,
                                            seen = true
                                        )
                                if (item.uid > 0) nav.navigate("nldetail")
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
