// Propiedad intelectual de PRISMAL MESH. Todos los derechos reservados.
//
// Configuración de compilación de la app (módulo único, en la raíz del repo).
// Se compila en GitHub Actions con `gradle assembleRelease` (ver .github/workflows/build.yml);
// el APK que se reparte sale en build/outputs/apk/release/.
//
// CAMBIO (5/oct/2026, 13): el APK que se reparte a los alumnos ahora es RELEASE (antes era debug).
// Un APK debug lleva la bandera "debuggable" y es el que más alarma a Play Protect; el release no la
// lleva y se firma con la llave fija de los secretos de GitHub (la misma que se registra ante Google
// en la verificación de desarrolladores, ver README "Instalar el APK y reducir las advertencias").
// El tipo debug sigue existiendo para compilar en tu computadora.

plugins {
    // Plugin de Android para Gradle. La versión (8.5.2) debe ser compatible con
    // la versión de Gradle que usa el workflow (8.9). Si subes una, revisa la otra.
    id("com.android.application") version "8.5.2"
}

// Clave con la que el APK firma sus llamadas al servidor (ver Puente.firmar() en MainActivity.java
// y _verificar_firma en Supabase). NUNCA se escribe en el repo: llega del secreto CMK_FIRMA_SECRETO
// de GitHub (variable de entorno en el workflow) o, para compilar en tu computadora, de la
// propiedad cmk.firma (p. ej. `gradle assembleRelease -Pcmk.firma=...`). Si no llega, queda vacía y
// el APK no podrá hablar con el servidor (el workflow además aborta antes de compilar).
//
// CAMBIO (2/oct/2026, 13): se aplica .trim(). Al pegar el secreto en GitHub se coló un salto de
// línea al final; dentro del BuildConfig.java generado partía el string en dos líneas y javac
// fallaba con "unclosed string literal". La clave es hexadecimal, así que recortar espacios y
// saltos de línea nunca altera su valor real (y coincide con la de la tabla config de Supabase).
val firmaSecreto: String = (System.getenv("CMK_FIRMA_SECRETO")
    ?: (project.findProperty("cmk.firma") as String?)
    ?: "").trim()

// Modo depuración del sensor (avisos Toast en MainActivity). POR DEFECTO ES false: antes era una
// constante escrita a mano en MainActivity.java que había que acordarse de apagar, y como cada push
// a main compila, el APK "normal" salía con los avisos. Ahora solo se enciende a propósito:
//   - en GitHub: Actions > Compilar APK > Run workflow > marcar "depurar_sensor";
//   - en tu computadora: `gradle assembleRelease -Pcmk.depurar=true`.
val depurarSensor: Boolean =
    System.getenv("CMK_DEPURAR_SENSOR") == "true" || project.findProperty("cmk.depurar") == "true"

// Firma ESTABLE del APK. Sin esto, cada build en GitHub Actions usa un debug.keystore nuevo
// (el runner es efímero), y Android rechaza instalar un APK encima de otro con distinta firma:
// los alumnos tendrían que desinstalar antes de actualizar. Con una llave fija (que vive solo en
// secretos de GitHub, ver README "Firma estable del APK") las actualizaciones se instalan encima.
//
// CAMBIO (5/oct/2026, 13): para el APK que se REPARTE la llave es obligatoria: el workflow aborta
// si falta (ver build.yml). Aquí, al compilar en tu computadora sin llave, el release se firma con
// la llave de depuración para que al menos se pueda probar, y se avisa. Ese APK NO se reparte.
val keystoreArchivo: String = System.getenv("CMK_KEYSTORE_FILE") ?: ""
val firmaEstable: Boolean = keystoreArchivo.isNotEmpty()
if (!firmaEstable) {
    logger.warn("AVISO: sin llave de firma estable (CMK_KEYSTORE_FILE). El release se firma con la llave de depuración: solo para pruebas locales, NO repartir.")
}

android {
    // Identificador interno del código. Debe coincidir con el `package` de
    // MainActivity.java y con la carpeta src/main/java/com/prismalmesh/checkmyknowledge/.
    namespace = "com.prismalmesh.checkmyknowledge"
    compileSdk = 34

    defaultConfig {
        // Identificador único de la app en el celular/Play Store. Cambiarlo hace
        // que Android la trate como una app distinta (no actualiza la instalada).
        // También es el "nombre de paquete" que se registra ante Google en la verificación
        // de desarrolladores: si lo cambias, hay que registrar el nuevo.
        applicationId = "com.prismalmesh.checkmyknowledge"
        minSdk = 24      // Android 7.0 en adelante
        targetSdk = 34
        // versionCode debe subir en cada versión que quieras que Android
        // reconozca como actualización; versionName es solo el texto visible.
        versionCode = 4
        versionName = "0.4"

        // Expone la clave como BuildConfig.FIRMA_SECRETO (la lee MainActivity). Es hexadecimal,
        // así que no necesita escapes dentro de las comillas (y ya viene sin saltos de línea).
        buildConfigField("String", "FIRMA_SECRETO", "\"$firmaSecreto\"")
        // Expone el modo depuración como BuildConfig.DEPURAR_SENSOR (la lee MainActivity).
        buildConfigField("boolean", "DEPURAR_SENSOR", depurarSensor.toString())
    }

    // Solo se define la firma si llegó la llave; si no, el bloque queda vacío.
    signingConfigs {
        if (firmaEstable) {
            create("cmk") {
                storeFile = file(keystoreArchivo)
                storePassword = System.getenv("CMK_KEYSTORE_PASS")
                keyAlias = System.getenv("CMK_KEY_ALIAS")
                keyPassword = System.getenv("CMK_KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (firmaEstable) signingConfig = signingConfigs.getByName("cmk")
        }
        // APK que se reparte. isDebuggable = false quita la bandera "debuggable" (la que más
        // alarma a Play Protect y la que permitiría inspeccionar la app). isMinifyEnabled = false
        // a propósito: la app no tiene librerías y ofuscar obligaría a mantener reglas para los
        // métodos del puente JS (@JavascriptInterface); no aporta aquí.
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = if (firmaEstable) signingConfigs.getByName("cmk") else signingConfigs.getByName("debug")
        }
    }

    // AGP 8.x ya no genera BuildConfig por defecto: hay que pedirlo.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No hay bloque `dependencies`: la app no usa librerías externas. Todo es la
// Activity nativa + WebView + HTML/JS en assets/. Si agregas librerías, hazlo aquí.
