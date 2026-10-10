package com.claulimitswidgets.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
    /** User-Agent del WebView, guardado en el login para que las consultas nativas lo reusen. */
    static final String KEY_UA = "user_agent";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // La pantalla muestra datos de la cuenta: que no salga en capturas ni en recientes.
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);
        // Nada de preferencias aqui: `getSharedPreferences` es lo que carga el archivo del disco,
        // y onCreate corre en el principal. Los radios se pintan cuando el hilo vuelve.
        loadAndPaint();
        // La lista cacheada no se refresca sola: abrir esta pantalla es el momento natural de
        // mirar. Al terminar se vuelve a leer y a pintar, porque la lista pudo cambiar.
        // El callback viene del hilo de red: la lectura del disco sigue fuera del principal.
        WidgetUpdateJob.refreshOrgs(this, this::loadAndPaint);

        ((Button) findViewById(R.id.btn_logout)).setOnClickListener(v -> {
            v.setEnabled(false);   // un solo cierre a la vez
            // Fuera del principal: borrar Keystore, cache e IPC por widget bloqueaba la UI (ANR).
            Session.logoutAsync(this, ok -> {
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    // Esta pantalla no tiene la instancia del WebView: la limpia LoginActivity
                    // (onCreate u onResume, segun este debajo o no) y es lo natural tras cerrar
                    // sesion. Sin esto, llegar a Ajustes desde el widget se saltaba esa limpieza.
                    startActivity(new Intent(this, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                } else {
                    // No cerrar la pantalla: la sesion puede seguir utilizable.
                    v.setEnabled(true);
                    Toast.makeText(this, R.string.logout_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * Lee la lista cacheada en un hilo (es disco: nada de leerla en el principal) y pinta los
     * radios en el principal. Se llama al crear la pantalla y cuando `refreshOrgs` termina.
     */
    private void loadAndPaint() {
        Context app = getApplicationContext();
        new Thread(() -> {
            // Las PRIMERAS lecturas de preferencias (las que cargan el archivo) van aqui.
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String current = prefs.getString(KEY_ORG, null);
            List<UsageClient.Org> orgs = loadKnownOrgs(app);
            boolean choosing = Session.snapshotFor(app).choosingOrg();
            runOnUiThread(() -> paintOrgs(orgs, prefs, current, choosing));
        }, "cuw-orgs-read").start();
    }

    /**
     * Dibuja los radios desde la lista dada. Va siempre al hilo principal y NO lee disco: lo que
     * necesita llega ya leido. `refreshOrgs` vuelve de un hilo de red y la pantalla pudo cerrarse.
     */
    private void paintOrgs(List<UsageClient.Org> orgs, SharedPreferences prefs, String current,
                           boolean choosing) {
        if (isFinishing() || isDestroyed()) return;
        // La ayuda solo cuando la eleccion automatica FALLO por ambigua; con una sola organizacion
        // decir "tu cuenta tiene mas de una" es falso.
        findViewById(R.id.org_help).setVisibility(helpVisible(choosing)
                ? android.view.View.VISIBLE : android.view.View.GONE);
        RadioGroup group = findViewById(R.id.orgs);
        group.removeAllViews();

        RadioButton auto = new RadioButton(this);
        auto.setText(R.string.settings_org_auto);
        auto.setChecked(autoChecked(current, choosing));
        auto.setOnClickListener(v -> {
            prefs.edit().remove(KEY_ORG).apply();
            // Volver a automatica es accion del usuario: no espera un backoff previo.
            UsageRefresher.clearBackoff(this);
            WidgetUpdateJob.runNow(this);
        });
        group.addView(auto);

        // Nombres, no uuid: un uuid no le dice nada al usuario y acabaria en una captura.
        int n = 0;
        for (UsageClient.Org o : orgs) {
            n++;
            RadioButton b = new RadioButton(this);
            // Sin nombre: texto generico, nunca parte del uuid (tampoco en una captura).
            b.setText(o.name != null && !o.name.isEmpty()
                    ? o.name : getString(R.string.settings_org_unnamed, n));
            b.setChecked(o.uuid.equals(current));
            b.setOnClickListener(v -> {
                prefs.edit().putString(KEY_ORG, o.uuid).apply();
                Session.snapshotFor(getApplicationContext()).setChoosingOrg(false);
                // Elegir es accion del usuario: no espera un backoff previo.
                UsageRefresher.clearBackoff(this);
                WidgetUpdateJob.runNow(this);
            });
            group.addView(b);
        }
    }

    static boolean helpVisible(boolean choosing) { return choosing; }

    /** "Automatica" marcada solo si no hay eleccion manual Y la automatica no fallo por ambigua. */
    static boolean autoChecked(String manualOrg, boolean choosing) {
        return manualOrg == null && !choosing;
    }

    /**
     * Organizaciones que recordo el refrescador, por la MISMA via que usa el (`Session.snapshotFor`).
     * Hace disco. Nunca lanza: sin lista, Ajustes ofrece solo "Automatica".
     */
    static List<UsageClient.Org> loadKnownOrgs(Context ctx) {
        return knownOrgs(Session.snapshotFor(ctx));
    }

    static List<UsageClient.Org> knownOrgs(SnapshotStore store) {
        try {
            return store.knownOrgs();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
