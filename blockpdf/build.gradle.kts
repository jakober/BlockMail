plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// BlockPDF: eigenstaendiger PDF-Editor auf Basis des gemeinsamen
// :document-Moduls. Ansehen ist frei, Bearbeiten/Speichern gibt es mit
// dem eigenen Abo "blockpdf_pro" (1,99 EUR/Monat, Preis kommt aus Play).
android {
    namespace = "com.jakober.blockpdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jakober.blockpdf"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.09"
    }

    // Gleicher Schluessel wie BlockMail: fuer Debug-Builds der geteilte
    // Repo-Schluessel, fuer den Store der Upload-Schluessel aus den Secrets
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation(project(":document"))
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    // Play Billing: Abo "BlockPDF Pro"
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}
