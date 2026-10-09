package com.claulimitswidgets.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.util.Collections;
import java.util.List;

/**
 * Selector manual de organizacion y cerrar sesion.
 *
 * Los uuid son datos de cuenta privados: se guardan en las preferencias privadas de la app, no
 * se registran y en pantalla se muestran NOMBRES (solo si falta el nombre, el uuid abreviado).
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "cuw";
    static final String KEY_ORG = "manual_org";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        paintOrgs();
        // La lista cacheada no se refresca sola: abrir esta pantalla es el momento natural de
        // mirar. Al terminar se repintan los radios, porque la lista pudo cambiar.
        WidgetUpdateJob.refreshOrgs(this, this::paintOrgs);

        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> {
            Session.logout(this);
            finish();
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
        for (UsageClient.Org o : knownOrgs()) {
            RadioButton b = new RadioButton(this);
            b.setText(o.name != null ? o.name : abbreviate(o.uuid));
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

    /** Respaldo si no se guardo el nombre: ocho caracteres bastan para distinguir dos. */
    static String abbreviate(String uuid) {
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8) + "…";
    }
}
