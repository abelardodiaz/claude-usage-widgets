package com.claulimitswidgets.android;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.claudewidgets.core.Assert;

/**
 * Prueba de logout con almacen y preferencias PROPIOS: no toca la sesion real del dueno.
 * Sin WebView, cache, job ni widgets (realDevice=false) por la misma razon.
 */
public final class SessionTest {

    private static final String TEST_FILE = "session-logout-test.bin";
    private static final String TEST_ALIAS = "cuw-session-logout-test";
    private static final String TEST_PREFS = "cuw-logout-test";

    public static void run(Assert a, Context ctx) {
        // Ata la ruta de produccion: logout(ctx) borra SampleStore.of(ctx); esa ruta es filesDir/DIR_NAME,
        // el sitio donde escribira la Tarea 4.2. Si alguien cambia una, esto falla.
        a.eq("SampleStore.of apunta al directorio de produccion",
                new java.io.File(ctx.getFilesDir(), SampleStore.DIR_NAME).getAbsolutePath(),
                SampleStore.of(ctx).dir().getAbsolutePath());
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
        // Almacen de muestras PROPIO: nunca el real del dueno.
        java.io.File samplesDir = new java.io.File(ctx.getCacheDir(), "logout-samples-" + System.nanoTime());
        SampleStore samples = new SampleStore(samplesDir);
        java.io.File samplesFile = new java.io.File(samplesDir, SampleStore.FILE_NAME);
        try {
            java.time.Instant now = java.time.Instant.parse("2026-10-03T12:00:00Z");
            a.isTrue("la muestra de prueba se acepta",
                    samples.append(new com.claudewidgets.core.Sample(now, 12, null), now));
        } catch (Exception e) {
            a.fail("no se pudo preparar la muestra de prueba: " + e.getClass().getSimpleName());
            return;
        }
        a.isTrue("antes: hay archivo de muestras", samplesFile.isFile());
        try {
            s.save("sessionKey=falsa");
        } catch (Exception e) {
            a.fail("no se pudo preparar la sesion de prueba: " + e.getClass().getSimpleName());
            return;
        }
        SharedPreferences p = ctx.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE);
        p.edit().putString("manual_org", "falso").putString("otra", "x").commit();
        // Evita pasar en vacio: antes de cerrar hay algo que borrar.
        a.isTrue("antes: hay sesion", s.hasSession());
        a.isTrue("antes: hay preferencias", p.getAll().size() == 2);

        boolean ok = Session.logout(ctx, s, samples, new SnapshotStore(ctx, TEST_PREFS), TEST_PREFS, false);

        a.isTrue("logout devuelve true", ok);
        a.isTrue("logout borra la sesion", !s.hasSession());
        a.isTrue("logout vacia las preferencias",
                ctx.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).getAll().isEmpty());
        a.isTrue("logout borra el archivo de muestras", !samplesFile.exists());
        a.isTrue("logout es repetible", Session.logout(ctx, s, samples, new SnapshotStore(ctx, TEST_PREFS), TEST_PREFS, false));

        // Rama de fallo: deleteSharedPreferences falla -> false, pero sigue y borra la sesion.
        try {
            s.save("sessionKey=falsa");
        } catch (Exception e) {
            a.fail("no se pudo preparar la segunda sesion: " + e.getClass().getSimpleName());
            return;
        }
        a.isTrue("antes del fallo: hay sesion", s.hasSession());
        // Control: el mismo envoltorio SIN inyectar el fallo da true. Asi el false de abajo solo
        // puede venir de deleteSharedPreferences, no del envoltorio ni de otro borrado.
        final String[] deleted = {null};
        Context passthrough = new ContextWrapper(ctx) {
            @Override public Context getApplicationContext() { return this; }
            @Override public boolean deleteSharedPreferences(String name) {
                return super.deleteSharedPreferences(name);
            }
        };
        SessionStore s2 = new SessionStore(ctx, TEST_FILE + "2", TEST_ALIAS + "2");
        s2.clear();
        try {
            s2.save("sessionKey=falsa");
        } catch (Exception e) {
            a.fail("no se pudo preparar la sesion de control: " + e.getClass().getSimpleName());
            return;
        }
        a.isTrue("control: el envoltorio sin fallo devuelve true",
                Session.logout(passthrough, s2, samples, new SnapshotStore(ctx, TEST_PREFS), TEST_PREFS, false));
        s2.clear();

        Context failing = new ContextWrapper(ctx) {
            @Override public Context getApplicationContext() { return this; }
            @Override public boolean deleteSharedPreferences(String name) {
                deleted[0] = name;
                return false;
            }
        };
        boolean ok2 = Session.logout(failing, s, samples, new SnapshotStore(ctx, TEST_PREFS), TEST_PREFS, false);
        a.eq("el unico fallo inyectado fue deleteSharedPreferences de las prefs de prueba",
                TEST_PREFS, deleted[0]);
        a.isTrue("si un borrado falla, logout devuelve false", !ok2);
        a.isTrue("aun asi borro la sesion", !s.hasSession());
    }
}
