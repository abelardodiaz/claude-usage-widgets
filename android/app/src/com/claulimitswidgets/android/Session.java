package com.claulimitswidgets.android;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;

import java.io.File;

/**
 * Cerrar sesion. Un solo sitio, llamado desde el login y desde los ajustes.
 *
 * Borra TODO lo que la app sabe del usuario y existe hoy: la cookie cifrada y su llave, las
 * preferencias (organizacion manual incluida), los datos del WebView que no necesitan una
 * instancia (cookies, almacenamiento web, credenciales de HTTP auth) y la cache de la app.
 * Lo que SI necesita una instancia de WebView (su cache propia, historial y datos de formulario)
 * lo borra LoginActivity.wipeWebView, que tiene la instancia; Ajustes no puede. Si manana se
 * guarda algo nuevo del usuario, se borra aqui o el "cerrar sesion" se vuelve mentira.
 */
public final class Session {

    private Session() {}

    /**
     * Devuelve true solo si TODO se borro. Si algo falla sigue con el resto y devuelve false:
     * quien llama debe avisar al usuario, no cerrar la pantalla como si hubiera salido bien.
     */
    public static boolean logout(Context ctx) {
        SampleStore samples;
        try {
            samples = samplesFor(ctx);
        } catch (RuntimeException e) {
            samples = null;   // no se pudo abrir el directorio: no se sabe si hay muestras -> false
        }
        return logout(ctx, new SessionStore(ctx), samples, snapshotFor(ctx),
                SettingsActivity.PREFS, true);
    }

    /**
     * Epoca de sesion. Cada logout la incrementa al empezar y al terminar. Un refresco guarda la
     * epoca al empezar y no escribe nada si cambio: asi un cierre de sesion que llega mientras
     * consulta no puede resucitar lo que acaba de borrarse, y sin bloquear nunca el hilo de la UI.
     */
    static final java.util.concurrent.atomic.AtomicLong EPOCH =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * El almacen de ultimo modelo/orgs/hora de produccion. Misma idea que `samplesFor`: el logout
     * borra por aqui y UsageRefresher guarda por aqui.
     */
    static SnapshotStore snapshotFor(Context ctx) {
        return new SnapshotStore(ctx);
    }

    /**
     * El almacen de muestras de produccion. UNA sola via: el logout borra por aqui y
     * UsageRefresher escribe por aqui, asi que no pueden divergir sin romper la prueba de la ruta.
     */
    static SampleStore samplesFor(Context ctx) {
        return SampleStore.of(ctx);
    }

    /**
     * Para las pruebas: almacen y preferencias inyectados; con realDevice=false no toca WebView, cache,
     * job ni widgets, para no afectar la sesion ni el aparato reales del dueno.
     */
    static boolean logout(Context ctx, SessionStore store, SampleStore samples,
                          SnapshotStore snapshot, String prefsName, boolean realDevice) {
        Context a = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        return logout(ctx, store, samples, snapshot, prefsName, realDevice,
                realDevice ? () -> WidgetUpdateJob.cancel(a) : null,
                realDevice ? s -> WidgetUpdateJob.pushToWidgets(a, s) : null);
    }

    /**
     * Con los dos pasos del aparato inyectados: `cancelJob` (cancela los trabajos) y
     * `pushWidgets` (pinta "sin sesion"). null = no hacerlo. Asi se prueba que el logout los
     * llama, y en orden, sin cancelar el job ni repintar los widgets reales del dueno.
     */
    static boolean logout(Context ctx, SessionStore store, SampleStore samples,
                          SnapshotStore snapshot, String prefsName, boolean realDevice,
                          Runnable cancelJob, java.util.function.Consumer<Snapshot> pushWidgets) {
        // NO envolver esto en synchronized (UsageRefresher.LOCK): un refresco mantiene ese candado
        // durante toda la red (hasta ~20 s por peticion y hay varias sondas), y logout se llama
        // desde el hilo de la UI = ANR. La coordinacion es la epoca: se incrementa aqui y el
        // refresco no escribe si cambio (ver EPOCH). Si hiciera falta mas, un tryLock con espera
        // corta, nunca un lock bloqueante. (SampleStore ya serializa su append/clear.)
        EPOCH.incrementAndGet();
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        boolean ok = true;

        ok &= store.clear();                                      // cookie cifrada + llave Keystore
        // Historico de uso. El almacen viene inyectado: la prueba pasa uno propio, asi que borrarlo
        // no depende de realDevice. null = no se pudo abrir el directorio: cuenta como fallo.
        ok &= samples != null && samples.clear();
        // Ultimo modelo, organizaciones conocidas y hora de la consulta: datos de la cuenta.
        // El almacen viene inyectado (por `snapshotFor` en produccion) y la prueba usa uno en OTRO
        // archivo de preferencias que el que vacia el paso siguiente: quitar esta linea la rompe.
        ok &= snapshot != null && snapshot.clear();

        // Preferencias: manual_org (uuid de cuenta) y lo que se anada. commit() y no apply():
        // tiene que estar en disco cuando el metodo vuelve.
        ok &= app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().clear().commit();
        ok &= app.deleteSharedPreferences(prefsName);             // y el archivo mismo

        if (realDevice) {
            ok &= clearWebData(app);
            ok &= deleteContents(app.getCacheDir());              // cache (incluye la del WebView)
        }

        // cancel y push tocan el job y los widgets REALES del dueno: la prueba inyecta dobles.
        if (cancelJob != null) {
            ok &= attempt(cancelJob);
        }
        // Los widgets pasan a "sin sesion". Va bajo el candado de pintado de WidgetUpdateJob: un
        // refresco que empezo antes no puede pintar despues los numeros de la cuenta cerrada.
        if (pushWidgets != null) {
            ok &= attempt(() -> pushWidgets.accept(Snapshot.of(Snapshot.Problem.NO_SESSION)));
        }
        EPOCH.incrementAndGet();   // por si un refresco empezo entre el primer incremento y los borrados
        return ok;
    }

    /**
     * Todo lo del WebView que se borra SIN una instancia: cookies, almacenamiento web y
     * credenciales de HTTP auth. NO cubre `clearCache`, `clearHistory` ni `clearFormData`, que son
     * metodos de instancia: los hace LoginActivity.wipeWebView. Nunca lanza.
     */
    @SuppressWarnings("deprecation")   // clearHttpAuthUsernamePassword: sin sustituto
    static boolean clearWebData(Context app) {
        boolean ok = attempt(() -> {
            CookieManager cm = CookieManager.getInstance();
            // removeAllCookies es asincrono: el flush va DENTRO del callback, cuando ya borro.
            cm.removeAllCookies(removed -> cm.flush());
        });
        ok &= attempt(() -> WebStorage.getInstance().deleteAllData());
        ok &= attempt(() -> WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword());
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
