package com.claulimitswidgets.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/**
 * Selector manual de organizacion y cerrar sesion.
 *
 * Los uuid son datos de cuenta privados: se guardan en las preferencias privadas de la app, no
 * se registran y en pantalla se muestran NOMBRES (si falta, un texto generico numerado).
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "cuw";
    static final String KEY_ORG = "manual_org";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // La pantalla muestra datos de la cuenta: que no salga en capturas ni en recientes.
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);
        paintOrgs();
        // La lista cacheada no se refresca sola: abrir esta pantalla es el momento natural de
        // mirar. Al terminar se repintan los radios, porque la lista pudo cambiar.
        // El callback puede venir de un hilo de red (F4): las vistas solo se tocan en el principal.
        WidgetUpdateJob.refreshOrgs(this, () -> runOnUiThread(this::paintOrgs));

        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> {
            if (Session.logout(this)) {
                finish();
            } else {
                // No cerrar la pantalla: la sesion puede seguir utilizable.
                Toast.makeText(this, R.string.logout_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Dibuja los radios desde la lista cacheada. Se llama al crear la pantalla y otra vez
     * cuando `refreshOrgs` termina, porque la lista pudo cambiar.
     */
    private void paintOrgs() {
        // `refreshOrgs` vuelve de un hilo de red: la pantalla pudo cerrarse mientras tanto.
        if (isFinishing() || isDestroyed()) return;
        RadioGroup group = findViewById(R.id.orgs);
        group.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_ORG, null);

        RadioButton auto = new RadioButton(this);
        auto.setText(R.string.settings_org_auto);
        auto.setChecked(current == null);
        auto.setOnClickListener(v -> {
            prefs.edit().remove(KEY_ORG).apply();
            // F4: UsageRefresher.clearBackoff(this); volver a automatica es accion del usuario.
            WidgetUpdateJob.runNow(this);
        });
        group.addView(auto);

        // Nombres, no uuid: un uuid no le dice nada al usuario y acabaria en una captura.
        int n = 0;
        for (UsageClient.Org o : knownOrgs()) {
            n++;
            RadioButton b = new RadioButton(this);
            // Sin nombre: texto generico, nunca parte del uuid (tampoco en una captura).
            b.setText(o.name != null && !o.name.isEmpty()
                    ? o.name : getString(R.string.settings_org_unnamed, n));
            b.setChecked(o.uuid.equals(current));
            b.setOnClickListener(v -> {
                prefs.edit().putString(KEY_ORG, o.uuid).apply();
                // F4: UsageRefresher.clearBackoff(this); elegir es accion del usuario, no espera.
                WidgetUpdateJob.runNow(this);
            });
            group.addView(b);
        }
    }

    /** Organizaciones cacheadas por el refrescador. Hoy vacio: solo se ofrece "Automatica". */
    private List<UsageClient.Org> knownOrgs() {
        // F4: return new SnapshotStore(this).knownOrgs();
        return Collections.emptyList();
    }
}
