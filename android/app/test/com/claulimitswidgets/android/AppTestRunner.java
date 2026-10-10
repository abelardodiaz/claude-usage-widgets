package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.util.List;

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
        List<String> failures = a.failures();
        if (failures.isEmpty()) return "OK: " + a.checks() + " comprobaciones, 0 fallos";
        StringBuilder sb = new StringBuilder("FALLOS (" + failures.size()
                + " de " + a.checks() + "):");
        for (String f : failures) sb.append("\n  - ").append(f);
        return sb.toString();
    }
}
