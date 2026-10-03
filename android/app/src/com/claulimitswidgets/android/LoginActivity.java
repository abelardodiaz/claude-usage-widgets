package com.claulimitswidgets.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.claudewidgets.core.Colors;

/**
 * En F2 solo demuestra que el nucleo quedo enlazado y dexeado para API 29. En F3 se convierte
 * en el login de verdad.
 */
public class LoginActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        // Si el nucleo no estuviera en el dex, esto no compilaria ni arrancaria.
        String probe = Colors.bar(85).toString();   // R7: 85 es rojo
        ((TextView) findViewById(R.id.status)).setText(getString(R.string.core_ok, probe));
        // RAMA DE USAR Y TIRAR: VibratorManager es de API 31 y el minSdk es 29.
        // Compila sin problema (esta en android.jar de la 34); lint tiene que cazarlo.
        android.os.VibratorManager vm = getSystemService(android.os.VibratorManager.class);
        if (vm != null) vm.cancel();
    }
}
