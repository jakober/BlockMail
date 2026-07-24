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

    private fun handleOpenIntent(intent: android.content.Intent?) {
        val uid = intent?.getLongExtra("open_uid", -1L) ?: -1L
        if (uid > 0) pendingOpenUid.value = uid
        if (intent?.getBooleanExtra("open_log", false) == true) pendingOpenLog.value = true
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
            KlarMailTheme(schemeId = scheme) {
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
                                onOpenNewsletterLog = { nav.navigate("newsletterlog") }
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
                        "compose?replyTo={replyTo}",
                        arguments = listOf(navArgument("replyTo") {
                            type = NavType.LongType
                            defaultValue = -1L
                        })
                    ) { entry ->
                        val replyTo = entry.arguments?.getLong("replyTo") ?: -1L
                        ComposeScreen(
                            replyToUid = replyTo.takeIf { it != -1L },
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenNewsletterLog = { nav.navigate("newsletterlog") }
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
