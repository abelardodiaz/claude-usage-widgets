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

    public static void logout(Context ctx) {
        // F4: envolver esto en synchronized (UsageRefresher.LOCK) para no borrar mientras un
        // refresco escribe.
        Context app = ctx.getApplicationContext();

        new SessionStore(app).clear();                            // cookie cifrada + llave Keystore

        // Preferencias: manual_org (uuid de cuenta) y lo que se anada. commit() y no apply():
        // tiene que estar en disco cuando el metodo vuelve.
        app.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        app.deleteSharedPreferences(SettingsActivity.PREFS);      // y el archivo mismo

        // Datos del WebView: cookies de claude.ai y almacenamiento web.
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();

        deleteContents(app.getCacheDir());                        // cache (incluye la del WebView)

        // F4: borrar aqui samples.jsonl (SampleStore.clear()) y el ultimo modelo/orgs/hora
        // (SnapshotStore.clear()).
        WidgetUpdateJob.cancel(app);
        // F4: pasar Snapshot.of(Snapshot.Problem.NO_SESSION) para que los widgets muestren "sin sesion".
        WidgetUpdateJob.pushToWidgets(app);
    }

    private static void deleteContents(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            deleteContents(k);
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }
}
