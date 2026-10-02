package com.claudewidgets.spike;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike W0 / criterio A2: averigua si un APK en Java puro puede iniciar sesion en
 * claude.ai dentro de un WebView y consultar el endpoint de uso descubierto en A1.
 *
 * Regla no negociable (SECURITY.md): de la cookie y del UUID de organizacion solo
 * se registran metadatos (cuantas hay, como se llaman). Sus valores no se escriben
 * nunca en la pantalla ni en logcat.
 */
public class MainActivity extends Activity {

    static final String TAG = "SpikeW0";
    static final String LOGIN_URL = "https://claude.ai/login";
    static final String ORGS_URL = "https://claude.ai/api/organizations";
    static final String ORIGIN = "https://claude.ai";

    /** Cualquier UUID que se cuele en un mensaje de error se tapa antes de registrarlo. */
    static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private WebView web;
    private TextView logView;
    private ScrollView logScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        web = findViewById(R.id.web);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Sin addJavascriptInterface: el resultado del fetch se recoge por evaluateJavascript.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                log("pagina cargada: " + hostOf(url));
            }
        });

        ((Button) findViewById(R.id.btn_login)).setOnClickListener(v -> {
            log("A2.1 cargando " + LOGIN_URL);
            web.loadUrl(LOGIN_URL);
        });
        ((Button) findViewById(R.id.btn_cookie)).setOnClickListener(v -> probeCookie("A2.2"));
        ((Button) findViewById(R.id.btn_native)).setOnClickListener(v -> probeNative());
        ((Button) findViewById(R.id.btn_webview)).setOnClickListener(v -> probeWebView());
        ((Button) findViewById(R.id.btn_widget)).setOnClickListener(v -> requestPin());

        log("arranque: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        log("UA del WebView: " + s.getUserAgentString());
        // A2.5: al abrir la app se mira la cookie antes de tocar nada, para saber si
        // sobrevivio al cierre anterior.
        probeCookie("A2.5 arranque");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Sin flush la cookie puede quedarse solo en memoria y A2.5 daria un falso negativo.
        CookieManager.getInstance().flush();
    }

    // ---------------------------------------------------------------- A2.2 / A2.5

    private void probeCookie(String tag) {
        String raw = CookieManager.getInstance().getCookie(ORIGIN);
        if (raw == null) {
            log(tag + ": cookie=null (sin sesion)");
            return;
        }
        List<String> names = new ArrayList<>();
        boolean hasSessionKey = false;
        for (String piece : raw.split(";")) {
            String p = piece.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String name = eq < 0 ? p : p.substring(0, eq);
            names.add(name);
            if ("sessionKey".equals(name)) hasSessionKey = true;
        }
        Collections.sort(names);
        log(tag + ": cookies=" + names.size() + " sessionKey=" + hasSessionKey
                + " nombres=" + names);
    }

    // ------------------------------------------------------------------- A2.3

    /** Respuesta reducida a lo que se puede registrar sin exponer datos de la cuenta. */
    private static final class Res {
        int code;
        String ctype;
        String server;
        List<String> headerNames = new ArrayList<>();
        String body = "";
    }

    private void probeNative() {
        final String cookie = CookieManager.getInstance().getCookie(ORIGIN);
        final String ua = web.getSettings().getUserAgentString();
        if (cookie == null) {
            log("A2.3: cookie=null, inicia sesion primero");
            return;
        }
        new Thread(() -> {
            try {
                Res orgs = httpGet(ORGS_URL, cookie, ua);
                post("A2.3 nativo /organizations: HTTP=" + orgs.code + " ctype=" + orgs.ctype
                        + " server=" + orgs.server);
                post("A2.6 nativo cabeceras=" + orgs.headerNames);
                if (orgs.code != 200) {
                    post("A2.3 nativo: cuerpo de " + orgs.body.length() + " bytes, no se sigue a /usage");
                    post("A2.3 nativo inicio del cuerpo: " + firstChars(orgs.body, 160));
                    return;
                }
                JSONArray arr = new JSONArray(orgs.body);
                post("A2.6 nativo organizaciones: n=" + arr.length()
                        + " claves=" + keysOf(arr.optJSONObject(0)));
                String uuid = arr.getJSONObject(0).getString("uuid"); // nunca se registra
                Res usage = httpGet(ORGS_URL + "/" + uuid + "/usage", cookie, ua);
                post("A2.3 nativo /usage: HTTP=" + usage.code + " ctype=" + usage.ctype
                        + " server=" + usage.server);
                post("A2.6 nativo cabeceras=" + usage.headerNames);
                if (usage.code == 200) {
                    post("A2.3 nativo /usage claves=" + keysOf(new JSONObject(usage.body)));
                } else {
                    post("A2.3 nativo /usage inicio del cuerpo: " + firstChars(usage.body, 160));
                }
            } catch (Exception e) {
                post("A2.3 nativo ERROR " + e.getClass().getSimpleName() + ": " + scrub(e.getMessage()));
            }
        }, "spike-native").start();
    }

    private static Res httpGet(String url, String cookie, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8");
            c.setRequestProperty("Referer", ORIGIN + "/");

            Res r = new Res();
            r.code = c.getResponseCode();
            r.ctype = c.getHeaderField("content-type");
            r.server = c.getHeaderField("server");
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) r.headerNames.add(e.getKey().toLowerCase(Locale.ROOT));
            }
            Collections.sort(r.headerNames);
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- A2.4

    /**
     * Misma consulta desde dentro del WebView. El JS deja el resumen en una variable
     * global y Java la sondea: asi no hace falta addJavascriptInterface, que esta
     * prohibido por SECURITY.md.
     */
    private void probeWebView() {
        String js =
            "(function(){window.__spike='';var out={};"
          + "function sum(r,t){var o=null;try{o=JSON.parse(t)}catch(e){}"
          + "var h=[];r.headers.forEach(function(v,k){h.push(k)});"
          + "var s={status:r.status,ctype:r.headers.get('content-type'),"
          + "server:r.headers.get('server'),hdrs:h.sort()};"
          + "if(Array.isArray(o)){s.n=o.length;s.keys=o.length?Object.keys(o[0]).sort():[]}"
          + "else if(o&&typeof o==='object'){s.keys=Object.keys(o).sort()}"
          + "else{s.nojson=true;s.len=t.length}return [s,o]}"
          + "fetch('" + ORGS_URL + "',{credentials:'include'}).then(function(r){"
          + "return r.text().then(function(t){var p=sum(r,t);out.orgs=p[0];"
          + "if(!Array.isArray(p[1])||!p[1].length){window.__spike=JSON.stringify(out);return}"
          + "return fetch('" + ORGS_URL + "/'+p[1][0].uuid+'/usage',{credentials:'include'})"
          + ".then(function(r2){return r2.text().then(function(t2){"
          + "out.usage=sum(r2,t2)[0];window.__spike=JSON.stringify(out)})})})})"
          + ".catch(function(e){out.error=String(e);window.__spike=JSON.stringify(out)});})()";
        log("A2.4 lanzando fetch dentro del WebView (origen actual: " + hostOf(web.getUrl()) + ")");
        web.evaluateJavascript(js, null);
        pollWebView(0);
    }

    private void pollWebView(final int tries) {
        if (tries > 80) {
            log("A2.4 FALLA: sin respuesta tras 24 s");
            return;
        }
        web.evaluateJavascript("window.__spike||''", value -> {
            String s = unquote(value);
            if (s.isEmpty()) {
                handler.postDelayed(() -> pollWebView(tries + 1), 300);
            } else {
                log("A2.4 webview: " + scrub(s));
            }
        });
    }

    /** evaluateJavascript devuelve el valor codificado como literal JSON. */
    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return "";
        try {
            return new JSONArray("[" + v + "]").getString(0);
        } catch (Exception e) {
            return v;
        }
    }

    // --------------------------------------------------------------------- B.1

    private void requestPin() {
        AppWidgetManager awm = getSystemService(AppWidgetManager.class);
        ComponentName cn = new ComponentName(this, HelloWidgetProvider.class);
        if (awm != null && awm.isRequestPinAppWidgetSupported()) {
            boolean ok = awm.requestPinAppWidget(cn, null, null);
            log("B.1 requestPinAppWidget devolvio " + ok);
        } else {
            log("B.1 el lanzador no soporta anclar widgets; hay que arrastrarlo a mano");
        }
    }

    // ------------------------------------------------------------------ utiles

    private static String keysOf(JSONObject o) {
        if (o == null) return "[]";
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        return keys.toString();
    }

    /** Tapa cualquier UUID: un mensaje de error puede traer la URL completa. */
    private static String scrub(String s) {
        if (s == null) return "(sin mensaje)";
        Matcher m = UUID_RE.matcher(s);
        return m.replaceAll("{org_uuid}");
    }

    private static String firstChars(String s, int n) {
        String t = scrub(s).replace('\n', ' ');
        return t.length() <= n ? t : t.substring(0, n) + "...";
    }

    /** De una URL solo se registra el host: la ruta puede llevar el UUID. */
    private static String hostOf(String url) {
        if (url == null) return "(ninguna)";
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "(no es URL)";
        }
    }

    private void post(String msg) {
        handler.post(() -> log(msg));
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        logView.append(msg + "\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
