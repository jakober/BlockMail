plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jakober.klarmail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jakober.klarmail"
        minSdk = 26
        targetSdk = 36
        versionCode = 144
        versionName = "3.41"

        // Redirect-Schema fuer den Google-OAuth-Ruecksprung (umgekehrte Client-ID)
        manifestPlaceholders["appAuthRedirectScheme"] =
            "com.googleusercontent.apps.313846853654-qv9mb3t22r8v9u8uhj5ee3jl0mu0sftu"
    }

    // Fester Signatur-Schlüssel für alle Builds (lokal wie CI): Ohne ihn erzeugt
    // jeder Build-Rechner einen eigenen Debug-Schlüssel, und Android verweigert
    // dann das Update über eine bestehende Installation ("Paket im Konflikt").
    // Play-Store-Upload-Schlüssel: kommt NICHT ins Repo, sondern per
    // GitHub-Secrets in den Release-Workflow (siehe release-aab.yml)
    val uploadKeystorePath: String? = System.getenv("UPLOAD_KEYSTORE_FILE")

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/blockmail-debug.keystore")
            storePassword = "blockmail1"
            keyAlias = "blockmail"
            keyPassword = "blockmail1"
        }
        if (uploadKeystorePath != null) {
            create("upload") {
                storeFile = file(uploadKeystorePath)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (uploadKeystorePath != null) "upload" else "shared"
            )
        }
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Wächter-Worker: belebt den Push-Dienst wieder und meldet verpasste Mails
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // IMAP/SMTP (Android-kompatible Jakarta-Mail-Variante)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Claude API + Google-Token-Refresh
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-Device-KI (Gemini Nano über ML Kit Prompt API) als Gratis-Fallback
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    // Google-OAuth-Anmeldung (Konto-Auswahlfenster)
    implementation("net.openid:appauth:0.11.1")

    // Verschluesselte Ablage fuer Zugangsdaten
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Absender-Logos laden
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Rich-Text-Editor fuer das Verfassen-Fenster
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc13")
}
