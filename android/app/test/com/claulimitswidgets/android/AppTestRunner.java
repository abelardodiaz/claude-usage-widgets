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
        return report(a);
    }

    /** El texto del resultado, construido SIEMPRE desde `a.failures()` (tambien al abortar). */
    static String report(Assert a) {
        List<String> failures = a.failures();
        if (failures.isEmpty()) return "OK: " + a.checks() + " comprobaciones, 0 fallos";
        StringBuilder sb = new StringBuilder("FALLOS (" + failures.size()
                + " de " + a.checks() + "):");
        for (String f : failures) sb.append("\n  - ").append(f);
        return sb.toString();
    }

    /**
     * El camino de aborto: la suite lanzo. Anota la excepcion como un fallo mas y devuelve el
     * texto con TODOS los fallos, los que las guardas (finally) ya habian anotado incluidos: la
     * evidencia de que claves cambiaron no se pierde justo cuando la suite aborta.
     */
    static String abortText(Assert a, Throwable e) {
        a.fail("excepcion no controlada: " + e);
        return report(a);
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
     * escribir `fetched_at`, `last_*`, `backoff_*`. Pero ESA huella (`fetched_at`, `last_*`) es
     * tambien la de una consulta REAL hecha por la suite con la sesion del dueno (el peor
     * accidente), asi que NO basta con "son esas claves": solo se llama refresco real si
     * `fetched_at` AVANZO (ver {@link #verdict}). `last_*` o `backoff_*` sin que avance
     * `fetched_at` es la suite. Para no llegar al falso positivo, `job-quiet.sh` espera a que el
     * periodico no este a punto de vencer antes de `am instrument`.
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
        Map<String, Object> after = snapshot(prefs);
        List<String> changed = changedKeys(before, after);
        a.isTrue("la suite no toco las preferencias reales (N2); claves cambiadas: " + changed
                + verdict(before, after, changed), changed.isEmpty());
    }

    /** Claves que escribe un refresco real: la huella de `fetched_at`, `last_*` y `backoff_*`. */
    private static boolean refreshKey(String k) {
        return k.equals("fetched_at") || k.startsWith("last_") || k.startsWith("backoff_");
    }

    /**
     * Veredicto sobre la huella ("" si no cambio nada). Un refresco REAL del periodico escribe
     * `fetched_at` y SIEMPRE lo hace avanzar. Si cambiaron `last_*` o `backoff_*` sin que
     * `fetched_at` avance, o cambio cualquier otra clave, NO es el fantasma: es la suite (o una
     * consulta real que ella provoco), y hay que perseguirlo. Solo el primer caso es "volver a
     * correr".
     */
    static String verdict(Map<String, Object> before, Map<String, Object> after, List<String> changed) {
        if (changed.isEmpty()) return "";
        boolean onlyRefreshKeys = true;
        for (String k : changed) if (!refreshKey(k)) onlyRefreshKeys = false;
        Object f0 = before.get("fetched_at");
        Object f1 = after.get("fetched_at");
        boolean advanced = f0 instanceof Long && f1 instanceof Long && (Long) f1 > (Long) f0
                || f0 == null && f1 instanceof Long;
        if (onlyRefreshKeys && advanced) {
            return " | fetched_at AVANZO: huella de un refresco real del periodico durante la"
                    + " corrida; volver a correr (esperar con job-quiet.sh antes)";
        }
        return " | es la SUITE: fetched_at no avanzo o cambio una clave que no es de un refresco."
                + " No volver a correr hasta entender que clave cambio y quien la toco";
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

            // El veredicto: solo un fetched_at que AVANZA es "refresco real".
            inner = new Assert();
            Assert i9 = inner;
            withPrefsGuard(i9, p, () -> p.edit().putLong("fetched_at", 100L).commit());   // 7 -> 100
            a.isTrue("veredicto: fetched_at avanza -> refresco real",
                    first(inner).contains("fetched_at AVANZO") && !first(inner).contains("es la SUITE"));
            inner = new Assert();
            Assert i10 = inner;
            withPrefsGuard(i10, p, () -> p.edit().putLong("fetched_at", 100L)
                    .putString("last_session_percent", "x").commit());   // sin avanzar, con last_*
            a.isTrue("veredicto: last_* sin que fetched_at avance -> la suite",
                    first(inner).contains("es la SUITE") && !first(inner).contains("AVANZO"));
            inner = new Assert();
            Assert i11 = inner;
            withPrefsGuard(i11, p, () -> p.edit().putLong("fetched_at", 50L).commit());   // retrocede
            a.isTrue("veredicto: fetched_at retrocede -> la suite", first(inner).contains("es la SUITE"));
            inner = new Assert();
            Assert i12 = inner;
            withPrefsGuard(i12, p, () -> p.edit().putLong("fetched_at", 500L)
                    .putString("manual_org", secret).commit());   // avanza pero hay una clave ajena
            a.isTrue("veredicto: fetched_at avanza pero cambio otra clave -> la suite",
                    first(inner).contains("es la SUITE") && !first(inner).contains(secret));

            // El camino de aborto conserva los fallos que las guardas ya anotaron.
            Assert ab = new Assert();
            ab.fail("N2 claves cambiadas: [fetched_at]");
            String txt = abortText(ab, new IllegalStateException("la suite aborto"));
            a.isTrue("aborto: el texto trae el fallo de la guarda", txt.contains("N2 claves cambiadas: [fetched_at]"));
            a.isTrue("aborto: y la excepcion", txt.contains("la suite aborto"));
            a.isTrue("aborto: cuenta los dos fallos", txt.startsWith("FALLOS (2 de "));

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
