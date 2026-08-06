package com.jakober.klarmail.data

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val MEM = MemoryUsageSetting.setupMixed(20L * 1024 * 1024)

/**
 * Volltextsuche im geöffneten PDF. Liefert je Treffer die Seite und einen
 * kurzen Textausschnitt — die Oberfläche springt dann zur Seite.
 */
object PdfTextSearch {

    data class Hit(val page: Int, val snippet: String)

    suspend fun search(file: File, query: String, maxHits: Int = 60): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.length < 2) return@withContext emptyList()
            runCatching {
                PDDocument.load(file, MEM).use { doc ->
                    val stripper = PDFTextStripper()
                    val hits = mutableListOf<Hit>()
                    for (i in 0 until doc.numberOfPages) {
                        if (hits.size >= maxHits) break
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        val text = runCatching { stripper.getText(doc) }.getOrNull() ?: continue
                        val lower = text.lowercase()
                        var idx = lower.indexOf(q)
                        while (idx >= 0 && hits.size < maxHits) {
                            val start = (idx - 36).coerceAtLeast(0)
                            val end = (idx + q.length + 36).coerceAtMost(text.length)
                            hits += Hit(
                                page = i,
                                snippet = text.substring(start, end)
                                    .replace(Regex("\\s+"), " ").trim()
                            )
                            idx = lower.indexOf(q, idx + q.length)
                        }
                    }
                    hits
                }
            }.getOrDefault(emptyList())
        }
}

/**
 * Echte PDF-Formularfelder (AcroForm) lesen und ausfüllen — statt Text nur
 * über das Formular zu legen. Unterstützt Textfelder und Ankreuzkästchen;
 * alles andere bleibt unangetastet.
 */
object PdfFormOps {

    data class TextEntry(val name: String, val label: String, val value: String)
    data class CheckEntry(val name: String, val label: String, val checked: Boolean)
    data class Form(val texts: List<TextEntry>, val checks: List<CheckEntry>) {
        val isEmpty get() = texts.isEmpty() && checks.isEmpty()
    }

    private fun flatten(fields: List<PDField>, out: MutableList<PDField>) {
        fields.forEach { f ->
            if (f is PDNonTerminalField) flatten(f.children, out) else out += f
        }
    }

    suspend fun read(file: File): Form = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file, MEM).use { doc ->
                val acro = doc.documentCatalog.acroForm
                    ?: return@use Form(emptyList(), emptyList())
                val flat = mutableListOf<PDField>()
                flatten(acro.fields, flat)
                val texts = mutableListOf<TextEntry>()
                val checks = mutableListOf<CheckEntry>()
                flat.forEach { f ->
                    val label = f.alternateFieldName?.takeIf { it.isNotBlank() }
                        ?: f.partialName ?: return@forEach
                    when (f) {
                        is PDTextField -> texts += TextEntry(
                            f.fullyQualifiedName, label, f.value.orEmpty()
                        )
                        is PDCheckBox -> checks += CheckEntry(
                            f.fullyQualifiedName, label, f.isChecked
                        )
                        else -> Unit
                    }
                }
                Form(texts, checks)
            }
        }.getOrDefault(Form(emptyList(), emptyList()))
    }

    suspend fun fill(
        src: File,
        out: File,
        textValues: Map<String, String>,
        checkValues: Map<String, Boolean>
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(src, MEM).use { doc ->
                val acro = doc.documentCatalog.acroForm ?: return@withContext false
                textValues.forEach { (name, value) ->
                    // Einzelfehler (fehlende Schrift o. Ä.) reißen nicht das
                    // ganze Formular mit — das Feld bleibt dann einfach leer
                    runCatching {
                        (acro.getField(name) as? PDTextField)?.setValue(value)
                    }
                }
                checkValues.forEach { (name, checked) ->
                    runCatching {
                        (acro.getField(name) as? PDCheckBox)?.let {
                            if (checked) it.check() else it.unCheck()
                        }
                    }
                }
                doc.save(out)
            }
            out.length() > 0
        }.getOrDefault(false)
    }
}

/** PDF beim Speichern mit einem Passwort verschlüsseln (AES-128). */
object PdfCrypt {

    suspend fun protect(src: File, out: File, password: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, MEM).use { doc ->
                    val policy = StandardProtectionPolicy(
                        password, password, AccessPermission()
                    )
                    policy.encryptionKeyLength = 128
                    policy.setPreferAES(true)
                    doc.protect(policy)
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }
}

/**
 * PDF verkleinern: jede Seite wird gerendert und als JPEG in ein neues
 * Dokument gelegt. Deutlich kleinere Datei — dafür ist der Text danach
 * nicht mehr markierbar. Seite für Seite, damit auch lange Dokumente
 * nicht am Speicher scheitern (JPEGs sind sofort komprimiert).
 */
object PdfCompress {

    suspend fun compress(src: File, out: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            android.os.ParcelFileDescriptor.open(
                src, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    PDDocument().use { dst ->
                        for (i in 0 until renderer.pageCount) {
                            val page = renderer.openPage(i)
                            try {
                                val scale = (1400f / maxOf(page.width, page.height))
                                    .coerceAtMost(2f)
                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                val m = android.graphics.Matrix().apply {
                                    setScale(scale, scale)
                                }
                                page.render(
                                    bmp, null, m,
                                    android.graphics.pdf.PdfRenderer.Page
                                        .RENDER_MODE_FOR_DISPLAY
                                )
                                val pd = PDPage(
                                    PDRectangle(
                                        page.width.toFloat(), page.height.toFloat()
                                    )
                                )
                                dst.addPage(pd)
                                val img = JPEGFactory.createFromImage(dst, bmp, 0.6f)
                                PDPageContentStream(dst, pd).use { cs ->
                                    cs.drawImage(
                                        img, 0f, 0f,
                                        page.width.toFloat(), page.height.toFloat()
                                    )
                                }
                                bmp.recycle()
                            } finally {
                                page.close()
                            }
                        }
                        dst.save(out)
                    }
                } finally {
                    renderer.close()
                }
            }
            out.length() > 0
        }.getOrDefault(false)
    }
}

/**
 * Findet Textstellen samt POSITION — Grundlage für „markiere alle
 * Geldbeträge/Begriffe“: Die Fundstellen werden dem Editor als Rechtecke
 * (PDF-Punkte der gedrehten Seite, Ursprung oben links) gemeldet, der legt
 * dort dann echte Textmarker-Aufsätze hin.
 */
object PdfTextLocate {

    data class Box(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    suspend fun find(file: File, regex: Regex, maxBoxes: Int = 400): List<Box> =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(file, MEM).use { doc ->
                    val boxes = mutableListOf<Box>()
                    for (p in 0 until doc.numberOfPages) {
                        if (boxes.size >= maxBoxes) break
                        val pageText = StringBuilder()
                        val charPos = ArrayList<com.tom_roush.pdfbox.text.TextPosition?>()
                        val stripper = object : PDFTextStripper() {
                            override fun writeString(
                                text: String,
                                textPositions: MutableList<
                                    com.tom_roush.pdfbox.text.TextPosition>
                            ) {
                                // Zeichen fuer Zeichen die Position merken —
                                // ein TextPosition kann mehrere Zeichen
                                // tragen (Ligaturen)
                                textPositions.forEach { tp ->
                                    val u = tp.unicode ?: return@forEach
                                    for (ch in u) {
                                        pageText.append(ch)
                                        charPos.add(tp)
                                    }
                                }
                            }

                            override fun writeWordSeparator() {
                                pageText.append(' ')
                                charPos.add(null)
                            }

                            override fun writeLineSeparator() {
                                pageText.append('\n')
                                charPos.add(null)
                            }
                        }
                        stripper.startPage = p + 1
                        stripper.endPage = p + 1
                        runCatching { stripper.getText(doc) }
                        regex.findAll(pageText).forEach mloop@{ m ->
                            if (boxes.size >= maxBoxes) return@mloop
                            val tps = (m.range.first..m.range.last)
                                .mapNotNull { charPos.getOrNull(it) }
                            if (tps.isEmpty()) return@mloop
                            // Je Zeile ein Kasten (mehrzeilige Treffer)
                            tps.groupBy { (it.yDirAdj / 5f).toInt() }.values.forEach { line ->
                                val x0 = line.minOf { it.xDirAdj }
                                val x1 = line.maxOf { it.xDirAdj + it.widthDirAdj }
                                val hMax = line.maxOf { tp ->
                                    tp.heightDir.takeIf { it > 1f } ?: 9f
                                }
                                val yBase = line.maxOf { it.yDirAdj }
                                if (x1 > x0) {
                                    boxes += Box(p, x0, yBase - hMax, x1 - x0, hMax * 1.25f)
                                }
                            }
                        }
                    }
                    boxes
                }
            }.getOrDefault(emptyList())
        }
}

/** Inhaltsverzeichnis (Lesezeichen) eines PDFs mit Sprungzielen. */
object PdfOutline {

    data class Entry(val title: String, val page: Int, val depth: Int)

    suspend fun read(file: File): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file, MEM).use { doc ->
                val root = doc.documentCatalog.documentOutline
                    ?: return@use emptyList()
                val entries = mutableListOf<Entry>()
                fun walk(first: PDOutlineItem?, depth: Int) {
                    var cur = first
                    while (cur != null && entries.size < 300) {
                        val page = runCatching { cur.findDestinationPage(doc) }.getOrNull()
                        val idx = page?.let { doc.pages.indexOf(it) } ?: -1
                        val title = cur.title.orEmpty().trim()
                        if (idx >= 0 && title.isNotEmpty()) {
                            entries += Entry(title, idx, depth)
                        }
                        walk(cur.firstChild, depth + 1)
                        cur = cur.nextSibling
                    }
                }
                walk(root.firstChild, 0)
                entries
            }
        }.getOrDefault(emptyList())
    }
}
