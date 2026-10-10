package com.claulimitswidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Source;
import com.claudewidgets.core.UsageModel;
import com.claudewidgets.core.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Guarda lo minimo para volver a pintar el widget sin red: los dos porcentajes con sus reinicios,
 * la hora de la ultima consulta buena y las organizaciones conocidas.
 *
 * Los uuid son credenciales: viven en las preferencias privadas y no se registran. Session.logout
 * llama a {@link #clear()} de forma explicita; que hoy comparta archivo con el resto de
 * preferencias (que logout tambien vacia) es un detalle que no se da por supuesto.
 */
public final class SnapshotStore {

    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final String KEY_ORGS = "known_orgs";
    private static final String KEY_ORGS_AT = "known_orgs_at";
    private static final String KEY_SP = "last_session_percent";
    private static final String KEY_SR = "last_session_resets";
    private static final String KEY_WP = "last_weekly_percent";
    private static final String KEY_WR = "last_weekly_resets";

    private final SharedPreferences prefs;
    private final String prefsName;

    public SnapshotStore(Context ctx) {
        this(ctx, SettingsActivity.PREFS);
    }

    /** Para las pruebas y para el logout con preferencias inyectadas. */
    SnapshotStore(Context ctx, String prefsName) {
        this.prefsName = prefsName;
        this.prefs = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                : ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    /** Nombre del archivo de preferencias. Lo usan las pruebas para atar la via de produccion. */
    String prefsName() { return prefsName; }

    /** Cuando se pidio por ultima vez la lista a la red; null si nunca. Caduca la cache. */
    public Instant orgsFetchedAt() {
        try {
            long v = prefs.getLong(KEY_ORGS_AT, 0L);
            return v == 0L ? null : Instant.ofEpochSecond(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Se serializa `uuid|nombre`; el `|` no aparece en un uuid y se parte por el primero.
     * `at` es el instante en que la red devolvio esta lista.
     */
    public void rememberOrgs(List<UsageClient.Org> orgs, Instant at) {
        StringBuilder sb = new StringBuilder();
        for (UsageClient.Org o : orgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(o.uuid).append('|').append(o.name == null ? "" : o.name.replace(',', ' '));
        }
        prefs.edit().putString(KEY_ORGS, sb.toString())
                .putLong(KEY_ORGS_AT, at.getEpochSecond()).apply();
    }

    /**
     * Guarda lo minimo para volver a pintar el widget sin red. No se guarda `scoped` ni
     * `breakdown`: el widget no los muestra y serian datos de la cuenta en disco sin razon.
     * Los porcentajes van como texto de double: un float deformaria 12.3 en 12.3000001907.
     */
    public void remember(UsageModel model, Instant fetchedAt) {
        // No toca KEY_ORGS: de eso se encarga `rememberOrgs`. Un solo edit: todo o nada.
        prefs.edit()
                .putString(KEY_SP, Double.toString(model.session.percent))
                .putString(KEY_SR, model.session.resetsAt == null ? "" : model.session.resetsAt.toString())
                .putString(KEY_WP, Double.toString(model.weekly.percent))
                .putString(KEY_WR, model.weekly.resetsAt == null ? "" : model.weekly.resetsAt.toString())
                .putLong(KEY_FETCHED_AT, fetchedAt.getEpochSecond())
                .apply();
    }

    /** Null si nunca hubo una consulta buena o si lo guardado no se puede leer. */
    public UsageModel lastModel() {
        try {
            String sp = prefs.getString(KEY_SP, null);
            String wp = prefs.getString(KEY_WP, null);
            if (sp == null || wp == null) return null;
            return new UsageModel(Source.CLAUDE_AI,
                    new Window(Double.parseDouble(sp), instant(prefs.getString(KEY_SR, ""))),
                    new Window(Double.parseDouble(wp), instant(prefs.getString(KEY_WR, ""))),
                    new ArrayList<>(), new ArrayList<>());
        } catch (RuntimeException e) {   // valor corrupto (o de un tipo viejo): como si no hubiera
            return null;
        }
    }

    /** Null si nunca se consulto. */
    public Instant lastFetchInstant() {
        try {
            long s = prefs.getLong(KEY_FETCHED_AT, 0L);
            return s == 0L ? null : Instant.ofEpochSecond(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return Instant.parse(v); } catch (RuntimeException e) { return null; }
    }

    public List<UsageClient.Org> knownOrgs() {
        String raw;
        try {
            raw = prefs.getString(KEY_ORGS, "");
        } catch (RuntimeException e) {
            raw = "";
        }
        List<UsageClient.Org> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String entry : raw.split(",")) {
            int bar = entry.indexOf('|');
            String uuid = bar < 0 ? entry : entry.substring(0, bar);
            String name = bar < 0 || bar == entry.length() - 1 ? null : entry.substring(bar + 1);
            if (!uuid.isEmpty()) out.add(new UsageClient.Org(uuid, name));
        }
        return out;
    }

    /** Borra todo lo que guarda este almacen. commit(): tiene que estar en disco al volver. */
    public boolean clear() {
        return prefs.edit().remove(KEY_FETCHED_AT).remove(KEY_ORGS).remove(KEY_ORGS_AT)
                .remove(KEY_SP).remove(KEY_SR).remove(KEY_WP).remove(KEY_WR).commit();
    }
}
