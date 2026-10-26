package com.claulimitswidgets.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Guarda la cookie de sesion cifrada con AES-GCM y una llave del Android Keystore que **no es
 * exportable**: ni con root se saca del dispositivo, solo se puede pedir al sistema que cifre
 * o descifre con ella.
 *
 * Formato del archivo: [1 byte longitud del IV][IV][texto cifrado + etiqueta GCM].
 *
 * Nada de lo que pasa por aqui se registra. `load()` devuelve material sensible: quien lo llame
 * no debe pasarlo a `Log`, a un `Intent` ni a un mensaje de error.
 */
public final class SessionStore {

    static final String FILE_NAME = "session.bin";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cuw-session-v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int IV_LEN = 12;

    /**
     * El Keystore declara la llave inservible (`KeyPermanentlyInvalidatedException` o
     * `UnrecoverableKeyException`: corrupcion del Keystore o copia/restauracion en otro aparato. OJO:
     * cambiar o quitar el bloqueo de pantalla NO la invalida, porque la llave se crea con
     * `setUserAuthenticationRequired(false)` a proposito, para refrescar con la pantalla apagada). La
     * cookie cifrada con ella ya no se puede leer jamas, asi que NO es "sin red" ni un hipo
     * transitorio: el widget debe mandar al login, como con una sesion vencida. Subclase de
     * GeneralSecurityException para que quien ya atrapaba esa no se rompa; sin causa ni mensaje
     * para no arrastrar nada del almacen.
     */
    public static final class KeyLostException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        KeyLostException() { super("llave del Keystore perdida"); }
    }

    /** De donde sale la llave. Las pruebas inyectan fallos que el Keystore real no da a pedido. */
    interface KeySource { SecretKey get() throws GeneralSecurityException, IOException; }

    private final Context ctx;
    private final String fileName;
    private final String keyAlias;
    private final KeySource keys;

    public SessionStore(Context ctx) {
        this(ctx, FILE_NAME, KEY_ALIAS);
    }

    /**
     * Para las pruebas. Ahora que corren con el uid y el `filesDir` de la app, un
     * `new SessionStore(ctx).clear()` borraria la sesion de verdad del usuario, y volver a entrar
     * cuesta un correo con ventana de 10 minutos y limite de reenvios. Las pruebas usan su propio
     * archivo y su propia llave.
     */
    SessionStore(Context ctx, String fileName, String keyAlias) {
        this(ctx, fileName, keyAlias, null);
    }

    /** Con la fuente de llaves inyectada (null = la del Keystore). Solo para las pruebas. */
    SessionStore(Context ctx, String fileName, String keyAlias, KeySource keys) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.fileName = fileName;
        this.keyAlias = keyAlias;
        this.keys = keys != null ? keys : this::key;
    }

    /**
     * Hay un archivo con la FORMA de uno que escribio save(): primer byte = 12 (el IV) y largo al
     * menos 1+12+16 (IV mas etiqueta GCM). No descifra: un archivo corrupto en su forma deja de
     * pintar "Sesion iniciada". Una corrupcion interna (byte volteado) solo la detecta load().
     */
    public boolean hasSession() {
        // Llave perdida de verdad: el archivo tiene buena forma pero no se podra descifrar jamas.
        if (lostMarker().exists()) return false;
        File f = file();
        if (!f.isFile() || f.length() < 1 + IV_LEN + TAG_BITS / 8) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == IV_LEN;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * No hay NI archivo de sesion: es la unica respuesta que justifica dejar de despertar el job.
     * A diferencia de `hasSession()`, no se confunde con un archivo ilegible (sin descriptores,
     * llave perdida, forma rara): ante la duda, el job sigue.
     */
    public boolean isAbsent() {
        return !file().exists();
    }

    /**
     * Marcador de "llave perdida": un archivo vacio junto al de la sesion que deja `load()` cuando
     * el Keystore declara la llave inservible, para que `hasSession()` no diga "sesion iniciada"
     * sobre una cookie que ya no se puede leer. Es SOLO un aviso: no borra ni toca la sesion.
     * `load()` lo quita en cuanto descifra bien (un hipo del Keystore se cura solo), `save()` al
     * guardar una sesion nueva y `clear()` al cerrar sesion.
     */
    boolean isKeyLost() {
        return lostMarker().exists() && fileExists();
    }

    private File lostMarker() {
        return new File(ctx.getFilesDir(), fileName + ".lost");
    }

    private void markKeyLost() {
        try {
            lostMarker().createNewFile();
        } catch (IOException ignored) {
            // Sin marcador la pantalla vuelve a ser optimista, pero nada se pierde.
        }
    }

    private void unmarkKeyLost() {
        File m = lostMarker();
        if (m.exists() && !m.delete()) m.deleteOnExit();
    }

    /** Hay algo en disco, sea o no valido: load() lo examina y borra lo corrupto. */
    private boolean fileExists() {
        return file().isFile() && file().length() > 0;
    }

    /** Candado comun: login y widget pueden usar instancias distintas sobre el mismo archivo. */
    private static final Object LOCK = new Object();

    public void save(String cookies) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            Cipher c = encryptCipher();
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(cookies.getBytes(StandardCharsets.UTF_8));
            // Escritura atomica: a un temporal y renombrar, para que un lector nunca vea un
            // archivo a medias y lo confunda con uno corrupto.
            File f = file();
            File tmp = new File(f.getParentFile(), fileName + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(iv.length);
                out.write(iv);
                out.write(body);
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("no se pudo reemplazar el archivo de sesion");
            }
            unmarkKeyLost();   // sesion nueva con llave nueva: ya no esta perdida
        }
    }

    /**
     * Volver a entrar es como se arregla una llave perdida: si la de siempre ya no sirve se
     * descarta y se crea otra. Sin esto el login fallaria para siempre con "no se pudo guardar".
     */
    private Cipher encryptCipher() throws GeneralSecurityException, IOException {
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, keys.get());
            return c;
        } catch (KeyPermanentlyInvalidatedException | UnrecoverableKeyException e) {
            deleteKey();
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, keys.get());
            return c;
        }
    }

    /**
     * Null si no hay sesion. Si el archivo esta corrupto (forma invalida o etiqueta GCM que no
     * cuadra) se borra y se devuelve null. Si el Keystore dice que la llave no sirve se lanza
     * {@link KeyLostException} SIN borrar nada (puede ser un hipo disfrazado). Cualquier otro fallo del Keystore se
     * propaga SIN borrar nada: puede ser transitorio y borrar costaria la sesion del usuario.
     */
    public String load() throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            if (!fileExists()) return null;
            byte[] all = readAll(file());
            if (all.length < 2) { clear(); return null; }
            int ivLen = all[0] & 0xFF;
            if (ivLen <= 0 || all.length < 1 + ivLen + 1) { clear(); return null; }
            // Forma valida pero IV que no es el de 12 bytes que escribe save(): GCMParameterSpec
            // lanzaria InvalidAlgorithmParameterException y load() fallaria para siempre.
            if (ivLen != 12) { clear(); return null; }
            byte[] iv = new byte[ivLen];
            System.arraycopy(all, 1, iv, 0, ivLen);
            byte[] body = new byte[all.length - 1 - ivLen];
            System.arraycopy(all, 1 + ivLen, body, 0, body.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            try {
                c.init(Cipher.DECRYPT_MODE, keys.get(), new GCMParameterSpec(TAG_BITS, iv));
            } catch (KeyPermanentlyInvalidatedException | UnrecoverableKeyException e) {
                // NO se borra nada. Desde Android 12 (keystore2) AndroidKeyStoreSpi envuelve en
                // UnrecoverableKeyException tambien errores TRANSITORIOS (se han visto tras el
                // arranque y en Samsung), y el 4202 corre justo despues del arranque: borrar
                // aqui cerraria la sesion del dueno por un hipo y le obligaria a repetir el
                // login por correo. El lado seguro es no borrar; el siguiente `save()` regenera la
                // llave si de verdad estaba perdida.
                markKeyLost();
                throw new KeyLostException();
            }
            try {
                String cookies = new String(c.doFinal(body), StandardCharsets.UTF_8);
                unmarkKeyLost();   // se pudo descifrar: era un hipo, la llave sirve
                return cookies;
            } catch (AEADBadTagException e) {
                // Corrupcion autentica: el contenido se manipulo. No se recupera.
                clear();
                return null;
            }
        }
    }

    /**
     * Borra el archivo y la llave. Idempotente. Devuelve false si la llave no pudo borrarse
     * (el llamador decide; no se registra nada porque aqui no hay datos que se puedan citar).
     */
    public boolean clear() {
        synchronized (LOCK) {
            File f = file();
            if (f.isFile()) {
                overwrite(f);
                if (!f.delete()) f.deleteOnExit();
            }
            // El temporal de save() (cookie CIFRADA a medio escribir si el proceso murio entre
            // la escritura y el renombrado) tambien es parte de la sesion: SampleStore.clear
            // borra el suyo, y cerrar sesion no puede dejar texto cifrado de la cookie atras.
            File tmp = new File(f.getParentFile(), fileName + ".tmp");
            if (tmp.isFile()) {
                overwrite(tmp);
                if (!tmp.delete()) tmp.deleteOnExit();
            }
            File lost = lostMarker();
            if (lost.exists() && !lost.delete()) lost.deleteOnExit();
            try {
                KeyStore ks = KeyStore.getInstance(KEYSTORE);
                ks.load(null);
                if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias);
                return !f.exists() && !tmp.exists() && !lost.exists();
            } catch (GeneralSecurityException | IOException e) {
                return false;
            }
        }
    }

    private void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias);
        } catch (GeneralSecurityException | IOException ignored) {
            // Si no se puede borrar, el siguiente intento de generar fallara y se vera arriba.
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(keyAlias, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin autenticacion de usuario: el widget se actualiza con la pantalla apagada.
                .setUserAuthenticationRequired(false)
                .build());
        return g.generateKey();
    }

    private File file() { return new File(ctx.getFilesDir(), fileName); }

    private static byte[] readAll(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        }
    }

    /**
     * Pisa el contenido antes de borrar. Es solo un esfuerzo: en F2FS y memoria flash con
     * copy-on-write no garantiza que no queden restos en bloques libres. Lo que de verdad protege
     * el contenido es que la llave del Keystore se elimina.
     */
    private static void overwrite(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] zeros = new byte[(int) r.length()];
            r.seek(0);
            r.write(zeros);
            r.getFD().sync();
        } catch (IOException ignored) {
            // Si no se puede pisar, igual se borra.
        }
    }
}
