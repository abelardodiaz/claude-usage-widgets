package com.claulimitswidgets.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

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

    private final Context ctx;
    private final String fileName;
    private final String keyAlias;

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
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.fileName = fileName;
        this.keyAlias = keyAlias;
    }

    public boolean hasSession() {
        return file().isFile() && file().length() > 0;
    }

    public void save(String cookies) throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] body = c.doFinal(cookies.getBytes(StandardCharsets.UTF_8));
        File f = file();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(iv.length);
            out.write(iv);
            out.write(body);
        }
    }

    /** Null si no hay sesion. Si el archivo esta corrupto, se borra y se devuelve null. */
    public String load() throws GeneralSecurityException, IOException {
        if (!hasSession()) return null;
        byte[] all = readAll(file());
        if (all.length < 2) { clear(); return null; }
        int ivLen = all[0] & 0xFF;
        if (ivLen <= 0 || all.length < 1 + ivLen + 1) { clear(); return null; }
        byte[] iv = new byte[ivLen];
        System.arraycopy(all, 1, iv, 0, ivLen);
        byte[] body = new byte[all.length - 1 - ivLen];
        System.arraycopy(all, 1 + ivLen, body, 0, body.length);
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // La etiqueta GCM no cuadra: el archivo se manipulo o la llave cambio.
            // No se puede recuperar; se borra y el usuario vuelve a iniciar sesion.
            clear();
            return null;
        }
    }

    /** Borra el archivo y la llave. Idempotente: no lanza si no habia nada. */
    public void clear() {
        File f = file();
        if (f.isFile()) {
            overwrite(f);
            if (!f.delete()) f.deleteOnExit();
        }
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias);
        } catch (GeneralSecurityException | IOException ignored) {
            // Si el Keystore no responde, el archivo ya esta borrado: sin llave no se descifra.
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

    /** Pisa el contenido antes de borrar, para que no quede en bloques libres del sistema. */
    private static void overwrite(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] zeros = new byte[(int) r.length()];
            r.seek(0);
            r.write(zeros);
            r.getFD().sync();
        } catch (IOException ignored) {
            // Si no se puede pisar, igual se borra: sin la llave el contenido no sirve.
        }
    }
}
