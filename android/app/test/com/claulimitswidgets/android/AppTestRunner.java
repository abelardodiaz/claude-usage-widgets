package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // Cada tarea que crea su prueba anade aqui su linea.
        // La suite corre en el proceso de la app, donde puede haber un refresco REAL en vuelo: si
        // tocara el contador global de epoca, ese refresco ejecutaria su deshacer sobre los almacenes
        // de produccion. Todo lo que la suite cierra usa un contador propio; esto lo vigila.
        long epochBefore = Session.EPOCH.get();
        // Igual con las preferencias REALES del dueno (cuw): ninguna prueba puede escribir ahi. La
        // espera del widget (backoff_*), la organizacion manual y el user agent se leen antes y
        // despues; si algo cambio, la suite falla. Por aqui se colo clearBackoff(ctx) en la 5.0.
        watchSelfTest(a, ctx);
        Map<String, Object> prefsBefore = watched(realPrefs(ctx));
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
        a.eq("la suite no toco Session.EPOCH (C1)", epochBefore, Session.EPOCH.get());
        guardPrefs(a, prefsBefore, realPrefs(ctx));
        List<String> failures = a.failures();
        if (failures.isEmpty()) return "OK: " + a.checks() + " comprobaciones, 0 fallos";
        StringBuilder sb = new StringBuilder("FALLOS (" + failures.size()
                + " de " + a.checks() + "):");
        for (String f : failures) sb.append("\n  - ").append(f);
        return sb.toString();
    }

    /** Claves de las preferencias reales que la suite vigila. */
    static final String[] WATCHED = {
        "backoff_attempt", "backoff_next_allowed_at", "backoff_last_problem",
        SettingsActivity.KEY_ORG, SettingsActivity.KEY_UA};

    private static android.content.SharedPreferences realPrefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** Lo que hay ahora en las claves vigiladas (las ausentes no aparecen). */
    static Map<String, Object> watched(android.content.SharedPreferences prefs) {
        Map<String, ?> all = prefs.getAll();
        Map<String, Object> out = new HashMap<>();
        for (String k : WATCHED) if (all.containsKey(k)) out.put(k, all.get(k));
        return out;
    }

    /** Falla si las claves vigiladas ya no valen lo que valian antes de la suite. */
    static void guardPrefs(Assert a, Map<String, Object> before, android.content.SharedPreferences prefs) {
        a.eq("la suite no toco las preferencias reales (N2)", before, watched(prefs));
    }

    /** La guarda no sirve si no ve cambios: se prueba con unas preferencias de usar y tirar. */
    private static void watchSelfTest(Assert a, android.content.Context ctx) {
        String name = "cuw-guard-selftest-" + System.nanoTime();
        android.content.SharedPreferences p = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
        try {
            a.isTrue("guarda: vacia no ve nada", watched(p).isEmpty());
            p.edit().putInt("backoff_attempt", 1).putLong("backoff_next_allowed_at", 2L)
                    .putString("backoff_last_problem", "OFFLINE").putString(SettingsActivity.KEY_ORG, "o")
                    .putString(SettingsActivity.KEY_UA, "u").putString("otra", "z").commit();
            Map<String, Object> w = watched(p);
            a.eq("guarda: ve las cinco claves y solo ellas", 5, w.size());
            p.edit().putString(SettingsActivity.KEY_UA, "v").commit();
            a.isTrue("guarda: ve un valor cambiado", !w.equals(watched(p)));
            Map<String, Object> w2 = watched(p);
            p.edit().remove("backoff_attempt").commit();
            a.isTrue("guarda: ve una clave quitada", !w2.equals(watched(p)));
            // guardPrefs falla de verdad con un cambio y calla sin el (con un Assert de usar y tirar).
            Assert inner = new Assert();
            guardPrefs(inner, watched(p), p);
            a.eq("guardPrefs: sin cambios no falla", 0, inner.failures().size());
            Map<String, Object> before = watched(p);
            p.edit().putString("backoff_last_problem", "AUTH_EXPIRED").commit();
            guardPrefs(inner, before, p);
            a.eq("guardPrefs: con un cambio falla", 1, inner.failures().size());
        } finally {
            p.edit().clear().commit();
            ctx.deleteSharedPreferences(name);
        }
    }
}
