package com.prismalmesh.checkmyknowledge;

import android.app.Activity;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Bloquea capturas y grabación de pantalla
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
        public void iniciarExamen() { examenActivo = true; }

        @JavascriptInterface
        public void terminarExamen() { examenActivo = false; }

        @JavascriptInterface
        public void salir() { runOnUiThread(() -> finishAndRemoveTask()); }
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
