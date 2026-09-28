package com.prismalmesh.checkmyknowledge;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView webView;
    private volatile boolean examenActivo = false;

    // --- Detección de "celular pegado a la pantalla" ---
    // No usamos cámara + IA para esto: es más pesado, menos confiable (falsos
    // positivos con la mano, la funda, poca luz) y capturar video de la cara
    // de un menor trae temas de privacidad que no vale la pena abrir aquí.
    // El sensor de proximidad (el mismo que apaga la pantalla en llamadas)
    // resuelve justo este caso: si algo queda pegado/muy cerca de la parte de
    // arriba de la pantalla por un rato, seguramente le taparon el sensor.
    private SensorManager sensorManager;
    private Sensor sensorProximidad;
    private long cercaDesdeMs = 0L;
    private static final long UMBRAL_CERCA_MS = 800; // tiene que estar "cerca" sostenido, no solo un roce

    private final SensorEventListener escuchaProximidad = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!examenActivo || sensorProximidad == null) { cercaDesdeMs = 0L; return; }
            boolean cerca = event.values.length > 0 && event.values[0] < sensorProximidad.getMaximumRange();
            long ahora = System.currentTimeMillis();
            if (cerca) {
                if (cercaDesdeMs == 0L) cercaDesdeMs = ahora;
                else if (ahora - cercaDesdeMs >= UMBRAL_CERCA_MS) {
                    cercaDesdeMs = 0L;
                    if (examenActivo) anularExamen();
                }
            } else {
                cercaDesdeMs = 0L;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Bloquea capturas y grabación de pantalla
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorProximidad = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        // Si el equipo no trae sensor de proximidad, sensorProximidad queda null
        // y simplemente no se activa esta defensa en ese equipo (no truena nada).

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ajustes = webView.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setAllowFileAccess(false);
        ajustes.setAllowContentAccess(false);

        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // no navegar a ningún otro sitio
            }
        });
        webView.addJavascriptInterface(new Puente(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class Puente {
        @JavascriptInterface
        public void iniciarExamen() { examenActivo = true; cercaDesdeMs = 0L; }

        @JavascriptInterface
        public void terminarExamen() { examenActivo = false; cercaDesdeMs = 0L; }

        @JavascriptInterface
        public void salir() { runOnUiThread(() -> finishAndRemoveTask()); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorProximidad != null) {
            sensorManager.registerListener(escuchaProximidad, sensorProximidad, SensorManager.SENSOR_DELAY_UI);
        }
    }

    // Si pierde el foco (notificaciones, ventana flotante, etc.) durante el examen
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && examenActivo) anularExamen();
    }

    // Si va a Inicio, Recientes o cambia de app durante el examen
    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(escuchaProximidad);
        if (examenActivo) anularExamen();
    }

    // El botón Atrás no hace nada durante el examen
    @Override
    public void onBackPressed() {
        if (!examenActivo) super.onBackPressed();
    }

    private void anularExamen() {
        examenActivo = false;
        finishAndRemoveTask(); // cierra la app; al abrirla empieza de cero
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
