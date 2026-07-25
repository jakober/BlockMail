package com.jakober.klarmail.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.jakober.klarmail.R

/** Rendert Bitmaps für Benachrichtigungen (App-Logo und Absender-Avatare). */
object NotificationUtil {

    private var cached: Bitmap? = null

    fun logoBitmap(context: Context): Bitmap? {
        cached?.let { return it }
        return try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_logo_color)
                ?: return null
            val size = (context.resources.displayMetrics.density * 48).toInt().coerceAtLeast(96)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            cached = bmp
            bmp
        } catch (e: Exception) {
            null
        }
    }

    // Gleiche Quellen-Logik wie SenderAvatar in der Mail-Liste:
    // Marken-Logo → Favicon der Domain → Initialen-Kreis
    private val freemailDomains = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "outlook.de", "hotmail.com",
        "hotmail.de", "live.com", "live.de", "msn.com", "yahoo.com", "yahoo.de",
        "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "icloud.com", "me.com",
        "mac.com", "t-online.de", "freenet.de", "aol.com", "mail.de", "mail.com",
        "posteo.de", "proton.me", "protonmail.com", "tutanota.com", "tuta.io"
    )

    private val avatarCache = HashMap<String, Bitmap>()

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Absender-Avatar für die Benachrichtigung — dasselbe Bild wie in der
     * Mail-Liste. Muss auf einem IO-Thread laufen (lädt ggf. aus dem Netz).
     */
    fun senderAvatarBitmap(name: String, address: String): Bitmap {
        val key = address.lowercase().ifBlank { name.lowercase() }
        synchronized(avatarCache) { avatarCache[key] }?.let { return it }
        val domain = address.substringAfterLast("@", "").lowercase().trim()
        var bmp: Bitmap? = null
        if (domain.isNotBlank() && domain !in freemailDomains) {
            val sources = listOf(
                "https://logo.clearbit.com/$domain?size=256",
                "https://www.google.com/s2/favicons?domain=$domain&sz=256"
            )
            for (url in sources) {
                bmp = fetchBitmap(url)?.takeIf { it.width >= 32 }
                if (bmp != null) break
            }
        }
        val result = bmp ?: initialBitmap(name)
        synchronized(avatarCache) {
            if (avatarCache.size > 64) avatarCache.clear()
            avatarCache[key] = result
        }
        return result
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    } catch (_: Exception) {
        null
    }

    /** Farbkreis mit Initiale — wie der Fallback-Avatar in der Liste. */
    private fun initialBitmap(name: String): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE85510.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.45f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initial, size / 2f, y, text)
        return bmp
    }
}
