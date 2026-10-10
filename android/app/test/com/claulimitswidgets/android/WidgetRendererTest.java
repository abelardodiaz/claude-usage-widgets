package com.claulimitswidgets.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Basis;
import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.Forecast;
import com.claudewidgets.core.Source;
import com.claudewidgets.core.UsageModel;
import com.claudewidgets.core.Window;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * El renderizador es codigo puro sobre un Snapshot: aqui se APLICA el RemoteViews a un arbol de
 * vistas de verdad (en el hilo principal, que es donde ProgressBar es sincrono) y se lee lo que
 * quedo. Lo que NO se prueba aqui es el lanzador: tamano de celda, recorte, toque.
 */
public final class WidgetRendererTest {

    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private WidgetRendererTest() {}

    // ---- infraestructura ----------------------------------------------------------------

    /** Corre `body` en el hilo principal y espera. Si lanza, lo relanza aqui. */
    private static void onMain(Runnable body) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("main colgado");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
    }

    private static Context localized(Context ctx, String lang) {
        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        cfg.setLocale(Locale.forLanguageTag(lang));
        return ctx.createConfigurationContext(cfg);
    }

    /** Pinta y devuelve la raiz inflada. */
    private static View paint(Context ctx, Snapshot s, boolean compact) {
        AtomicReference<View> out = new AtomicReference<>();
        onMain(() -> {
            RemoteViews rv = WidgetRenderer.render(ctx, s, compact, NOW);
            out.set(rv.apply(ctx, new FrameLayout(ctx)));
        });
        return out.get();
    }

    private static Window win(double pct) { return new Window(pct, NOW.plus(Duration.ofHours(2))); }

    private static Snapshot snap(double session, double weekly, double used, Double quota,
                                 Double pace, Forecast fc, Instant fetched, Snapshot.Problem p) {
        UsageModel m = new UsageModel(Source.CLAUDE_AI, win(session), win(weekly),
                Collections.emptyList(), Collections.emptyList());
        DayUsage d = new DayUsage(new HashMap<>(), used, quota, false);
        return new Snapshot(m, d, fc, fc, pace, fetched, p);
    }

    private static Snapshot fine(double session, double weekly, double used, Double quota) {
        return snap(session, weekly, used, quota, 0.4,
                new Forecast(NOW.plus(Duration.ofDays(1)), true, Basis.WINDOW),
                NOW.minus(Duration.ofMinutes(5)), null);
    }

    private static int vis(Assert a, String what, View root, int id) {
        View v = root.findViewById(id);
        a.isTrue(what + ": la vista existe", v != null);
        return v == null ? -99 : v.getVisibility();
    }

    private static String text(Assert a, String what, View root, int id) {
        View v = root.findViewById(id);
        a.isTrue(what + ": el TextView existe", v instanceof TextView);
        return v instanceof TextView ? ((TextView) v).getText().toString() : null;
    }

    /** Todo el texto visible o no del arbol, para buscar lo que NO debe estar. */
    private static void collect(View v, StringBuilder sb) {
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), sb);
        }
    }

    /** Devuelve que barras (verde, ambar, rojo, gris) estan encendidas, como "VGRG"-style. */
    private static String lit(View root, int[] ids) {
        StringBuilder sb = new StringBuilder();
        String[] names = {"green", "amber", "red", "gray"};
        for (int i = 0; i < ids.length; i++) {
            View b = root.findViewById(ids[i]);
            if (b != null && b.getVisibility() == View.VISIBLE) {
                if (sb.length() > 0) sb.append('+');
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    private static int progressOf(View root, int[] ids, String color) {
        String[] names = {"green", "amber", "red", "gray"};
        for (int i = 0; i < ids.length; i++) {
            if (names[i].equals(color)) return ((ProgressBar) root.findViewById(ids[i])).getProgress();
        }
        return -1;
    }

    private static final int[] SESSION = {R.id.session_green, R.id.session_amber,
            R.id.session_red, R.id.session_gray};
    private static final int[] WEEKLY = {R.id.weekly_green, R.id.weekly_amber,
            R.id.weekly_red, R.id.weekly_gray};
    private static final int[] TODAY = {R.id.today_green, R.id.today_amber,
            R.id.today_red, R.id.today_gray};

    // ---- pruebas ------------------------------------------------------------------------

    public static void run(Assert a, Context ctx) {
        Context en = localized(ctx, "en");
        Context es = localized(ctx, "es");
        problems(a, en, es);
        thresholds(a, en);
        exhausted(a, en, es);
        reapply(a, en);
        paceOrder(a, en);
        tapTargets(a, en);
        problemWithOldData(a, en);
        normal(a, en, es);
        bars(a, en);
        pace(a, en);
        noUuid(a, ctx, en);
        fillColors(a, en);
        translations(a, en, es);
    }

    /** Cada Problem, sin datos: el aviso correcto y NINGUN numero, en los dos tamanios y idiomas. */
    private static void problems(Assert a, Context en, Context es) {
        Snapshot.Problem[] all = Snapshot.Problem.values();
        int[] enIds = {R.string.p_no_session, R.string.p_auth_expired, R.string.p_blocked,
                R.string.p_offline, R.string.p_bad_format, R.string.p_choose_org, R.string.p_loading};
        String[] enText = {"Tap to sign in", "Session expired \u2014 tap to sign in",
                "Blocked by Claude \u2014 retrying", "No connection",
                "Unexpected response format", "Pick an organization in Settings", "Updating\u2026"};
        String[] esText = {"Toca para iniciar sesi\u00f3n", "Sesi\u00f3n vencida \u2014 toca para entrar",
                "Claude est\u00e1 bloqueando \u2014 reintentando", "Sin conexi\u00f3n",
                "Formato de respuesta no reconocido", "Elige organizaci\u00f3n en Ajustes",
                "Actualizando\u2026"};
        Snapshot.Problem[] order = {Snapshot.Problem.NO_SESSION, Snapshot.Problem.AUTH_EXPIRED,
                Snapshot.Problem.BLOCKED, Snapshot.Problem.OFFLINE, Snapshot.Problem.BAD_FORMAT,
                Snapshot.Problem.CHOOSE_ORG, Snapshot.Problem.LOADING};
        // Las tablas de arriba van en este orden: si alguien reordena o amplia el enum, falla aqui
        // en vez de comparar el texto de un Problem con el de otro.
        a.isTrue("Problem == el orden que suponen las tablas",
                java.util.Arrays.equals(order, all));
        for (int i = 0; i < order.length; i++) {
            for (boolean compact : new boolean[] {true, false}) {
                String tag = order[i] + (compact ? " 4x1" : " 4x2");
                Snapshot s = Snapshot.of(order[i]);
                View v = paint(en, s, compact);
                a.eq(tag + ": aviso visible", View.VISIBLE, vis(a, tag, v, R.id.notice));
                a.eq(tag + ": aviso en ingles", enText[i], text(a, tag, v, R.id.notice));
                a.eq(tag + ": sin numeros", View.GONE, vis(a, tag, v, R.id.numbers));
                a.eq(tag + ": el recurso es el esperado", enText[i], en.getString(enIds[i]));
                View ve = paint(es, s, compact);
                a.eq(tag + ": aviso en espanol", esText[i], text(a, tag, ve, R.id.notice));
            }
        }
    }

    /** Con un problema y datos viejos se ve el aviso Y los numeros (offline con la hora). */
    private static void problemWithOldData(Assert a, Context en) {
        for (boolean compact : new boolean[] {true, false}) {
            String tag = "OFFLINE con datos " + (compact ? "4x1" : "4x2");
            Snapshot s = snap(42, 68, 3, 10.0, 0.4, null, NOW.minus(Duration.ofHours(3)),
                    Snapshot.Problem.OFFLINE);
            View v = paint(en, s, compact);
            a.eq(tag + ": aviso", View.VISIBLE, vis(a, tag, v, R.id.notice));
            a.eq(tag + ": texto del aviso", "No connection", text(a, tag, v, R.id.notice));
            a.eq(tag + ": numeros visibles", View.VISIBLE, vis(a, tag, v, R.id.numbers));
            a.eq(tag + ": sesion", "Session 42%", text(a, tag, v, R.id.session));
            a.eq(tag + ": edad del dato viejo", "3 h ago", text(a, tag, v, R.id.age));
        }
        // Un snapshot "NO_SESSION" que aun trae modelo (no deberia, pero el renderizador no
        // lo decide): el aviso manda y los numeros se ven. Documenta que hasData() es la regla.
        Snapshot odd = snap(10, 10, 0, 5.0, null, null, NOW, Snapshot.Problem.NO_SESSION);
        View v = paint(en, odd, true);
        a.eq("NO_SESSION con modelo: aviso", "Tap to sign in", text(a, "odd", v, R.id.notice));
    }

    private static void normal(Assert a, Context en, Context es) {
        Snapshot s = fine(42.4, 67.6, 3.5, 10.0);
        View v1 = paint(en, s, true);
        a.eq("4x1: sin aviso", View.GONE, vis(a, "4x1", v1, R.id.notice));
        a.eq("4x1: numeros", View.VISIBLE, vis(a, "4x1", v1, R.id.numbers));
        a.eq("4x1: sesion redondeada", "Session 42%", text(a, "4x1", v1, R.id.session));
        a.eq("4x1: semana truncada, no redondeada", "Week 67%", text(a, "4x1", v1, R.id.weekly));
        a.eq("4x1: edad", "5 min ago", text(a, "4x1", v1, R.id.age));
        a.isTrue("4x1 no lleva barras ni proyeccion", v1.findViewById(R.id.today) == null
                && v1.findViewById(R.id.session_green) == null);

        View v2 = paint(en, s, false);
        a.eq("4x2: sin aviso", View.GONE, vis(a, "4x2", v2, R.id.notice));
        a.eq("4x2: sesion", "Session 42%", text(a, "4x2", v2, R.id.session));
        a.eq("4x2: semana", "Week 67%", text(a, "4x2", v2, R.id.weekly));
        a.eq("4x2: edad", "5 min ago", text(a, "4x2", v2, R.id.age));
        a.eq("4x2: hoy", "Today 3.5% of 10.0%", text(a, "4x2", v2, R.id.today));
        String fc = text(a, "4x2", v2, R.id.forecast);
        a.isTrue("4x2: proyeccion 'Full at <dia hora>': " + fc,
                fc != null && fc.matches("Full at .+ \\d{1,2}:\\d\\d.*"));

        View ve = paint(es, s, false);
        a.eq("es: sesion", "Sesi\u00f3n 42%", text(a, "es", ve, R.id.session));
        a.eq("es: semana", "Semana 67%", text(a, "es", ve, R.id.weekly));
        a.eq("es: edad", "hace 5 min", text(a, "es", ve, R.id.age));
        String fce = text(a, "es", ve, R.id.forecast);
        a.isTrue("es: proyeccion 'Al 100% a las': " + fce,
                fce != null && fce.startsWith("Al 100% a las "));

        // Sin proyeccion: ni weeklyForecast ni hitsAt.
        Snapshot none = snap(10, 10, 1, 5.0, 0.2, new Forecast(null, null, null), NOW, null);
        a.eq("sin hitsAt: 'No forecast yet'", "No forecast yet",
                text(a, "nofc", paint(en, none, false), R.id.forecast));
        Snapshot nullFc = snap(10, 10, 1, 5.0, 0.2, null, NOW, null);
        a.eq("sin Forecast: 'No forecast yet'", "No forecast yet",
                text(a, "nofc2", paint(en, nullFc, false), R.id.forecast));
        Snapshot noQuota = snap(10, 10, 1, null, 0.2, null, NOW, null);
        a.isTrue("cuota nula: 'Today 1.0% of —'",
                text(a, "noq", paint(en, noQuota, false), R.id.today).endsWith(" of \u2014"));

        // Edades.
        a.eq("edad: hace segundos", "just now", text(a, "age",
                paint(en, snap(1, 1, 0, 1.0, null, null, NOW.minus(Duration.ofSeconds(10)), null),
                        true), R.id.age));
        a.eq("edad: 59 min", "59 min ago", text(a, "age",
                paint(en, snap(1, 1, 0, 1.0, null, null, NOW.minus(Duration.ofMinutes(59)), null),
                        true), R.id.age));
        a.eq("edad: 1 h", "1 h ago", text(a, "age",
                paint(en, snap(1, 1, 0, 1.0, null, null, NOW.minus(Duration.ofMinutes(60)), null),
                        true), R.id.age));
    }

    /**
     * El color sale de `Colors` (R7). Aqui se comprueba el CABLEADO: que la barra encendida es
     * la que corresponde y que solo hay una. Los umbrales los prueban los fixtures del nucleo;
     * los valores de abajo estan elegidos lejos de cualquier umbral para no duplicarlos.
     */
    private static void bars(Assert a, Context en) {
        double[] pcts = {10, 30, 70, 90, 100};
        String[] want = {"green", "green", "amber", "red", "red"};
        for (int i = 0; i < pcts.length; i++) {
            View v = paint(en, fine(pcts[i], pcts[i], 1, 10.0), false);
            a.eq("sesion " + pcts[i], want[i], lit(v, SESSION));
            a.eq("semana " + pcts[i], want[i], lit(v, WEEKLY));
            a.eq("progreso sesion " + pcts[i], (int) pcts[i], progressOf(v, SESSION, want[i]));
            a.eq("progreso semana " + pcts[i], (int) pcts[i], progressOf(v, WEEKLY, want[i]));
        }
        // La sesion y la semana pueden tener colores distintos a la vez.
        View mix = paint(en, fine(10, 90, 1, 10.0), false);
        a.eq("mezcla: sesion verde", "green", lit(mix, SESSION));
        a.eq("mezcla: semana roja", "red", lit(mix, WEEKLY));
        // Redondeo y recorte del DIBUJO.
        a.eq("42.6 se dibuja como 43", 43, progressOf(paint(en, fine(42.6, 1, 0, 1.0), false),
                SESSION, "green"));
        a.eq("150 se recorta a 100", 100, progressOf(paint(en, fine(150, 1, 0, 1.0), false),
                SESSION, "red"));

        // Barra de hoy: today_used contra el cupo (ruling 3), color de Colors.today.
        View g = paint(en, fine(10, 10, 2.0, 10.0), false);
        a.eq("hoy 20% del cupo: verde", "green", lit(g, TODAY));
        a.eq("hoy 20%: progreso", 20, progressOf(g, TODAY, "green"));
        View am = paint(en, fine(10, 10, 8.5, 10.0), false);
        a.eq("hoy 85% del cupo: ambar", "amber", lit(am, TODAY));
        a.eq("hoy 85%: progreso", 85, progressOf(am, TODAY, "amber"));
        View rd = paint(en, fine(10, 10, 15.0, 10.0), false);
        a.eq("hoy 150% del cupo: rojo", "red", lit(rd, TODAY));
        a.eq("hoy 150%: la barra se recorta a 100", 100, progressOf(rd, TODAY, "red"));
        View nq = paint(en, fine(10, 10, 5.0, null), false);
        a.eq("sin cupo: gris", "gray", lit(nq, TODAY));
        a.eq("sin cupo: barra vacia", 0, progressOf(nq, TODAY, "gray"));
        View zq = paint(en, fine(10, 10, 5.0, 0.0), false);
        a.eq("cupo 0: gris", "gray", lit(zq, TODAY));
        View neg = paint(en, fine(10, 10, 5.0, -3.0), false);
        a.eq("cupo negativo: rojo", "red", lit(neg, TODAY));
        a.eq("cupo negativo: barra llena", 100, progressOf(neg, TODAY, "red"));
    }

    private static void pace(Assert a, Context en) {
        View v = paint(en, snap(10, 10, 1, 5.0, 0.4, null, NOW, null), false);
        a.eq("marca de ritmo visible", View.VISIBLE, vis(a, "pace", v, R.id.pace));
        a.eq("marca de ritmo: 40%", 40, ((ProgressBar) v.findViewById(R.id.pace)).getProgress());
        View n = paint(en, snap(10, 10, 1, 5.0, null, null, NOW, null), false);
        a.eq("sin marca de ritmo: oculta", View.GONE, vis(a, "pace", n, R.id.pace));
    }

    /**
     * Ningun uuid ni cookie llega a pantalla. Un Snapshot no tiene campo de uuid, asi que pintar
     * Snapshots a mano no podria fallar: aqui se planta el uuid donde SI vive (preferencias, lista
     * de organizaciones y hasta el NOMBRE de una organizacion) y la cookie, y se pinta lo que
     * devuelve el refrescador de verdad.
     */
    private static void noUuid(Assert a, Context ctx, Context en) {
        final String org1 = "11111111-1111-4111-8111-111111111111";
        final String org2 = "22222222-2222-4222-8222-222222222222";
        final String cookie = "sessionKey=SECRETCOOKIEVALUE";
        List<UsageClient.Org> orgs = java.util.Arrays.asList(
                new UsageClient.Org(org1, "Org " + org1), new UsageClient.Org(org2, org2));
        List<Snapshot> all = new ArrayList<>();

        // Con organizacion elegida a mano (el uuid esta en las preferencias): hay datos.
        UsageRefresherTest.Rig r1 = new UsageRefresherTest.Rig(ctx, true, cookie);
        try {
            r1.fake.orgs = orgs;
            r1.fake.usage.put(org2, UsageRefresherTest.model(30, 40));
            r1.prefs.edit().putString(SettingsActivity.KEY_ORG, org2).commit();
            Snapshot s = r1.refresher.refresh();
            a.isTrue("plantado: hay datos que pintar", s.hasData() && s.problem == null);
            all.add(s);
            all.add(r1.refresher.last());
        } finally { r1.close(); }

        // Dos organizaciones y sin pista: CHOOSE_ORG con los uuid en la lista.
        UsageRefresherTest.Rig r2 = new UsageRefresherTest.Rig(ctx, true, cookie);
        try {
            r2.fake.orgs = orgs;
            r2.fake.usage.put(org1, UsageRefresherTest.model(1, 11));
            r2.fake.usage.put(org2, UsageRefresherTest.model(2, 22));
            Snapshot s = r2.refresher.refresh();
            a.eq("plantado: CHOOSE_ORG", Snapshot.Problem.CHOOSE_ORG, s.problem);
            all.add(s);
        } finally { r2.close(); }

        for (Snapshot.Problem p : Snapshot.Problem.values()) all.add(Snapshot.of(p));

        int seen = 0;
        for (Snapshot s : all) {
            for (boolean compact : new boolean[] {true, false}) {
                StringBuilder sb = new StringBuilder();
                collect(paint(en, s, compact), sb);
                String shown = sb.toString();
                seen += shown.length();
                String tag = "estado " + s.problem + (compact ? " 4x1" : " 4x2");
                a.isTrue(tag + ": sin uuid", !UUID.matcher(shown).find());
                a.isTrue(tag + ": sin uuid plantado",
                        !shown.contains(org1) && !shown.contains(org2));
                a.isTrue(tag + ": sin cookie", !shown.contains("SECRETCOOKIEVALUE")
                        && !shown.contains("sessionKey"));
                // El toque tampoco lleva nada.
                a.isTrue(tag + ": el toque no lleva extras",
                        WidgetRenderer.tapTarget(en, s, compact).getExtras() == null);
            }
        }
        a.isTrue("el detector de uuid detecta", UUID.matcher("x " + org1 + " y").find());
        a.isTrue("se recorrio texto de verdad", seen > 100);
    }

    /** El texto nunca cruza un umbral antes que el color: 84,6 no puede decir 85 en ambar. */
    private static void thresholds(Assert a, Context en) {
        View v = paint(en, fine(59.6, 84.6, 1, 10.0), false);
        a.eq("59,6: texto 59", "Session 59%", text(a, "t", v, R.id.session));
        a.eq("59,6: sigue verde", "green", lit(v, SESSION));
        a.eq("84,6: texto 84", "Week 84%", text(a, "t", v, R.id.weekly));
        a.eq("84,6: sigue ambar", "amber", lit(v, WEEKLY));
        View w = paint(en, fine(60, 85, 1, 10.0), false);
        a.eq("60 exacto: texto 60", "Session 60%", text(a, "t", w, R.id.session));
        a.eq("60 exacto: ambar", "amber", lit(w, SESSION));
        a.eq("85 exacto: texto 85", "Week 85%", text(a, "t", w, R.id.weekly));
        a.eq("85 exacto: rojo", "red", lit(w, WEEKLY));
    }

    /** Cupo de hoy agotado: texto propio, no "de -3,0%", y la barra no se ve vacia. */
    private static void exhausted(Assert a, Context en, Context es) {
        for (double q : new double[] {-3.0, 0.0}) {
            View v = paint(en, fine(10, 10, 5.0, q), false);
            a.eq("cupo " + q + ": texto", "Weekly quota used up", text(a, "ex", v, R.id.today));
            a.eq("cupo " + q + ": barra llena", 100, progressOf(v, TODAY,
                    q < 0 ? "red" : "gray"));
            a.eq("cupo " + q + " (es)", "Cuota semanal agotada",
                    text(a, "ex", paint(es, fine(10, 10, 5.0, q), false), R.id.today));
        }
        a.eq("cupo nulo no es agotado: raya", "Today 5.0% of \u2014",
                text(a, "ex", paint(en, fine(10, 10, 5.0, null), false), R.id.today));
    }

    /** Reaplica B sobre LA MISMA vista, como hace el lanzador. */
    private static View repaint(Context ctx, View existing, Snapshot s, boolean compact) {
        onMain(() -> WidgetRenderer.render(ctx, s, compact, NOW).reapply(ctx, existing));
        return existing;
    }

    /**
     * El lanzador usa `reapply` cuando el layout coincide, y lo que el renderizador no reenvia
     * NO se borra. Un arbol nuevo (como en el resto de las pruebas) no puede delatar un campo
     * olvidado; estas si.
     */
    private static void reapply(Assert a, Context en) {
        Snapshot ok = fine(42, 68, 3, 10.0);
        for (boolean compact : new boolean[] {true, false}) {
            String tag = "reapply " + (compact ? "4x1" : "4x2");
            for (Snapshot.Problem p : new Snapshot.Problem[] {Snapshot.Problem.NO_SESSION,
                    Snapshot.Problem.LOADING, Snapshot.Problem.AUTH_EXPIRED}) {
                View v = paint(en, ok, compact);
                a.eq(tag + ": antes, la edad esta", "5 min ago", text(a, tag, v, R.id.age));
                repaint(en, v, Snapshot.of(p), compact);
                a.eq(tag + " -> " + p + ": la edad se borra", "", text(a, tag, v, R.id.age));
                a.eq(tag + " -> " + p + ": aviso visible", View.VISIBLE, vis(a, tag, v, R.id.notice));
                a.eq(tag + " -> " + p + ": numeros ocultos", View.GONE, vis(a, tag, v, R.id.numbers));
                repaint(en, v, ok, compact);
                a.eq(tag + " <- " + p + ": aviso oculto", View.GONE, vis(a, tag, v, R.id.notice));
                a.eq(tag + " <- " + p + ": numeros", View.VISIBLE, vis(a, tag, v, R.id.numbers));
                a.eq(tag + " <- " + p + ": edad", "5 min ago", text(a, tag, v, R.id.age));
            }
        }
        View v = paint(en, fine(10, 10, 1, 10.0), false);
        a.eq("reapply: verde al inicio", "green", lit(v, SESSION));
        repaint(en, v, fine(90, 70, 12.0, 10.0), false);
        a.eq("reapply: sesion pasa a roja y solo roja", "red", lit(v, SESSION));
        a.eq("reapply: semana pasa a ambar", "amber", lit(v, WEEKLY));
        a.eq("reapply: hoy pasa a rojo", "red", lit(v, TODAY));
        repaint(en, v, fine(10, 10, 1, 10.0), false);
        a.eq("reapply: vuelve a verde, solo verde", "green", lit(v, SESSION));
        a.eq("reapply: texto normal", "Today 1.0% of 10.0%", text(a, "re", v, R.id.today));
        repaint(en, v, fine(10, 10, 1, -2.0), false);
        a.eq("reapply: cupo agotado", "Weekly quota used up", text(a, "re", v, R.id.today));
        repaint(en, v, fine(10, 10, 1, 10.0), false);
        a.eq("reapply: del agotado de vuelta al normal", "Today 1.0% of 10.0%",
                text(a, "re", v, R.id.today));
        repaint(en, v, snap(10, 10, 1, 5.0, null, null, NOW, null), false);
        a.eq("reapply: sin marca de ritmo", View.GONE, vis(a, "re", v, R.id.pace));
        repaint(en, v, snap(10, 10, 1, 5.0, 0.7, null, NOW, null), false);
        a.eq("reapply: marca de ritmo vuelve", View.VISIBLE, vis(a, "re", v, R.id.pace));
        a.eq("reapply: marca de ritmo 70", 70, ((ProgressBar) v.findViewById(R.id.pace)).getProgress());
    }

    /** La marca de ritmo es de la ventana semanal: va justo despues de la barra semanal. */
    private static void paceOrder(Assert a, Context en) {
        View v = paint(en, fine(10, 10, 1, 10.0), false);
        View weeklyBar = v.findViewById(R.id.weekly_green);
        View pace = v.findViewById(R.id.pace);
        View todayLabel = v.findViewById(R.id.today);
        ViewGroup col = (ViewGroup) pace.getParent();
        int iPace = col.indexOfChild(pace);
        int iWeeklyFrame = col.indexOfChild((View) weeklyBar.getParent());
        a.eq("marca de ritmo inmediatamente despues de la barra semanal", iWeeklyFrame + 1, iPace);
        a.isTrue("y antes del texto de hoy", iPace < col.indexOfChild(todayLabel));
    }

    /** Las tres rutas del toque. */
    private static void tapTargets(Assert a, Context en) {
        for (boolean compact : new boolean[] {true, false}) {
            String tag = compact ? "4x1" : "4x2";
            for (Snapshot.Problem p : new Snapshot.Problem[] {Snapshot.Problem.NO_SESSION,
                    Snapshot.Problem.AUTH_EXPIRED}) {
                android.content.Intent i = WidgetRenderer.tapTarget(en, Snapshot.of(p), compact);
                a.eq(tag + " " + p + ": abre el login", LoginActivity.class.getName(),
                        i.getComponent().getClassName());
                a.eq(tag + " " + p + ": sin accion (es una actividad)", null, i.getAction());
            }
            android.content.Intent o = WidgetRenderer.tapTarget(en,
                    Snapshot.of(Snapshot.Problem.CHOOSE_ORG), compact);
            a.eq(tag + " CHOOSE_ORG: abre Ajustes", SettingsActivity.class.getName(),
                    o.getComponent().getClassName());
            Snapshot.Problem[] refresh = {Snapshot.Problem.BLOCKED, Snapshot.Problem.OFFLINE,
                    Snapshot.Problem.BAD_FORMAT, Snapshot.Problem.LOADING};
            for (Snapshot.Problem p : refresh) {
                android.content.Intent i = WidgetRenderer.tapTarget(en, Snapshot.of(p), compact);
                a.eq(tag + " " + p + ": refresca", WidgetUpdateJob.ACTION_TAP, i.getAction());
                a.eq(tag + " " + p + ": al proveedor de su tamano",
                        (compact ? Widget4x1Provider.class : Widget4x2Provider.class).getName(),
                        i.getComponent().getClassName());
            }
            android.content.Intent none = WidgetRenderer.tapTarget(en, fine(1, 1, 0, 1.0), compact);
            a.eq(tag + " sin problema: refresca", WidgetUpdateJob.ACTION_TAP, none.getAction());
        }
    }

    /**
     * Los hex de los cuatro rellenos solo existen en los drawables: se dibuja cada barra al
     * 100% y se lee el pixel del centro.
     */
    private static void fillColors(Assert a, Context en) {
        View root = paint(en, fine(10, 10, 1, 10.0), false);
        int[] ids = SESSION;
        String[] hex = {"#3FBF6F", "#E8B03F", "#E5534B", "#6B7280"};
        for (int i = 0; i < ids.length; i++) {
            final int idx = i;
            AtomicReference<Integer> px = new AtomicReference<>();
            onMain(() -> {
                ProgressBar b = root.findViewById(ids[idx]);
                b.setVisibility(View.VISIBLE);
                b.setProgress(100);
                b.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(16, View.MeasureSpec.EXACTLY));
                b.layout(0, 0, 200, 16);
                Bitmap bmp = Bitmap.createBitmap(200, 16, Bitmap.Config.ARGB_8888);
                b.draw(new Canvas(bmp));
                px.set(bmp.getPixel(100, 8));
            });
            a.eq("relleno " + hex[i], android.graphics.Color.parseColor(hex[i]), px.get());
        }
        // Y al 0% se ve el fondo, no el relleno: la barra vacia no puede parecer llena.
        AtomicReference<Integer> empty = new AtomicReference<>();
        onMain(() -> {
            ProgressBar b = root.findViewById(R.id.session_green);
            b.setProgress(0);
            b.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(16, View.MeasureSpec.EXACTLY));
            b.layout(0, 0, 200, 16);
            Bitmap bmp = Bitmap.createBitmap(200, 16, Bitmap.Config.ARGB_8888);
            b.draw(new Canvas(bmp));
            empty.set(bmp.getPixel(100, 8));
        });
        a.eq("barra vacia muestra el fondo", android.graphics.Color.parseColor("#2A2E37"),
                empty.get());
    }

    /** Toda cadena del widget existe en los dos idiomas y no es la misma en ambos. */
    private static void translations(Assert a, Context en, Context es) {
        int[] ids = {R.string.widget_desc, R.string.w_session, R.string.w_weekly, R.string.w_today, R.string.w_today_exhausted,
                R.string.w_age_now, R.string.w_age_min, R.string.w_age_hour, R.string.w_full_at,
                R.string.w_no_forecast, R.string.p_no_session, R.string.p_auth_expired,
                R.string.p_blocked, R.string.p_offline, R.string.p_bad_format,
                R.string.p_choose_org, R.string.p_loading, R.string.pv_session,
                R.string.pv_weekly, R.string.pv_today, R.string.pv_forecast, R.string.pv_age};
        for (int id : ids) {
            String name = en.getResources().getResourceEntryName(id);
            String e = en.getResources().getString(id);
            String s = es.getResources().getString(id);
            a.isTrue(name + ": no vacia en ingles", !e.isEmpty());
            a.isTrue(name + ": distinta en espanol", !e.equals(s));
        }
        a.eq("formato es con %%", "Al 100% a las 18:30", es.getString(R.string.w_full_at, "18:30"));
    }
}
