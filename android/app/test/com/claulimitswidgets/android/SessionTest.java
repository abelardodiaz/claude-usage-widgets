package com.claulimitswidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Assert;

/**
 * Prueba de logout con almacen y preferencias PROPIOS: no toca la sesion real del dueno.
 * Sin WebView ni cache (webData=false) por la misma razon.
 */
public final class SessionTest {

    private static final String TEST_FILE = "session-logout-test.bin";
    private static final String TEST_ALIAS = "cuw-session-logout-test";
    private static final String TEST_PREFS = "cuw-logout-test";

    public static void run(Assert a, Context ctx) {
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
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

        boolean ok = Session.logout(ctx, s, TEST_PREFS, false);

        a.isTrue("logout devuelve true", ok);
        a.isTrue("logout borra la sesion", !s.hasSession());
        a.isTrue("logout vacia las preferencias",
                ctx.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).getAll().isEmpty());
        a.isTrue("logout es repetible", Session.logout(ctx, s, TEST_PREFS, false));
    }
}
