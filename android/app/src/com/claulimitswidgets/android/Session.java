package com.claulimitswidgets.android;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import java.io.File;

/**
 * Cerrar sesion. Un solo sitio, llamado desde el login y desde los ajustes.
 *
 * Borra TODO lo que la app sabe del usuario y existe hoy: la cookie cifrada y su llave, las
 * preferencias (organizacion manual incluida), los datos del WebView y la cache. Si manana se
 * guarda algo nuevo del usuario, se borra aqui o el "cerrar sesion" se vuelve mentira.
 */
public final class Session {

    private Session() {}

    /**
     * Devuelve true solo si TODO se borro. Si algo falla sigue con el resto y devuelve false:
     * quien llama debe avisar al usuario, no cerrar la pantalla como si hubiera salido bien.
     */
    public static boolean logout(Context ctx) {
        return logout(ctx, new SessionStore(ctx), SettingsActivity.PREFS, true);
    }

    /**
     * Para las pruebas: almacen y preferencias inyectados, y sin tocar WebView ni cache, para no
     * borrar la sesion ni los datos reales del dueno.
     */
    static boolean logout(Context ctx, SessionStore store, String prefsName, boolean webData) {
        // F4: envolver esto en synchronized (UsageRefresher.LOCK) para no borrar mientras un
        // refresco escribe.
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        boolean ok = true;

        ok &= store.clear();                                      // cookie cifrada + llave Keystore

        // Preferencias: manual_org (uuid de cuenta) y lo que se anada. commit() y no apply():
        // tiene que estar en disco cuando el metodo vuelve.
        ok &= app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().clear().commit();
        ok &= app.deleteSharedPreferences(prefsName);             // y el archivo mismo

        if (webData) {
            ok &= clearWebData(app);
            ok &= deleteContents(app.getCacheDir());              // cache (incluye la del WebView)
        }

        // F4: borrar aqui samples.jsonl (SampleStore.clear()) y el ultimo modelo/orgs/hora
        // (SnapshotStore.clear()).
        ok &= attempt(() -> WidgetUpdateJob.cancel(app));
        // F4: pasar Snapshot.of(Snapshot.Problem.NO_SESSION) para que los widgets muestren "sin sesion".
        ok &= attempt(() -> WidgetUpdateJob.pushToWidgets(app));
        return ok;
    }

    /** Cookies y almacenamiento del WebView. Puede lanzar si el WebView no esta disponible. */
    private static boolean clearWebData(Context app) {
        boolean ok = attempt(() -> {
            CookieManager cm = CookieManager.getInstance();
            // removeAllCookies es asincrono: el flush va DENTRO del callback, cuando ya borro.
            cm.removeAllCookies(removed -> cm.flush());
        });
        ok &= attempt(() -> WebStorage.getInstance().deleteAllData());
        return ok;
    }

    private static boolean attempt(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {   // p. ej. AndroidRuntimeException sin WebView
            return false;                // sin registrar el mensaje: no hay datos que citar
        }
    }

    private static boolean deleteContents(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return true;
        boolean ok = true;
        for (File k : kids) {
            ok &= deleteContents(k);
            ok &= k.delete();
        }
        return ok;
    }
}
