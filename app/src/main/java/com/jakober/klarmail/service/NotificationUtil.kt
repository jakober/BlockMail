package com.jakober.klarmail.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.jakober.klarmail.R

/** Rendert das bunte BlockMail-Logo als Bitmap für das große Benachrichtigungs-Icon. */
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
}
