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
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

            // Red de seguridad: shouldOverrideUrlLoading NO se invoca para la carga inicial, ni
            // para las redirecciones del servidor, ni para los POST de formularios. Esto cubre
            // la navegacion principal por esas rutas.
            // A PROPOSITO no se bloquean subrecursos ni subframes (nada de shouldInterceptRequest):
            // el reto de Cloudflare vive en un iframe de otro dominio y bloquearlo romperia el
            // login en silencio. Que hosts de terceros hay que permitir se decide en la Tarea 3.7,
            // con el login real delante. No es un olvido.
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!isClaude(url == null ? null : Uri.parse(url)) && !"about:blank".equals(url)) {
                    view.stopLoading();
                    view.loadUrl("about:blank");
                }
            }
        });

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(v -> startLogin());
        ((Button) findViewById(R.id.btn_done)).setOnClickListener(v -> finishLogin());
        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> logout());
        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        ((Button) findViewById(R.id.btn_logout_intro)).setOnClickListener(v -> logout());
        // Si el sistema mato el proceso a medias de un login, sin onDestroy, el jarro del WebView
        // pudo quedarse con la sessionKey. Sin sesion guardada no hay nada que conservar: limpiar.
        if (!store.hasSession()) {
            wipeWebView();
        }
        paintIntro();
    }

    /** Al volver (p. ej. desde Ajustes) la pantalla vuelve a reflejar el estado real. */
    @Override
    protected void onResume() {
        super.onResume();
        // Tras cerrar sesion desde Ajustes, el WebView de esta instancia puede conservar cache.
        // SOLO si no hay sesion Y el panel web no esta a la vista: con el panel visible el
        // usuario esta a mitad de un login (o guardando) y no se toca nada.
        if (!store.hasSession()
                && findViewById(R.id.web_pane).getVisibility() != View.VISIBLE) {
            wipeWebView();
        }
        paintIntro();
    }

    /**
     * Pinta la intro segun el estado REAL de la sesion. Con sesion deja de ser un tutorial y pasa
     * a ser el panel de la cuenta: si no, Ajustes queda inalcanzable y "cerrar sesion" escondido
     * tras el WebView. Solo cambia textos y botones, no cual panel se ve: eso lo decide
     * {@link #showIntro()}, para que un onResume a medias de un login no tire el WebView.
     * btn_probe queda oculto: la prueba de conexion necesita la consulta de F4.
     */
    private void paintIntro() {
        boolean signedIn = store.hasSession();
        ((TextView) findViewById(R.id.intro_status)).setText(
                signedIn ? R.string.login_ok : R.string.login_steps);
        findViewById(R.id.btn_settings).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_logout_intro).setVisibility(signedIn ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btn_start)).setText(
                signedIn ? R.string.login_again : R.string.login_start);
    }

    /** Vuelve de la pagina web a la intro. */
    private void showIntro() {
        findViewById(R.id.web_pane).setVisibility(View.GONE);
        findViewById(R.id.intro).setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        // Si el usuario se va a medio login, el jarro del WebView no se queda con su sesion.
        if (webUsed) {
            wipeWebView();
            // Patron habitual: sacarlo de su padre y destruirlo evita fugar el contexto.
            if (web.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
        }
        super.onDestroy();
    }

    private void startLogin() {
        webUsed = true;
        status.setText("");   // sin restos de un intento anterior
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
        final String minimal = cookiesToSave(all);
        if (minimal == null) {
            status.setText(R.string.login_no_session);
            return;
        }
        // Keystore y sync() no van en el hilo principal. Mientras tanto el boton queda inactivo
        // para que un segundo toque no lance un segundo guardado.
        // Lo mismo con los dos botones de cerrar sesion: un logout cruzado con el guardado dejaria
        // una sesion zombi en disco o pintaria "sesion iniciada" sobre un estado sin sesion.
        final View done = findViewById(R.id.btn_done);
        final View out1 = findViewById(R.id.btn_logout);
        final View out2 = findViewById(R.id.btn_logout_intro);
        done.setEnabled(false);
        out1.setEnabled(false);
        out2.setEnabled(false);
        new Thread(() -> {
            boolean saved;
            try {
                store.save(minimal);       // se guarda YA reducida al minimo
                saved = true;
            } catch (Exception e) {
                // El mensaje de la excepcion podria arrastrar material sensible: no se muestra.
                saved = false;
            }
            final boolean ok = saved;
            runOnUiThread(() -> {
                done.setEnabled(true);
                out1.setEnabled(true);
                out2.setEnabled(true);
                // Si la pantalla se cerro mientras se guardaba, onDestroy ya limpio el WebView.
                if (closeOrFail(ok, isFinishing() || isDestroyed(), this::scheduleAfterLogin)) return;
                if (!ok) {
                    // Entro bien pero la sesion se perdio al guardarla. El mensaje promete que no
                    // quedo sesion: el WebView tampoco debe conservar la cookie.
                    wipeWebView();
                    showIntro();
                    paintIntro();
                    ((TextView) findViewById(R.id.intro_status)).setText(R.string.login_save_failed);
                    status.setText(R.string.login_save_failed);
                    return;
                }
                afterSaved(ua);
            });
        }, "login-save").start();
    }

    /** En el hilo principal y SOLO con la sesion ya guardada: ahora si se limpia el WebView. */
    private void afterSaved(String ua) {
        // Con la sesion ya guardada, un fallo aqui NO es un fallo del login.
        try {
            // El UA del WebView es el que usaran las consultas nativas: una sola huella hacia
            // claude.ai. Se guarda aqui porque un JobService no puede crear un WebView.
            getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(SettingsActivity.KEY_UA, ua).apply();
        } catch (RuntimeException ignored) {
            // Sin UA guardado las consultas llevan uno vacio: no hay respaldo a proposito (ver userAgent).
        }
        wipeWebView();
        status.setText(R.string.login_ok);   // ya no se queda en "Comprobando..."
        showIntro();
        paintIntro();      // ahora hay sesion: aparecen Ajustes y cerrar sesion
        scheduleAfterLogin();
    }

    /**
     * Con la sesion ya guardada, por las dos ramas (pantalla abierta o cerrandose). Volver a
     * entrar arregla el problema: la espera acumulada ya no aplica y ningun refresco en vuelo con
     * la cookie vieja puede contestar por el de ahora (clearBackoff invalida el unificador).
     */
    private void scheduleAfterLogin() {
        scheduleAfterLogin(() -> UsageRefresher.clearBackoff(this),
                () -> WidgetUpdateJob.schedule(this), () -> WidgetUpdateJob.runNow(this));
    }

    /** El orden importa: primero se borra la espera, luego se programa y se lanza el refresco. */
    static void scheduleAfterLogin(Runnable clearBackoff, Runnable schedule, Runnable runNow) {
        clearBackoff.run();
        // Los dos solo encolan (programan el periodico y lanzan el primer refresco en un hilo).
        schedule.run();
        runNow.run();
    }

    /**
     * Si la pantalla se cerro mientras se guardaba, onDestroy ya limpio el WebView y solo queda
     * dejar el widget funcionando (si se guardo). Devuelve true si no hay mas que hacer en pantalla.
     */
    static boolean closeOrFail(boolean saved, boolean closing, Runnable scheduleAfterLogin) {
        if (!closing) return false;
        if (saved) scheduleAfterLogin.run();
        return true;
    }

    /** true mientras un cierre de sesion esta en curso (solo se toca en el hilo principal). */
    private final java.util.concurrent.atomic.AtomicBoolean loggingOut =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void logout() {
        // El borrado (Keystore, commit, barrido de la cache, widgets) va a un hilo: en el
        // principal, con una cache de WebView grande, "Cerrar sesion" daba ANR. Aqui, en el
        // principal, solo queda lo que exige la instancia del WebView.
        // Mientras dura, los botones quedan inactivos: un doble toque lanzaba dos cierres y el
        // segundo (sin nada que borrar) podia decir "no se pudo cerrar" cuando SI se cerro; y un
        // login guardado a mitad de un cierre se quedaba sin job.
        View[] buttons = {findViewById(R.id.btn_logout), findViewById(R.id.btn_logout_intro),
                findViewById(R.id.btn_start), findViewById(R.id.btn_done)};
        guardedLogout(loggingOut, buttons, cb -> Session.logoutAsync(this, cb), ok -> {
            if (isFinishing() || isDestroyed()) return;
            wipeWebView();        // el WebView pudo quedar con cookies de un login anterior
            showIntro();
            paintIntro();         // refleja el estado real, no el que se esperaba
            if (!ok) {
                // No se cierra la pantalla como si hubiera salido bien.
                ((TextView) findViewById(R.id.intro_status)).setText(R.string.logout_failed);
            }
        });
    }

    /**
     * El cierre de sesion de un solo toque. Devuelve false (y no hace nada) si ya hay uno en
     * curso. Los botones se reactivan ANTES de `after`, y tambien si el cierre ni arranca: nunca
     * quedan muertos. Aparte de la pantalla para poder probarlo sin cerrar ninguna sesion.
     */
    static boolean guardedLogout(java.util.concurrent.atomic.AtomicBoolean busy, View[] buttons,
                                 java.util.function.Consumer<java.util.function.Consumer<Boolean>> start,
                                 java.util.function.Consumer<Boolean> after) {
        if (!busy.compareAndSet(false, true)) return false;
        setEnabled(buttons, false);
        try {
            start.accept(ok -> {
                setEnabled(buttons, true);
                busy.set(false);
                after.accept(ok);
            });
        } catch (RuntimeException | Error e) {
            setEnabled(buttons, true);
            busy.set(false);
        }
        return true;
    }

    private static void setEnabled(View[] views, boolean enabled) {
        for (View v : views) v.setEnabled(enabled);
    }

    /**
     * Lo unico que se guarda del jarro, o null si no hay sesion que guardar (sin `sessionKey`
     * con valor no se escribe nada en el almacen).
     */
    static String cookiesToSave(String all) {
        String minimal = UsageClient.minimalCookies(all);
        return hasSessionKey(minimal) ? minimal : null;
    }

    /**
     * Hay una pieza `sessionKey=` con valor, en CUALQUIER posicion: minimalCookies conserva el
     * orden de entrada y CookieManager no garantiza que sessionKey vaya primero.
     */
    static boolean hasSessionKey(String minimal) {
        if (minimal == null) return false;
        for (String piece : minimal.split(";")) {
            String p = piece.trim();
            if (p.startsWith("sessionKey=") && p.length() > "sessionKey=".length()) return true;
        }
        return false;
    }

    /** Solo https hacia claude.ai o un subdominio suyo. */
    static boolean isClaude(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        // Solo el puerto por omision de https: otro puerto es otro servicio.
        if (u.getPort() != -1 && u.getPort() != 443) return false;
        String h = u.getHost();
        if (h == null) return false;
        h = h.toLowerCase(java.util.Locale.ROOT);
        return h.equals("claude.ai") || h.endsWith(".claude.ai");
    }

    /**
     * Tras el login el WebView no debe conservar nada. `removeAllCookies` es asincrono: el
     * `flush` va DENTRO del callback o se escribe en disco lo que se acaba de borrar.
     * Y con DOM storage activado hay que borrar tambien localStorage e IndexedDB.
     */
    @SuppressWarnings("deprecation")   // clearFormData: sin sustituto
    private void wipeWebView() {
        // Lo que no necesita instancia (cookies, almacenamiento web, HTTP auth) vive en Session,
        // para que los dos caminos de logout borren lo mismo.
        Session.clearWebData(this);
        // Lo que SI necesita la instancia solo se puede borrar aqui.
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
