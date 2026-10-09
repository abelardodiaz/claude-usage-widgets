package com.claulimitswidgets.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.TextView;

/**
 * Login por correo en un WebView de claude.ai.
 *
 * El WebView es SOLO para el login: sin puente JS, sin cookies de terceros, solo navega a
 * claude.ai, y cuando termina se le borra todo. Las consultas periodicas van por
 * HttpURLConnection (spike A2). Esta clase no registra nada en logs: la cookie nunca sale de aqui
 * salvo hacia SessionStore (cifrada) y ya reducida a lo minimo.
 */
public class LoginActivity extends Activity {

    private static final String LOGIN_URL = "https://claude.ai/login";
    private static final String ORIGIN = "https://claude.ai";

    private WebView web;
    private TextView status;
    private SessionStore store;
    private boolean webUsed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // La pantalla muestra el formulario de acceso del dueno: que no salga en capturas ni en
        // la vista de apps recientes (restriccion 4).
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_login);
        store = new SessionStore(this);
        status = findViewById(R.id.status);
        web = findViewById(R.id.web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // claude.ai no carga sin JS
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);       // nada de file:// con JS activado
        s.setAllowContentAccess(false);    // ni content://
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Sin addJavascriptInterface: SECURITY.md regla 5.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);   // restriccion global 6

        // Solo claude.ai se carga en el WebView. Todo lo demas se descarta: ni se carga ni se
        // entrega a otra app (un intent: o market: desde una pagina remota saldria de la app), y
        // abrir Google en el navegador del sistema no sirve para este login.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !isClaude(req.getUrl());   // false: lo carga el WebView; true: no se carga
            }
        });

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startLogin());
        ((Button) findViewById(R.id.btn_done)).setOnClickListener(v -> finishLogin());
        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> logout());
        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Con sesion, la intro deja de ser un tutorial y pasa a ser el panel de la cuenta:
        // si no, Ajustes queda inalcanzable y "cerrar sesion" escondido tras el WebView.
        // btn_probe queda oculto: la prueba de conexion necesita la consulta de F4.
        boolean signedIn = store.hasSession();
        ((TextView) findViewById(R.id.intro_status)).setText(
                signedIn ? R.string.login_ok : R.string.login_steps);
        findViewById(R.id.btn_settings).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_logout_intro).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btn_logout_intro)).setOnClickListener(v -> {
            boolean ok = Session.logout(this);
            wipeWebView();        // el WebView pudo quedar con cookies de un login anterior
            if (ok) {
                recreate();
            } else {
                // No se cierra la pantalla como si hubiera salido bien.
                ((TextView) findViewById(R.id.intro_status)).setText(R.string.logout_failed);
            }
        });
        ((Button) findViewById(R.id.btn_start)).setText(
                signedIn ? R.string.login_again : R.string.login_start);
    }

    @Override
    protected void onDestroy() {
        // Si el usuario se va a medio login, el jarro del WebView no se queda con su sesion.
        if (webUsed) {
            wipeWebView();
        }
        super.onDestroy();
    }

    private void startLogin() {
        webUsed = true;
        findViewById(R.id.intro).setVisibility(View.GONE);
        findViewById(R.id.web_pane).setVisibility(View.VISIBLE);
        web.loadUrl(LOGIN_URL);
    }

    /** El usuario dice que ya entro. Se comprueba leyendo la cookie, no creyendole. */
    private void finishLogin() {
        status.setText(R.string.login_checking);
        // El UA se lee en el hilo de UI: `web.getSettings()` no se toca desde otro hilo.
        final String ua = userAgent(web);
        String all = CookieManager.getInstance().getCookie(ORIGIN);
        String minimal = UsageClient.minimalCookies(all);
        if (!minimal.contains("sessionKey=")) {
            status.setText(R.string.login_no_session);
            return;
        }
        try {
            store.save(minimal);           // se guarda YA reducida al minimo
            // El UA del WebView es el que usaran las consultas nativas: una sola huella hacia
            // claude.ai. Se guarda aqui porque un JobService no puede crear un WebView.
            getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).edit()
                    .putString("user_agent", ua).apply();
            // F4: aqui engancha UsageRefresher.clearBackoff(this): volver a entrar arregla el
            // problema y la espera acumulada ya no aplica.
        } catch (Exception e) {
            // El mensaje de la excepcion podria arrastrar material sensible: no se muestra.
            status.setText(R.string.login_no_session);
            return;
        }
        wipeWebView();
        status.setText(R.string.login_ok);
        // F3: ambos son esqueleto y no hacen nada hasta F4.
        WidgetUpdateJob.schedule(this);
        WidgetUpdateJob.runNow(this);
    }

    private void logout() {
        boolean ok = Session.logout(this);
        wipeWebView();
        status.setText(ok ? R.string.login_no_session : R.string.logout_failed);
    }

    /** Solo https hacia claude.ai o un subdominio suyo. */
    private static boolean isClaude(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String h = u.getHost();
        return h != null && (h.equals("claude.ai") || h.endsWith(".claude.ai"));
    }

    /**
     * Tras el login el WebView no debe conservar nada. `removeAllCookies` es asincrono: el
     * `flush` va DENTRO del callback o se escribe en disco lo que se acaba de borrar.
     * Y con DOM storage activado hay que borrar tambien localStorage e IndexedDB.
     */
    @SuppressWarnings("deprecation")   // clearFormData y clearHttpAuth...: sin sustituto
    private void wipeWebView() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> cm.flush());
        WebStorage.getInstance().deleteAllData();          // localStorage e IndexedDB
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        web.clearCache(true);
        web.clearHistory();
        web.clearFormData();
        web.loadUrl("about:blank");
    }

    /** El UA del WebView es el que usan las consultas nativas: una sola huella. */
    static String userAgent(WebView web) {
        return web.getSettings().getUserAgentString();
    }
}
