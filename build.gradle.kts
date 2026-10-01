// Configuración de compilación de la app (módulo único, en la raíz del repo).
// Se compila en GitHub Actions con `gradle assembleDebug` (ver .github/workflows/build.yml);
// el APK sale en build/outputs/apk/debug/.

plugins {
    // Plugin de Android para Gradle. La versión (8.5.2) debe ser compatible con
    // la versión de Gradle que usa el workflow (8.9). Si subes una, revisa la otra.
    id("com.android.application") version "8.5.2"
}

android {
    // Identificador interno del código. Debe coincidir con el `package` de
    // MainActivity.java y con la carpeta src/main/java/com/prismalmesh/checkmyknowledge/.
    namespace = "com.prismalmesh.checkmyknowledge"
    compileSdk = 34

    defaultConfig {
        // Identificador único de la app en el celular/Play Store. Cambiarlo hace
        // que Android la trate como una app distinta (no actualiza la instalada).
        applicationId = "com.prismalmesh.checkmyknowledge"
        minSdk = 24      // Android 7.0 en adelante
        targetSdk = 34
        // versionCode debe subir en cada versión que quieras que Android
        // reconozca como actualización; versionName es solo el texto visible.
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No hay bloque `dependencies`: la app no usa librerías externas. Todo es la
// Activity nativa + WebView + HTML/JS en assets/. Si agregas librerías, hazlo aquí.
