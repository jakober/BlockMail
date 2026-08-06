plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Gemeinsamer Dokument-Editor von BlockMail und BlockPDF: Anzeige,
// Werkzeuge, verlustfreies Speichern (PdfOverlay), Seitenoperationen.
// Die Pakete bleiben com.jakober.klarmail.*, damit der Umzug aus der
// App keine Import-Aenderungen nach sich zieht.
android {
    namespace = "com.jakober.klarmail.document"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    api(composeBom)
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.activity:activity-compose:1.9.2")
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // PDF: PdfRenderer/PdfDocument sind Bordmittel; PDFBox fuer
    // Entschluesseln, Overlay-Speichern und Seitenoperationen
    api("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-Device-KI (Gemini Nano ueber die ML-Kit-Prompt-API) fuer den
    // KI-Assistenten des Editors — laeuft komplett auf dem Geraet
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
}
