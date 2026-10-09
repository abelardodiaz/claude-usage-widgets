package com.claulimitswidgets.android;

import android.app.Instrumentation;
import android.os.Bundle;

import com.claudewidgets.core.Assert;

/**
 * Corre las pruebas DENTRO del proceso de la app: uid correcto, `filesDir` escribible y
 * Keystore propio. Se lanza con `adb shell am instrument -w`.
 */
public class AppInstrumentation extends Instrumentation {

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override
    public void onStart() {
        Bundle out = new Bundle();
        Assert a = new Assert();
        String text;
        try {
            // La lista de pruebas vive en UN sitio: AppTestRunner. Repetirla aqui garantiza
            // que algun dia se agregue una prueba y no corra.
            text = AppTestRunner.run(a, getTargetContext());
        } catch (RuntimeException e) {
            a.fail("excepcion no controlada: " + e);
            text = "FALLOS (1 de " + a.checks() + "):\n  - " + e;
        }
        out.putString("stream", text);
        finish(a.failures().isEmpty() ? 0 : 1, out);
    }
}
