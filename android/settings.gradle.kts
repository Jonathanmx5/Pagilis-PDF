pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Bibliothèque MuPDF (le même moteur PDF que la version Windows, via PyMuPDF).
        maven("https://maven.ghostscript.com")
    }
}
rootProject.name = "PagilisPDF"
include(":app")
