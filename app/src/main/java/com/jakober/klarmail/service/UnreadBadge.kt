package com.jakober.klarmail.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.jakober.klarmail.data.Prefs
import org.json.JSONObject

/**
 * Zahl am App-Symbol: Summe der ungelesenen Posteingangs-Mails aller Konten.
 *
 * Android selbst kennt dafür keine Schnittstelle — die Zahl am Symbol hängt
 * offiziell an den sichtbaren Benachrichtigungen und verschwindet, sobald
 * man sie wegwischt. Viele Hersteller-Startbildschirme (Samsung, LG, Vivo,
 * Huawei/Honor) nehmen aber zusätzlich eine direkt gemeldete Zahl an. Die
 * wird hier nach bestem Wissen über alle bekannten Wege gesetzt; ein
 * Startbildschirm, der keinen davon kennt, ignoriert sie einfach.
 */
object UnreadBadge {

    /** Ungelesene je Konto (Schlüssel: Adresse in Kleinbuchstaben). */
    private val counts = HashMap<String, Int>()
    private var loaded = false
    private var lastSent = -1

    private fun key(accountEmail: String) =
        accountEmail.ifBlank { Prefs.email }.trim().lowercase()

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(Prefs.badgeCounts.ifBlank { "{}" })
            o.keys().forEach { counts[it] = o.optInt(it, 0) }
        }
    }

    /** Verbindlicher Stand eines Kontos (vom Server gezählt). */
    @Synchronized
    fun set(context: Context, accountEmail: String, unread: Int) {
        load()
        counts[key(accountEmail)] = unread.coerceAtLeast(0)
        publish(context)
    }

    /**
     * Sofort-Korrektur aus der App heraus (Mail gelesen/ungelesen markiert),
     * damit die Zahl nicht bis zum nächsten Server-Abgleich hinterherhinkt.
     */
    @Synchronized
    fun adjust(context: Context, accountEmail: String, delta: Int) {
        load()
        val k = key(accountEmail)
        // Ohne bekannten Stand nichts erfinden — der Abgleich liefert ihn
        val cur = counts[k] ?: return
        counts[k] = (cur + delta).coerceAtLeast(0)
        publish(context)
    }

    /** Entfernte Konten aus der Summe nehmen. */
    @Synchronized
    fun retain(context: Context, accountEmails: Collection<String>) {
        load()
        val keep = accountEmails.map { it.trim().lowercase() }.toSet()
        if (counts.keys.retainAll(keep)) publish(context)
    }

    private fun publish(context: Context) {
        runCatching {
            Prefs.badgeCounts = JSONObject().apply {
                counts.forEach { (k, v) -> put(k, v) }
            }.toString()
        }
        val total = counts.values.sum()
        if (total == lastSent) return
        lastSent = total
        apply(context.applicationContext, total)
    }

    private fun apply(context: Context, count: Int) {
        val pkg = context.packageName
        val cls = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.component?.className ?: return
        // Samsung, LG und weitere: Rundruf an den Startbildschirm. Seit
        // Android 8 erreichen Rundrufe ohne Zielpaket registrierte Empfänger
        // nicht mehr — deshalb gezielt an jeden, der sich dafür meldet.
        runCatching {
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
                .putExtra("badge_count", count)
                .putExtra("badge_count_package_name", pkg)
                .putExtra("badge_count_class_name", cls)
            val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
            if (receivers.isEmpty()) {
                context.sendBroadcast(intent)
            } else {
                receivers.forEach { r ->
                    context.sendBroadcast(
                        Intent(intent).setPackage(r.activityInfo.packageName)
                    )
                }
            }
        }
        // Samsung: Zähler-Tabelle des Startbildschirms
        runCatching {
            val uri = Uri.parse("content://com.sec.badge/apps?notify=true")
            val cr = context.contentResolver
            cr.query(uri, arrayOf("_id", "class"), "package=?", arrayOf(pkg), null)?.use { c ->
                var found = false
                while (c.moveToNext()) {
                    cr.update(
                        uri,
                        ContentValues().apply { put("badgecount", count) },
                        "_id=?",
                        arrayOf(c.getInt(0).toString())
                    )
                    if (cls == c.getString(1)) found = true
                }
                if (!found) {
                    cr.insert(
                        uri,
                        ContentValues().apply {
                            put("package", pkg)
                            put("class", cls)
                            put("badgecount", count)
                        }
                    )
                }
            }
        }
        // Vivo
        runCatching {
            context.sendBroadcast(
                Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM")
                    .putExtra("packageName", pkg)
                    .putExtra("className", cls)
                    .putExtra("notificationNum", count)
            )
        }
        // Huawei / Honor
        runCatching {
            context.contentResolver.call(
                Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge",
                null,
                Bundle().apply {
                    putString("package", pkg)
                    putString("class", cls)
                    putInt("badgenumber", count)
                }
            )
        }
    }
}
