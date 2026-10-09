package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** El Keystore solo existe bajo ART; estas pruebas no corren en una JVM. */
public final class SessionStoreTest {

    private static final String COOKIE = "sessionKey=valor-de-prueba; lastActiveOrg=otro-valor";

    /** Archivo y llave propios: una prueba NO puede borrar la sesion real del usuario. */
    private static final String TEST_FILE = "session-test.bin";
    private static final String TEST_ALIAS = "cuw-session-test";

    public static void run(Assert a, android.content.Context ctx) {
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
        a.isTrue("sin sesion al empezar", !s.hasSession());
        a.eq("load sin sesion da null", null, call(a, () -> s.load()));

        call(a, () -> { s.save(COOKIE); return null; });
        a.isTrue("hasSession tras guardar", s.hasSession());
        a.eq("vuelve lo mismo que entro", COOKIE, call(a, () -> s.load()));

        // Lo que queda en disco no puede contener el texto claro.
        File f = new File(ctx.getFilesDir(), TEST_FILE);
        String raw = new String(readAll(f), StandardCharsets.ISO_8859_1);
        a.isTrue("el archivo no contiene la cookie en claro", !raw.contains("valor-de-prueba"));
        a.isTrue("el archivo no contiene el nombre sessionKey", !raw.contains("sessionKey"));

        s.clear();
        a.isTrue("clear borra la sesion", !s.hasSession());
        a.isTrue("clear es idempotente", clearTwiceOk(s));
    }

    private static boolean clearTwiceOk(SessionStore s) {
        try { s.clear(); return true; } catch (RuntimeException e) { return false; }
    }

    private static byte[] readAll(File f) {
        try { return Files.readAllBytes(f.toPath()); } catch (Exception e) { return new byte[0]; }
    }

    interface Body<T> { T run() throws Exception; }

    private static <T> T call(Assert a, Body<T> b) {
        try {
            return b.run();
        } catch (Exception e) {
            a.fail("lanzo " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
