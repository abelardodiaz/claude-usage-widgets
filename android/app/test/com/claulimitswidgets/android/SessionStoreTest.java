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
        corrupt(a, ctx, s, f, "5 bytes con primer byte 0: corto y IV invalido", new byte[] {0, 1, 2, 3, 4});
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

    /**
     * Tarea 4.4: una llave perdida PARA SIEMPRE no es un hipo. El Keystore real no da ese fallo a
     * pedido, asi que la llave se inyecta; el cifrado es real (AES-GCM con una llave de software).
     */
    public static void runKeyLoss(Assert a, android.content.Context ctx) {
        final String file = "session-keyloss-test.bin";
        final String alias = "cuw-session-keyloss-test";
        javax.crypto.SecretKey soft = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        SessionStore real = new SessionStore(ctx, file, alias);
        real.clear();
        File f = new File(ctx.getFilesDir(), file);
        try {
            // Control: con una llave sana, guarda y lee. Sin esto lo de abajo pasaria en vacio.
            SessionStore ok = new SessionStore(ctx, file, alias, () -> soft);
            call(a, () -> { ok.save(COOKIE); return null; });
            a.eq("control: lee lo que guardo", COOKIE, call(a, () -> ok.load()));

            Throwable[] lost = {new android.security.keystore.KeyPermanentlyInvalidatedException(),
                    new java.security.UnrecoverableKeyException("x")};
            for (Throwable t : lost) {
                String n = t.getClass().getSimpleName();
                call(a, () -> { ok.save(COOKIE); return null; });
                a.isTrue(n + ": antes hay sesion", ok.hasSession());
                SessionStore dead = new SessionStore(ctx, file, alias, () -> { throw sneaky(t); });
                Throwable got = null;
                try { dead.load(); } catch (Throwable e) { got = e; }
                a.isTrue(n + ": load lanza KeyLostException", got instanceof SessionStore.KeyLostException);
                a.isTrue(n + ": KeyLostException sigue siendo GeneralSecurityException",
                        got instanceof java.security.GeneralSecurityException);
                // NO se borra: UnrecoverableKeyException puede ser un hipo disfrazado (keystore2,
                // Android 12+), y borrar cerraria la sesion del dueno por un fallo transitorio.
                a.isTrue(n + ": la sesion NO se borra (puede ser un hipo)", dead.hasSession());
                a.isTrue(n + ": el archivo sigue", f.exists());
                a.eq(n + ": y cuando la llave vuelve, se lee", COOKIE, call(a, () -> ok.load()));
            }

            // Un hipo transitorio NO es esto: se propaga tal cual y no se borra nada.
            call(a, () -> { ok.save(COOKIE); return null; });
            SessionStore flaky = new SessionStore(ctx, file, alias,
                    () -> { throw new java.security.GeneralSecurityException("hipo"); });
            Throwable hipo = null;
            try { flaky.load(); } catch (Throwable e) { hipo = e; }
            a.isTrue("hipo: lanza", hipo != null);
            a.isTrue("hipo: NO es KeyLostException", !(hipo instanceof SessionStore.KeyLostException));
            a.isTrue("hipo: la sesion sigue", flaky.hasSession());

            // Volver a entrar arregla una llave perdida: save descarta la llave y reintenta UNA vez.
            for (Throwable t : lost) {
                String n = t.getClass().getSimpleName();
                int[] calls = {0};
                SessionStore heal = new SessionStore(ctx, file, alias, () -> {
                    if (calls[0]++ == 0) throw sneaky(t);
                    return soft;
                });
                call(a, () -> { heal.save(COOKIE); return null; });
                a.eq(n + ": save pidio la llave dos veces (fallo y reintento)", 2, calls[0]);
                a.eq(n + ": tras volver a entrar se lee", COOKIE, call(a, () -> ok.load()));
            }
            int[] always = {0};
            SessionStore hopeless = new SessionStore(ctx, file, alias, () -> {
                always[0]++;
                throw new java.security.UnrecoverableKeyException("x");
            });
            Exception final1 = null;
            try { hopeless.save(COOKIE); } catch (Exception e) { final1 = e; }
            a.isTrue("llave irrecuperable siempre: save acaba lanzando",
                    final1 instanceof java.security.UnrecoverableKeyException);
            a.eq("y reintenta una sola vez, no en bucle", 2, always[0]);
            int[] hipoCalls = {0};
            SessionStore hipoSave = new SessionStore(ctx, file, alias, () -> {
                hipoCalls[0]++;
                throw new java.security.GeneralSecurityException("hipo");
            });
            try { hipoSave.save(COOKIE); } catch (Exception ignored) { }
            a.eq("un hipo al guardar NO borra la llave ni reintenta", 1, hipoCalls[0]);
        } finally {
            real.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T { throw (T) t; }

    /** M1: cerrar sesion borra tambien el temporal cifrado de save(). */
    public static void runTmp(Assert a, android.content.Context ctx) {
        SessionStore s = new SessionStore(ctx, "session-tmp-test.bin", "cuw-session-tmp-test");
        s.clear();
        File tmp = new File(ctx.getFilesDir(), "session-tmp-test.bin.tmp");
        try {
            Files.write(tmp.toPath(), new byte[] {12, 1, 2, 3, 4, 5});
            a.isTrue("antes: existe el temporal", tmp.isFile());
            a.isTrue("clear devuelve true", s.clear());
            a.isTrue("clear borra el temporal de save()", !tmp.exists());
        } catch (Exception e) {
            a.fail("runTmp: " + e.getClass().getSimpleName());
        } finally {
            tmp.delete();
            s.clear();
        }
    }

    /** Cierre de F3: hasSession() mira la forma del archivo, sin descifrar. */
    public static void runCierre(Assert a, android.content.Context ctx) {
        SessionStore s = new SessionStore(ctx, TEST_FILE, TEST_ALIAS);
        s.clear();
        File f = new File(ctx.getFilesDir(), TEST_FILE);
        shape(a, s, f, "1 byte", new byte[] {7}, false);
        shape(a, s, f, "5 bytes con primer byte 0: corto y IV invalido", new byte[] {0, 1, 2, 3, 4}, false);
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
        // Borde exacto del largo minimo (1+12+16 = 29), con primer byte valido.
        byte[] b28 = new byte[28];
        b28[0] = 12;
        shape(a, s, f, "borde: 28 bytes", b28, false);
        byte[] b29 = new byte[29];
        b29[0] = 12;
        shape(a, s, f, "borde: 29 bytes", b29, true);
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
