package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;

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
        byte[] disk = readAll(a, f);
        String raw = new String(disk, StandardCharsets.ISO_8859_1);
        a.isTrue("el archivo no esta vacio", disk.length > 0);
        a.isTrue("el archivo no contiene la cookie en claro", !raw.contains("valor-de-prueba"));
        a.isTrue("el archivo no contiene el nombre sessionKey", !raw.contains("sessionKey"));
        a.eq("primer byte = longitud del IV", 12, disk.length > 0 ? (disk[0] & 0xFF) : -1);
        a.eq("longitud esperada 1+12+cookie+16", 1 + 12 + COOKIE.getBytes(StandardCharsets.UTF_8).length + 16,
                disk.length);

        // El IV no se reutiliza.
        call(a, () -> { s.save(COOKIE); return null; });
        byte[] disk2 = readAll(a, f);
        a.isTrue("dos save() dan IV distintos", disk.length > 12 && disk2.length > 12
                && !Arrays.equals(Arrays.copyOfRange(disk, 1, 13), Arrays.copyOfRange(disk2, 1, 13)));

        s.clear();
        a.isTrue("clear borra la sesion", !s.hasSession());
        a.isTrue("clear borra la llave del Keystore", !keyExists());
        a.isTrue("clear es idempotente", clearTwiceOk(s));

        // Archivo corrupto: null, archivo borrado (y llave borrada: es corrupcion autentica).
        corrupt(a, ctx, s, f, "1 byte", new byte[] {7});
        corrupt(a, ctx, s, f, "ivLen invalido", new byte[] {0, 1, 2, 3, 4});
        byte[] ivCinco = new byte[20];
        ivCinco[0] = 5;
        corrupt(a, ctx, s, f, "ivLen valido en forma pero distinto de 12", ivCinco);
        corrupt(a, ctx, s, f, "ivLen mayor que el archivo", new byte[] {(byte) 200, 1, 2, 3});
        call(a, () -> { s.save(COOKIE); return null; });
        byte[] good = readAll(a, f);
        good[good.length - 5] ^= 0x01;
        corrupt(a, ctx, s, f, "byte volteado en el cuerpo", good);
        s.clear();
    }

    /** Cierre de F3: hasSession() mira la forma del archivo, sin descifrar. */
    public static void runCierre(Assert a, android.content.Context ctx) {
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
        File f = new File(ctx.getFilesDir(), TEST_FILE);
        shape(a, s, f, "1 byte", new byte[] {7}, false);
        shape(a, s, f, "ivLen invalido", new byte[] {0, 1, 2, 3, 4}, false);
        byte[] ivCinco = new byte[40];
        ivCinco[0] = 5;
        shape(a, s, f, "iv de 5 bytes", ivCinco, false);
        shape(a, s, f, "demasiado corto para IV+etiqueta", new byte[] {12, 1, 2, 3, 4, 5, 6, 7}, false);
        shape(a, s, f, "archivo vacio", new byte[0], false);
        byte[] ok = new byte[1 + 12 + 16 + 3];
        ok[0] = 12;
        // Control: con forma valida (aunque el contenido no descifre) SI cuenta; si no, lo
        // anterior pasaria en vacio.
        shape(a, s, f, "forma valida", ok, true);
        s.clear();
        // Un archivo valido de verdad sigue contando y se carga.
        call(a, () -> { s.save(COOKIE); return null; });
        a.isTrue("save real: hasSession", s.hasSession());
        a.eq("save real: load", COOKIE, call(a, () -> s.load()));
        s.clear();
    }

    private static void shape(Assert a, SessionStore s, File f, String what, byte[] content,
                              boolean expected) {
        try {
            Files.write(f.toPath(), content);
        } catch (Exception e) {
            a.fail(what + ": no se pudo escribir el archivo de prueba");
            return;
        }
        a.eq("hasSession " + what, expected, s.hasSession());
        s.clear();
    }

    private static void corrupt(Assert a, android.content.Context ctx, SessionStore s, File f,
                                String what, byte[] content) {
        try {
            Files.write(f.toPath(), content);
        } catch (Exception e) {
            a.fail(what + ": no se pudo escribir el archivo de prueba");
            return;
        }
        a.eq(what + ": load da null", null, call(a, () -> s.load()));
        a.isTrue(what + ": el archivo corrupto se borra", !f.exists());
        a.isTrue(what + ": no queda sesion", !s.hasSession());
        s.clear();
    }

    private static boolean keyExists() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return ks.containsAlias(TEST_ALIAS);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean clearTwiceOk(SessionStore s) {
        try { s.clear(); return true; } catch (RuntimeException e) { return false; }
    }

    private static byte[] readAll(Assert a, File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            a.fail("no se pudo leer " + f.getName() + ": " + e.getClass().getSimpleName());
            return new byte[0];
        }
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
