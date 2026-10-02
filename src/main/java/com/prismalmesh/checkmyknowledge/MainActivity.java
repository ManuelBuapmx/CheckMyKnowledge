package com.prismalmesh.checkmyknowledge;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Única Activity de la app. Es un "cascarón" nativo que:
 *   1. Muestra en un WebView la app real del alumno (assets/index.html).
 *   2. Hace cumplir las reglas anti-trampa que HTML/JS no puede hacer solo:
 *      bloquear capturas, detectar que la app pierde el foco, ignorar el
 *      botón Atrás y detectar un celular pegado a la pantalla.
 *
 * REGLA CENTRAL: mientras el examen está activo (examenActivo == true),
 * cualquier cosa sospechosa llama a anularExamen(), que CIERRA la app por
 * completo. Al reabrirla, el alumno vuelve a escribir su matrícula y REANUDA
 * el MISMO intento: sus respuestas se pierden (viven solo en el WebView), pero
 * el reloj NO se reinicia ni se pausa, porque la hora de inicio vive en el
 * servidor (ver iniciar_intento en Supabase y iniciarIntento() en index.html).
 * Solo hay un intento por matrícula y materia: terminar el tiempo o entregar
 * lo cierra para siempre. Esta clase no sabe nada de esto; solo cierra la app.
 *
 * COMUNICACIÓN CON index.html: el JS llama a los métodos de la clase Puente
 * (expuesta como window.Android). iniciarExamen() y terminarExamen() marcan
 * el inicio y fin del periodo vigilado. Si cambias sus nombres, cambia
 * también las llamadas en assets/index.html.
 *
 * HILOS: los callbacks de Puente llegan en un hilo del WebView, NO en el
 * principal. Por eso tocan estado del temporizador vía runOnUiThread().
 * El listener del sensor sí corre en el hilo principal (no se le pasa Handler
 * propio al registrarlo).
 *
 * CAMBIOS (1/oct/2026): se agregó el modo de depuración del sensor
 * (DEPURAR_SENSOR) y se cambió el umbral de "cerca" a min(rangoMax, 5 cm).
 *
 * CAMBIOS (2/oct/2026): index.html se carga con origen https (loadDataWithBaseURL).
 * CAMBIO (2/oct/2026, 2): el origen ya NO es "https://www.youtube.com" (la página
 * se hacía pasar por YouTube y el reproductor respondía error 152-4). Ahora es
 * "https://" + el package de la app, que es como YouTube pide que se identifique
 * una app que embebe sus videos. Ver origenBase().
 * CAMBIO (2/oct/2026, 3): solo comentarios. Se corrigió la descripción de qué pasa
 * al reabrir la app tras una anulación (ahora reanuda el intento con el reloj del
 * servidor). La lógica de esta clase no cambió.
 * CAMBIO (2/oct/2026, 4): FIRMA DE LA APP. Puente.firmar() firma con HMAC-SHA256 las llamadas de
 * index.html al servidor, usando la clave BuildConfig.FIRMA_SECRETO (se inyecta al compilar desde
 * el secreto CMK_FIRMA_SECRETO de GitHub; NUNCA va en el repo). El servidor (Supabase) conoce la
 * misma clave y rechaza llamadas sin firma válida, así el examen no se puede presentar sin esta
 * app. Además se desactiva la depuración remota del WebView: con ella, quien conecte el celular
 * a una computadora podría ejecutar JS dentro de la página y llamar a Android.firmar().
 */
public class MainActivity extends Activity {

    private WebView webView;

    // ------------------------------------------------------------------
    // ORIGEN "https" PARA LA PÁGINA (necesario para los videos de YouTube)
    // ------------------------------------------------------------------
    // Una página file:// no tiene origen ni Referer válidos, y YouTube rechaza
    // reproducir videos embebidos así (error de configuración 153). Por eso
    // index.html se lee de assets y se carga con loadDataWithBaseURL, que le da
    // a la página un origen https; el iframe de YouTube envía entonces un
    // Referer válido.
    //
    // El origen debe identificar A NUESTRA APP, no a YouTube: con
    // "https://www.youtube.com" el reproductor respondía error 152-4. El formato
    // recomendado por YouTube para apps es "https://<applicationId>".
    // Si algún día vuelve a fallar, esta función es lo único que hay que tocar
    // (p. ej. devolver "https://localhost/").
    private String origenBase() {
        return "https://" + getPackageName();
    }

    /** Lee un archivo de assets/ completo como texto UTF-8. */
    private String leerAsset(String nombre) throws IOException {
        try (InputStream in = getAssets().open(nombre);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /**
     * true solo entre iniciarExamen() y terminarExamen() (o hasta que se anule).
     * Es volatile porque se lee desde varios hilos (WebView y principal).
     */
    private volatile boolean examenActivo = false;

    // ------------------------------------------------------------------
    // MODO DEPURACIÓN DEL SENSOR
    // ------------------------------------------------------------------
    // Con true, la app muestra avisos (Toast) con el sensor detectado y cada
    // lectura que llega, para diagnosticar por qué "no pasa nada" en un
    // equipo concreto. Un Toast no roba el foco de la ventana, así que NO
    // dispara onWindowFocusChanged ni anula el examen por sí mismo.
    // IMPORTANTE: ponerlo en false antes de repartir el APK a los alumnos
    // (los avisos les revelarían cómo funciona la defensa).
    private static final boolean DEPURAR_SENSOR = true;

    /** Toast reutilizable: se cancela el anterior para que no se acumulen en cola. */
    private Toast toastDepuracion;

    /** Muestra un aviso solo si DEPURAR_SENSOR está activo. Llamar desde el hilo principal. */
    private void depurar(String mensaje) {
        if (!DEPURAR_SENSOR) return;
        if (toastDepuracion != null) toastDepuracion.cancel();
        toastDepuracion = Toast.makeText(this, mensaje, Toast.LENGTH_SHORT);
        toastDepuracion.show();
    }

    // ------------------------------------------------------------------
    // Detección de "celular pegado a la pantalla" (sensor de proximidad)
    // ------------------------------------------------------------------
    // Por qué sensor y no cámara + IA: la cámara es más pesada, da falsos
    // positivos (mano, funda, poca luz) y grabar la cara de un menor abre
    // temas de privacidad que no vale la pena abrir. El sensor de proximidad
    // (el que apaga la pantalla en llamadas) cubre justo este caso: si algo
    // queda pegado a la parte de arriba de la pantalla por un rato, le
    // taparon el sensor.
    //
    // Si el equipo no tiene sensor, sensorProximidad queda null y esta
    // defensa simplemente no se activa en ese equipo (no truena nada). En
    // modo depuración se avisa con un Toast para que no pase desapercibido.
    //
    // POR QUÉ UN TEMPORIZADOR (Handler) Y NO COMPARAR TIMESTAMPS:
    // muchos sensores de proximidad solo emiten un evento cuando CAMBIA el
    // estado (cerca/lejos). Si solo midiéramos el tiempo dentro de
    // onSensorChanged, tras el primer "cerca" no llegaría un segundo evento
    // y el chequeo nunca se cumpliría. Por eso, al ver "cerca" armamos un
    // temporizador de UMBRAL_CERCA_MS; si llega "lejos" antes de que se
    // cumpla, se cancela (un roce rápido no anula el examen).
    private SensorManager sensorManager;
    private Sensor sensorProximidad;

    /** Cuánto tiempo seguido debe estar "cerca" para anular el examen. */
    private static final long UMBRAL_CERCA_MS = 800;

    /**
     * Distancia máxima (cm) que se considera "cerca". Es el mismo tope que usa
     * Android internamente para apagar la pantalla en llamadas: sirve para
     * sensores que reportan distancias continuas con un rango máximo grande
     * (p. ej. 10 o 100 cm), donde "< rangoMáximo" daría falsos positivos.
     * En sensores binarios (0 = cerca, máximo = lejos) no cambia nada.
     */
    private static final float DISTANCIA_CERCA_CM = 5.0f;

    /** Handler atado al hilo principal; ahí corre el callback del temporizador. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** true si hay un callback pendiente en el Handler. Solo se toca en el hilo principal. */
    private boolean temporizadorArmado = false;

    /**
     * Última lectura del sensor (true = cerca), haya examen o no.
     * Sirve para el caso en que el sensor YA estaba tapado cuando el alumno
     * toca "Comenzar examen": no llegará ningún evento nuevo, así que
     * iniciarExamen() consulta este valor para armar el temporizador.
     */
    private boolean ultimaLecturaCerca = false;

    /**
     * Se ejecuta UMBRAL_CERCA_MS después de armarse, siempre que no se haya
     * cancelado antes (porque llegó "lejos", terminó el examen o se pausó la app).
     */
    private final Runnable anularPorProximidad = () -> {
        temporizadorArmado = false;
        depurar("Proximidad: tapado " + UMBRAL_CERCA_MS + " ms, examenActivo=" + examenActivo);
        if (examenActivo) anularExamen();
    };

    /** Arma el temporizador si no hay uno ya corriendo. Llamar solo desde el hilo principal. */
    private void armarTemporizador() {
        if (temporizadorArmado) return;
        temporizadorArmado = true;
        handler.postDelayed(anularPorProximidad, UMBRAL_CERCA_MS);
    }

    /** Cancela el temporizador pendiente (si lo hay). Llamar solo desde el hilo principal. */
    private void cancelarTemporizador() {
        handler.removeCallbacks(anularPorProximidad);
        temporizadorArmado = false;
    }

    private final SensorEventListener escuchaProximidad = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (sensorProximidad == null) return;
            // "Cerca" = lectura menor al menor entre el rango máximo del sensor
            // y DISTANCIA_CERCA_CM. Muchos equipos solo reportan dos valores
            // (0 = cerca, máximo = lejos); esta comparación funciona para ambos
            // tipos de sensor (binario y continuo).
            float umbral = Math.min(sensorProximidad.getMaximumRange(), DISTANCIA_CERCA_CM);
            boolean cerca = event.values.length > 0 && event.values[0] < umbral;
            ultimaLecturaCerca = cerca;

            depurar("Proximidad: valor=" + (event.values.length > 0 ? event.values[0] : -1)
                    + " umbral=" + umbral + " cerca=" + cerca + " examenActivo=" + examenActivo);

            if (!examenActivo) { cancelarTemporizador(); return; }
            if (cerca) armarTemporizador(); else cancelarTemporizador();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FLAG_SECURE: Android bloquea capturas y grabación de pantalla, y la
        // app aparece en negro en la vista de Recientes.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        // Evita que la pantalla se apague sola a mitad del examen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorProximidad = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        // Sin inspección remota (chrome://inspect). Los APK de depuración la habilitan por
        // defecto; se apaga explícitamente porque permitiría llamar a Android.firmar() a mano.
        WebView.setWebContentsDebuggingEnabled(false);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ajustes = webView.getSettings();
        ajustes.setJavaScriptEnabled(true);   // index.html es JS; necesario
        ajustes.setAllowFileAccess(false);    // cerrado a propósito...
        ajustes.setAllowContentAccess(false);
        // El reproductor embebido de YouTube usa almacenamiento DOM; sin esto puede
        // negarse a iniciar. No da acceso a archivos ni a otras páginas.
        ajustes.setDomStorageEnabled(true);
        // ...file:///android_asset/ NO depende de setAllowFileAccess, por eso
        // el loadUrl() de respaldo de abajo sigue funcionando.

        // Sin menú de copiar/pegar por pulsación larga.
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);

        // El alumno no puede navegar a ningún otro sitio desde la app.
        // OJO: esto NO afecta al iframe de YouTube que index.html embebe como
        // contexto de una pregunta: un iframe no pasa por este método.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });

        // Expone la clase Puente al JS como window.Android.
        webView.addJavascriptInterface(new Puente(), "Android");
        // Se carga con origen https propio (ver origenBase()). Si por algo no se pudo
        // leer el asset, se cae al método anterior para que la app al menos abra
        // (sin videos).
        try {
            webView.loadDataWithBaseURL(origenBase(), leerAsset("index.html"), "text/html", "UTF-8", null);
        } catch (IOException e) {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    /**
     * Métodos que index.html puede llamar como Android.iniciarExamen(), etc.
     * Todos llegan en un hilo del WebView, así que el estado del temporizador
     * se toca dentro de runOnUiThread (hilo principal).
     */
    public class Puente {

        /** El alumno pasó la pantalla de nombre y empezó el examen: comienza la vigilancia. */
        @JavascriptInterface
        public void iniciarExamen() {
            runOnUiThread(() -> {
                examenActivo = true;
                depurar("Examen iniciado (vigilancia activa)");
                // Si el sensor ya estaba tapado, no llegará un evento nuevo: armamos aquí.
                if (ultimaLecturaCerca) armarTemporizador();
            });
        }

        /** El alumno contestó la última pregunta: termina la vigilancia (para poder mostrar el resultado). */
        @JavascriptInterface
        public void terminarExamen() {
            runOnUiThread(() -> {
                examenActivo = false;
                cancelarTemporizador();
            });
        }

        /** Botón "Salir" de la pantalla final: cierra la app y la quita de Recientes. */
        @JavascriptInterface
        public void salir() { runOnUiThread(() -> finishAndRemoveTask()); }

        /**
         * Firma una llamada al servidor. Devuelve "ts:firma" (ts = segundos Unix; firma = HMAC-SHA256
         * en hexadecimal minúscula de "accion|matricula|materiaId|ts"), o "" si no se puede firmar.
         * El servidor calcula lo MISMO con su copia de la clave y compara (ver _verificar_firma en
         * Supabase): el formato del texto firmado debe coincidir exactamente en ambos lados.
         *
         * Solo firma las tres acciones que usa index.html. No corre en el hilo principal (es una
         * llamada síncrona desde el hilo del WebView) y no toca estado compartido, así que es seguro.
         * Si la clave quedó vacía (APK compilado sin el secreto), devuelve "" y el servidor rechazará
         * la llamada: preferible a un APK que parezca funcionar y no pueda calificar a nadie.
         */
        @JavascriptInterface
        public String firmar(String accion, String matricula, String materiaId) {
            if (!"iniciar".equals(accion) && !"preguntas".equals(accion) && !"calificar".equals(accion)) return "";
            String secreto = BuildConfig.FIRMA_SECRETO;
            if (secreto == null || secreto.isEmpty() || matricula == null || materiaId == null) return "";
            try {
                long ts = System.currentTimeMillis() / 1000L;
                String dato = accion + "|" + matricula + "|" + materiaId + "|" + ts;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] hash = mac.doFinal(dato.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) hex.append(String.format("%02x", b));
                return ts + ":" + hex;
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorProximidad != null) {
            // SENSOR_DELAY_UI basta: solo nos importa el cambio cerca/lejos.
            boolean registrado = sensorManager.registerListener(
                    escuchaProximidad, sensorProximidad, SensorManager.SENSOR_DELAY_UI);
            // Diagnóstico: nombre del sensor, rango máximo y si el registro tuvo éxito.
            depurar("Sensor: " + sensorProximidad.getName()
                    + " | rangoMax=" + sensorProximidad.getMaximumRange()
                    + " | registrado=" + registrado);
        } else {
            // Sin sensor, la defensa no existe en este equipo: se avisa en depuración.
            depurar("SIN SENSOR DE PROXIMIDAD en este equipo");
        }
    }

    /**
     * Pierde el foco (notificación desplegada, ventana flotante, panel de
     * ajustes rápidos, etc.) durante el examen: se anula.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && examenActivo) anularExamen();
    }

    /**
     * Va a Inicio, Recientes o cambia de app durante el examen: se anula.
     * También deja el sensor y el temporizador limpios para que no queden
     * callbacks ni lecturas viejas al volver a la app.
     */
    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(escuchaProximidad);
        cancelarTemporizador();
        ultimaLecturaCerca = false; // al volver, el sensor mandará una lectura fresca
        if (examenActivo) anularExamen();
    }

    /** Durante el examen el botón Atrás no hace nada; fuera de él se comporta normal. */
    @Override
    public void onBackPressed() {
        if (!examenActivo) super.onBackPressed();
    }

    /**
     * Anula el examen cerrando la app por completo (y quitándola de Recientes).
     * Esta clase no guarda nada: las respuestas se pierden. Al abrir la app de nuevo
     * el alumno puede reanudar el MISMO intento con su matrícula, pero el reloj del
     * servidor siguió corriendo mientras la app estuvo cerrada. Cualquier regla
     * nueva anti-trampa debería terminar llamando a este método.
     */
    private void anularExamen() {
        examenActivo = false;
        cancelarTemporizador();
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
