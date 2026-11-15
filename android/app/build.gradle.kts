import java.util.Properties

plugins {
    id("com.android.application")
}

// Clé de publication : android/key.properties (secret, jamais commité). Sans elle,
// la clé de débogage : l'APK s'installe, mais une mise à jour signée autrement non.
val proprietesCle = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val versionPagilis = (findProperty("versionPagilis") as String?)?.takeIf { it != "0.0.0" } ?: "0.1.0"

android {
    namespace = "fr.pagilis.pdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.pagilis.pdf"
        minSdk = 26
        targetSdk = 36
        // 0.1.0 -> 100, 1.2.3 -> 10203
        versionCode = versionPagilis.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }
        versionName = versionPagilis
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (proprietesCle.containsKey("storeFile")) {
            create("publication") {
                storeFile = file(proprietesCle.getProperty("storeFile"))
                storePassword = proprietesCle.getProperty("storePassword")
                keyAlias = proprietesCle.getProperty("keyAlias")
                keyPassword = proprietesCle.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Tablettes et téléphones (ARM) : l'APK publié n'embarque pas MuPDF pour PC (x86).
            // (-PtoutesArchis : aussi x86, pour essayer une version signée sur l'émulateur.)
            if (findProperty("toutesArchis") == null) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            signingConfig = signingConfigs.findByName("publication") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("com.artifex.mupdf:fitz:1.28.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
