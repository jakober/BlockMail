package com.jakober.blockpdf

import android.app.Application
import com.jakober.klarmail.data.AttachmentEditing
import com.jakober.klarmail.data.DocumentHost

class BlockPdfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // PDFBox braucht seine Schriftdaten, bevor ein geschuetztes PDF
        // aufgeschlossen oder ein Overlay geschrieben werden kann
        runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        }
        // Arbeitsdateien des Editors aufraeumen (Abbrueche, Abstuerze)
        runCatching { AttachmentEditing.cleanupOldFiles(this) }
        // Anschlussstelle des gemeinsamen Editors: Ansehen ist frei,
        // Bearbeiten haengt am BlockPDF-Pro-Abo
        DocumentHost.fileProviderAuthority = "com.jakober.blockpdf.fileprovider"
        DocumentHost.editAllowedFlow = PdfBilling.isProFlow
        DocumentHost.upsell = { onDismiss ->
            com.jakober.blockpdf.ui.PdfUpsellDialog(onDismiss = onDismiss)
        }
        runCatching { PdfBilling.init(this) }
    }
}
