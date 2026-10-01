// Ajustes del proyecto Gradle: de dónde se descargan plugins y librerías.

pluginManagement {
    // Repositorios donde Gradle busca PLUGINS (como com.android.application).
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Repositorios donde Gradle busca LIBRERÍAS de la app.
    repositories {
        google()
        mavenCentral()
    }
}

// Nombre del proyecto Gradle (no es el nombre visible de la app; ese está en
// el atributo android:label del AndroidManifest.xml).
rootProject.name = "checkmyknowledge"
