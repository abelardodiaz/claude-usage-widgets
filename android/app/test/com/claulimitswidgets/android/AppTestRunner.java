package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Corredor de las pruebas de la cascara. NO corre en una JVM: el Keystore y el almacenamiento
 * de la app no existen fuera del dispositivo.
 *
 * Lo lanza {@link AppInstrumentation} dentro del proceso de la app. Correrlo con `app_process`
 * NO sirve: ese proceso tiene el uid de `shell`, asi que el Keystore seria de otro usuario y
 * `filesDir` no seria escribible.
 */
public final class AppTestRunner {

    private AppTestRunner() {}

    /** Devuelve el texto del resultado; quien llama decide el codigo de salida. */
    public static String run(Assert a, android.content.Context ctx) {
        // La FOTO va en la primera linea: ni la autoprueba de las guardas queda fuera de la red.
        // La suite corre en el proceso de la app, donde puede haber un refresco REAL en vuelo: si
        // tocara el contador global de epoca, ese refresco ejecutaria su deshacer sobre los almacenes
        // de produccion. Todo lo que la suite cierra usa un contador propio; esto lo vigila. (Una
        // prueba SI sube Coalescer.SHARED.version(), a proposito y sin guarda: a lo sumo cuesta una
        // consulta extra al refresco real, y hacerlo sin eso exigiria inyectar el unificador.)
        // Igual con las preferencias REALES del dueno (cuw): se vigila el getAll() ENTERO, no una
        // lista de claves. Esta guarda de ejecucion no ve una escritura sin efecto neto: de eso se
        // ocupa la guarda estatica `android/app/guard-tests.sh`, que corre en el build de pruebas.
        withEpochGuard(a, Session.EPOCH, () ->
                withPrefsGuard(a, realPrefs(ctx), () -> suite(a, ctx)));
        List<String> failures = a.failures();
        if (failures.isEmpty()) return "OK: " + a.checks() + " comprobaciones, 0 fallos";
        StringBuilder sb = new StringBuilder("FALLOS (" + failures.size()
                + " de " + a.checks() + "):");
        for (String f : failures) sb.append("\n  - ").append(f);
        return sb.toString();
    }

    private static void suite(Assert a, android.content.Context ctx) {
        // Cada tarea que crea su prueba anade aqui su linea.
        guardSelfTest(a, ctx);
        SessionStoreTest.run(a, ctx);
        BackoffTest.run(a);
        UsageClientTest.run(a);
        OrgSelectorTest.run(a);
        SessionTest.run(a, ctx);
        LoginActivityTest.run(a);
        SampleStoreTest.run(a, ctx.getCacheDir());
        UsageRefresherTest.run(a, ctx);
        WidgetRendererTest.run(a, ctx);
        // Tarea 4.4.
        SessionStoreTest.runKeyLoss(a, ctx);
        SessionStoreTest.runTmp(a, ctx);
        WidgetUpdateJobTest.run(a, ctx);
        // Entrada de F5: la deuda de F4.
        F5DebtTest.run(a, ctx);
        // Cierre de F3.
        UsageClientTest.runCierre(a);
        OrgSelectorTest.runCierre(a);
        SessionStoreTest.runCierre(a, ctx);
    }

    private static android.content.SharedPreferences realPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS, android.content.Context.MODE_PRIVATE);
    }

    /**
     * Corre `body` y, pase lo que pase (incluida una excepcion o un Error que aborte la suite),
     * comprueba que las preferencias no cambiaron. Vigila `getAll()` entero.
     *
     * Puede fallar en falso: el periodico REAL del dueno puede dispararse durante la corrida y
     * escribir `backoff_*` (o `fetched_at`...) legitimamente. Si falla solo con esas claves y sin
     * que ninguna prueba las toque, es ese fantasma: no hay nada que perseguir, se vuelve a correr.
     * Pasa sobre todo justo tras `adb install -r`: MY_PACKAGE_REPLACED lanza un refresco REAL que
     * escribe `fetched_at`, `last_*`... mientras corre la suite (esperar ~25 s tras instalar).
     */
    static void withPrefsGuard(Assert a, android.content.SharedPreferences prefs, Runnable body) {
        Map<String, Object> before = snapshot(prefs);
        try {
            body.run();
        } finally {
            guardPrefs(a, before, prefs);
        }
    }

    /** Igual con el contador de epoca de sesion. */
    static void withEpochGuard(Assert a, java.util.concurrent.atomic.AtomicLong epoch, Runnable body) {
        long before = epoch.get();
        try {
            body.run();
        } finally {
            a.isTrue("la suite no toco Session.EPOCH (C1)", before == epoch.get());
        }
    }

    /** Todo lo que hay ahora en las preferencias (una copia). */
    static Map<String, Object> snapshot(android.content.SharedPreferences prefs) {
        return new HashMap<>(prefs.getAll());
    }

    /** Nombres (ordenados) de las claves que aparecen, desaparecen o cambian de valor. */
    static List<String> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            boolean in1 = before.containsKey(k);
            boolean in2 = after.containsKey(k);
            if (in1 != in2 || (in1 && !Objects.equals(before.get(k), after.get(k)))) out.add(k);
        }
        return out;
    }

    /**
     * Falla si algo cambio desde `before`. El mensaje trae SOLO los nombres de las claves, jamas
     * los valores: contienen el uuid de la organizacion y el user agent, y el repo es publico.
     */
    static void guardPrefs(Assert a, Map<String, Object> before, android.content.SharedPreferences prefs) {
        List<String> changed = changedKeys(before, snapshot(prefs));
        a.isTrue("la suite no toco las preferencias reales (N2); claves cambiadas: " + changed,
                changed.isEmpty());
    }

    /** El primer fallo, o "" si no hubo ninguno (para que una guarda ausente falle con nombre, no con un IOOBE). */
    private static String first(Assert x) {
        return x.failures().isEmpty() ? "" : x.failures().get(0);
    }

    /** Las guardas no sirven si no ven cambios: se prueban con preferencias de usar y tirar. */
    private static void guardSelfTest(Assert a, android.content.Context ctx) {
        String name = "cuw-guard-selftest-" + System.nanoTime();
        android.content.SharedPreferences p = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
        try {
            final String secret = "SECRETO-uuid-org-0000";
            // Cuerpo que no escribe: sin fallos.
            Assert inner = new Assert();
            withPrefsGuard(inner, p, () -> { });
            a.eq("envoltorio: cuerpo que no escribe no falla", 0, inner.failures().size());
            // Una clave nueva (que antes no existia) se ve.
            inner = new Assert();
            Assert i1 = inner;
            withPrefsGuard(i1, p, () -> p.edit().putString("manual_org", secret).commit());
            a.eq("envoltorio: clave nueva -> 1 fallo", 1, inner.failures().size());
            a.isTrue("envoltorio: el mensaje nombra la clave", first(inner).contains("manual_org"));
            a.isTrue("envoltorio: el mensaje NO trae el valor (N2/SECURITY)",
                    !first(inner).contains(secret));
            // Valor cambiado y clave quitada (cualquier clave, no una lista blanca).
            inner = new Assert();
            Assert i2 = inner;
            withPrefsGuard(i2, p, () -> p.edit().putString("manual_org", "otro-valor-xyz")
                    .putString("clave_cualquiera", "z").commit());
            a.eq("envoltorio: valor cambiado y clave ajena -> 1 fallo", 1, inner.failures().size());
            a.isTrue("envoltorio: nombra las dos y ningun valor",
                    first(inner).contains("manual_org") && first(inner).contains("clave_cualquiera")
                    && !first(inner).contains("otro-valor-xyz") && !first(inner).contains(secret));
            inner = new Assert();
            Assert i3 = inner;
            withPrefsGuard(i3, p, () -> p.edit().remove("clave_cualquiera").commit());
            a.eq("envoltorio: clave quitada -> 1 fallo", 1, inner.failures().size());
            // Una clave SOLO de SnapshotStore (fuera de las cinco de antes) tambien se ve.
            inner = new Assert();
            Assert i4 = inner;
            withPrefsGuard(i4, p, () -> p.edit().putLong("fetched_at", 7L).commit());
            a.eq("envoltorio: clave de SnapshotStore -> 1 fallo", 1, inner.failures().size());
            // Cuerpo que lanza: la guarda corre igual (finally) y la excepcion no se traga.
            inner = new Assert();
            Assert i5 = inner;
            boolean threw = false;
            try {
                withPrefsGuard(i5, p, () -> { p.edit().putString("tras_excepcion", "v").commit();
                    throw new IllegalStateException("prueba"); });
            } catch (IllegalStateException e) { threw = true; }
            a.isTrue("envoltorio: la excepcion del cuerpo sigue su camino", threw);
            a.eq("envoltorio: cuerpo que lanza -> la guarda corrio igual", 1, inner.failures().size());
            // Lo mismo con un Error (no es RuntimeException).
            inner = new Assert();
            Assert i6 = inner;
            boolean err = false;
            try {
                withPrefsGuard(i6, p, () -> { p.edit().putString("tras_error", "v").commit();
                    throw new OutOfMemoryError("prueba"); });
            } catch (OutOfMemoryError e) { err = true; }
            a.isTrue("envoltorio: el Error sigue su camino", err);
            a.eq("envoltorio: cuerpo con Error -> la guarda corrio igual", 1, inner.failures().size());

            // Epoca, con un contador de usar y tirar.
            java.util.concurrent.atomic.AtomicLong ep = new java.util.concurrent.atomic.AtomicLong();
            inner = new Assert();
            withEpochGuard(inner, ep, () -> { });
            a.eq("epoca: sin cambio no falla", 0, inner.failures().size());
            inner = new Assert();
            Assert i7 = inner;
            withEpochGuard(i7, ep, ep::incrementAndGet);
            a.eq("epoca: cuerpo que la sube -> 1 fallo", 1, inner.failures().size());
            inner = new Assert();
            Assert i8 = inner;
            boolean threw2 = false;
            try {
                withEpochGuard(i8, ep, () -> { ep.incrementAndGet(); throw new IllegalStateException("p"); });
            } catch (IllegalStateException e) { threw2 = true; }
            a.isTrue("epoca: la excepcion sigue su camino", threw2);
            a.eq("epoca: cuerpo que lanza -> la guarda corrio igual", 1, inner.failures().size());
        } finally {
            p.edit().clear().commit();
            ctx.deleteSharedPreferences(name);
        }
    }
}
