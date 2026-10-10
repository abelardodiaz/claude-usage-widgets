package com.claulimitswidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Sample;
import com.claudewidgets.core.Source;
import com.claudewidgets.core.UnrecognizedFormatException;
import com.claudewidgets.core.UsageModel;
import com.claudewidgets.core.Window;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pruebas de Snapshot/SnapshotStore/UsageRefresher con entradas SINTETICAS: nada de red y nada
 * de la sesion, preferencias ni muestras reales del dueno (todo con nombres propios).
 */
public final class UsageRefresherTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final String ORG1 = "11111111-1111-4111-8111-111111111111";
    private static final String ORG2 = "22222222-2222-4222-8222-222222222222";

    static UsageModel model(double sp, double wp) {
        return new UsageModel(Source.CLAUDE_AI,
                new Window(sp, T0.plusSeconds(3 * 3600)),
                new Window(wp, T0.plusSeconds(3 * 86400)),
                new ArrayList<>(), new ArrayList<>());
    }

    /** Red falsa: devuelve un modelo o lanza lo que se le haya puesto por organizacion. */
    static final class Fake implements UsageRefresher.Remote {
        List<UsageClient.Org> orgs = new ArrayList<>();
        Exception orgsFail;
        final Map<String, Object> usage = new HashMap<>();   // UsageModel o Exception
        final List<String> usageCalls = new ArrayList<>();
        int orgCalls;
        Runnable onUsage;

        @Override public List<UsageClient.Org> organizations() throws IOException,
                AuthExpiredException, BlockedException, UsageClient.RetryLaterException,
                UnrecognizedFormatException {
            orgCalls++;
            if (orgsFail != null) throwIt(orgsFail);
            return orgs;
        }

        @Override public UsageModel usage(String uuid) throws IOException, AuthExpiredException,
                BlockedException, UsageClient.RetryLaterException, UnrecognizedFormatException {
            synchronized (usageCalls) { usageCalls.add(uuid); }
            if (onUsage != null) onUsage.run();
            Object r = usage.get(uuid);
            if (r instanceof Exception) throwIt((Exception) r);
            return (UsageModel) r;
        }

        private static void throwIt(Exception e) throws IOException, AuthExpiredException,
                BlockedException, UsageClient.RetryLaterException, UnrecognizedFormatException {
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof AuthExpiredException) throw (AuthExpiredException) e;
            if (e instanceof BlockedException) throw (BlockedException) e;
            if (e instanceof UsageClient.RetryLaterException) throw (UsageClient.RetryLaterException) e;
            if (e instanceof UnrecognizedFormatException) throw (UnrecognizedFormatException) e;
            throw new IllegalStateException("excepcion de prueba no prevista");
        }
    }

    /** Todo propio: sesion, preferencias, muestras y meta con nombres de prueba. */
    static final class Rig {
        final Context ctx;
        final String prefsName = "cuw-refresher-test-" + System.nanoTime();
        final SessionStore session;
        final SampleStore samples;
        final SnapshotStore meta;
        final SharedPreferences prefs;
        final Fake fake = new Fake();
        final Instant[] now = {T0};
        UsageRefresher refresher;

        Rig(Context ctx, boolean withSession, String cookies) {
            this.ctx = ctx;
            long n = System.nanoTime();
            session = new SessionStore(ctx, "refresher-test-" + n + ".bin", "cuw-refresher-test-" + n);
            samples = new SampleStore(new File(ctx.getCacheDir(), "refresher-samples-" + n));
            meta = new SnapshotStore(ctx, prefsName);
            prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            if (withSession) {
                try { session.save(cookies); } catch (Exception e) {
                    throw new IllegalStateException("no se pudo preparar la sesion de prueba");
                }
            }
            refresher = new UsageRefresher(session, samples, meta, prefs, c -> fake, () -> now[0]);
        }

        void close() {
            session.clear();
            samples.clear();
            ctx.deleteSharedPreferences(prefsName);
        }
    }

    private static final String COOKIES = "sessionKey=falsa; lastActiveOrg=" + ORG1;

    public static void run(Assert a, Context ctx) {
        snapshotStore(a, ctx);
        noSession(a, ctx);
        happyPath(a, ctx);
        offlineKeepsOldAndBacksOff(a, ctx);
        failureMapping(a, ctx);
        orgSelection(a, ctx);
        logoutDuringFetch(a, ctx);
        lastBeforeFirstFetch(a, ctx);
        serialized(a, ctx);
        productionPathTied(a, ctx);
        logoutClearsSnapshot(a, ctx);
    }

    // ---------- SnapshotStore ----------

    private static void snapshotStore(Assert a, Context ctx) {
        String name = "cuw-snapstore-test-" + System.nanoTime();
        try {
            SnapshotStore s = new SnapshotStore(ctx, name);
            a.isTrue("vacio: sin modelo", s.lastModel() == null);
            a.isTrue("vacio: sin hora", s.lastFetchInstant() == null);
            a.eq("vacio: sin orgs", 0, s.knownOrgs().size());

            s.remember(model(12.3, 45.678), T0);
            UsageModel m = s.lastModel();
            a.isTrue("remember -> hay modelo", m != null);
            // Exacto, no aproximado: un float deformaria 12.3 en 12.3000001907...
            a.eq("porcentaje de sesion exacto", 12.3, m.session.percent);
            a.eq("porcentaje semanal exacto", 45.678, m.weekly.percent);
            a.eq("reinicio de sesion", T0.plusSeconds(3 * 3600), m.session.resetsAt);
            a.eq("reinicio semanal", T0.plusSeconds(3 * 86400), m.weekly.resetsAt);
            a.eq("hora de la consulta", T0, s.lastFetchInstant());

            s.remember(new UsageModel(Source.CLAUDE_AI, new Window(1, null), new Window(2, null),
                    new ArrayList<>(), new ArrayList<>()), T0);
            a.isTrue("resets null sobrevive (sesion)", s.lastModel().session.resetsAt == null);
            a.isTrue("resets null sobrevive (semana)", s.lastModel().weekly.resetsAt == null);

            s.rememberOrgs(Arrays.asList(new UsageClient.Org(ORG1, "Mia, la buena"),
                    new UsageClient.Org(ORG2, null)));
            List<UsageClient.Org> orgs = s.knownOrgs();
            a.eq("dos orgs", 2, orgs.size());
            a.eq("uuid 1", ORG1, orgs.get(0).uuid);
            a.eq("la coma del nombre no parte la lista", "Mia  la buena", orgs.get(0).name);
            a.eq("uuid 2", ORG2, orgs.get(1).uuid);
            a.isTrue("nombre null sobrevive", orgs.get(1).name == null);
            a.isTrue("remember no toco las orgs", s.knownOrgs().size() == 2);
            s.remember(model(5, 6), T0);
            a.eq("rememberOrgs no toca el modelo", 6.0, s.lastModel().weekly.percent);

            // Valor corrupto: se trata como "no hay", sin lanzar.
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    .putString("last_weekly_percent", "no-es-numero").commit();
            a.isTrue("porcentaje corrupto -> null", s.lastModel() == null);

            a.isTrue("clear devuelve true", s.clear());
            a.isTrue("clear: sin modelo", s.lastModel() == null);
            a.isTrue("clear: sin hora", s.lastFetchInstant() == null);
            a.eq("clear: sin orgs", 0, s.knownOrgs().size());
        } finally {
            ctx.deleteSharedPreferences(name);
        }
    }

    // ---------- UsageRefresher ----------

    private static void noSession(Assert a, Context ctx) {
        Rig r = new Rig(ctx, false, null);
        try {
            Snapshot s = r.refresher.refresh();
            a.eq("sin sesion -> NO_SESSION", Snapshot.Problem.NO_SESSION, s.problem);
            a.isTrue("sin sesion no hay datos", !s.hasData());
            a.eq("sin sesion no se toco la red", 0, r.fake.orgCalls + r.fake.usageCalls.size());
            a.eq("last() sin sesion", Snapshot.Problem.NO_SESSION, r.refresher.last().problem);
        } finally { r.close(); }

        // Una cookie sin sessionKey no es una sesion.
        Rig r2 = new Rig(ctx, true, "otra=cosa");
        try {
            a.eq("cookie sin sessionKey -> NO_SESSION", Snapshot.Problem.NO_SESSION,
                    r2.refresher.refresh().problem);
            a.eq("y sin red", 0, r2.fake.orgCalls + r2.fake.usageCalls.size());
        } finally { r2.close(); }
    }

    private static void happyPath(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            Snapshot s = r.refresher.refresh();
            a.isTrue("exito: sin problema", s.problem == null);
            a.isTrue("exito: hay datos", s.hasData());
            a.eq("exito: porcentaje semanal", 40.0, s.model.weekly.percent);
            a.eq("exito: hora = now", T0, s.fetchedAt);
            a.isTrue("exito: el nucleo calculo el dia", s.day != null);
            a.isTrue("exito: el nucleo calculo la marca", s.paceMark != null);
            a.isTrue("exito: hay pronostico de sesion", s.sessionForecast != null);
            a.isTrue("exito: hay pronostico semanal", s.weeklyForecast != null);
            a.isTrue("la muestra se guardo (append devolvio true)", r.refresher.lastSampleStored);
            try {
                List<Sample> got = r.samples.load(T0);
                a.eq("hay una muestra en disco", 1, got.size());
                a.eq("la muestra es la semanal", 40.0, got.get(0).percent);
                a.eq("la muestra lleva su resetsAt", T0.plusSeconds(3 * 86400), got.get(0).resetsAt);
                a.eq("la muestra lleva el mismo now", T0, got.get(0).t);
            } catch (IOException e) {
                a.fail("no se pudo leer el almacen de muestras");
            }
            a.eq("las orgs se recordaron", 1, r.meta.knownOrgs().size());
            Snapshot again = r.refresher.last();
            a.isTrue("last() tras el exito: sin problema", again.problem == null);
            a.eq("last() tras el exito: mismo dato", 40.0, again.model.weekly.percent);
            a.eq("last() tras el exito: misma hora", T0, again.fetchedAt);
            a.isTrue("last() recalcula el dia, no lo persiste", again.day != null);

            // Con pista y orgs conocidas, el segundo refresco no vuelve a /organizations.
            int orgCalls = r.fake.orgCalls;
            r.now[0] = T0.plusSeconds(900);
            r.refresher.refresh();
            a.eq("con pista y orgs conocidas no se pide /organizations", orgCalls, r.fake.orgCalls);
            a.isTrue("el segundo refresco si consulto /usage", r.fake.usageCalls.size() == 2);

            // El sello de tiempo: con un reloj que avanza a cada lectura, la muestra y el
            // Snapshot llevan el MISMO instante (una sola captura de `now`).
            Rig t = new Rig(ctx, true, COOKIES);
            try {
                int[] ticks = {0};
                t.refresher = new UsageRefresher(t.session, t.samples, t.meta, t.prefs, c -> t.fake,
                        () -> T0.plusSeconds(ticks[0]++));
                t.fake.orgs = r.fake.orgs;
                t.fake.usage.put(ORG1, model(1, 2));
                Snapshot ts = t.refresher.refresh();
                a.isTrue("reloj que avanza: la muestra no se rechazo", t.refresher.lastSampleStored);
                List<Sample> got = t.samples.load(T0.plusSeconds(60));
                a.eq("reloj que avanza: una muestra", 1, got.size());
                a.eq("la muestra y el Snapshot comparten instante", ts.fetchedAt, got.get(0).t);
            } catch (IOException e) {
                a.fail("no se pudo leer el almacen de muestras (reloj)");
            } finally { t.close(); }
        } finally { r.close(); }
    }

    private static void offlineKeepsOldAndBacksOff(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            a.isTrue("preparacion: primer refresco bueno", r.refresher.refresh().problem == null);
            int calls = r.fake.usageCalls.size();

            // Se cae la red: el ultimo dato sigue, con SU hora.
            r.now[0] = T0.plusSeconds(900);
            r.fake.usage.put(ORG1, new IOException("sin red"));
            Snapshot s = r.refresher.refresh();
            a.eq("sin red -> OFFLINE", Snapshot.Problem.OFFLINE, s.problem);
            a.isTrue("sin red conserva el dato", s.hasData());
            a.eq("sin red: el dato viejo", 40.0, s.model.weekly.percent);
            a.eq("sin red: con la hora vieja, no la de ahora", T0, s.fetchedAt);
            a.eq("sin red: se intento una vez", calls + 1, r.fake.usageCalls.size());
            a.eq("backoff: 1er fallo, 1 min", T0.plusSeconds(900 + 60).getEpochSecond(),
                    prefs(r).getLong("backoff_next_allowed_at", -1));
            a.eq("backoff: contador en 1", 1, prefs(r).getInt("backoff_attempt", -1));

            // Toques repetidos dentro de la espera: ni red ni incremento del contador.
            r.now[0] = T0.plusSeconds(910);
            for (int i = 0; i < 5; i++) r.refresher.refresh();
            Snapshot held = r.refresher.refresh();
            a.eq("en espera: no hay peticiones nuevas", calls + 1, r.fake.usageCalls.size());
            a.eq("en espera: el contador no sube", 1, prefs(r).getInt("backoff_attempt", -1));
            a.eq("en espera: sigue diciendo OFFLINE", Snapshot.Problem.OFFLINE, held.problem);
            a.eq("en espera: con el dato viejo", 40.0, held.model.weekly.percent);

            // Pasada la espera, otro fallo: 2 min.
            r.now[0] = T0.plusSeconds(900 + 61);
            r.refresher.refresh();
            a.eq("2o fallo consulta de nuevo", calls + 2, r.fake.usageCalls.size());
            a.eq("backoff: 2o fallo, 2 min", T0.plusSeconds(900 + 61 + 120).getEpochSecond(),
                    prefs(r).getLong("backoff_next_allowed_at", -1));

            // Vuelve la red: se recupera y la cuenta se reinicia.
            r.now[0] = T0.plusSeconds(2000);
            r.fake.usage.put(ORG1, model(31, 41));
            Snapshot ok = r.refresher.refresh();
            a.isTrue("recupera: sin problema", ok.problem == null);
            a.eq("recupera: dato nuevo", 41.0, ok.model.weekly.percent);
            a.isTrue("recupera: sin contador", !prefs(r).contains("backoff_attempt"));
            a.isTrue("recupera: sin espera", !prefs(r).contains("backoff_next_allowed_at"));
            a.isTrue("recupera: sin problema anotado", !prefs(r).contains("backoff_last_problem"));
        } finally { r.close(); }
    }

    private static SharedPreferences prefs(Rig r) {
        return r.ctx.getSharedPreferences(r.prefsName, Context.MODE_PRIVATE);
    }

    /** Cada fracaso del cliente se convierte en SU problema, y solo los que esperan hacen backoff. */
    private static void failureMapping(Assert a, Context ctx) {
        Object[][] cases = {
            {new AuthExpiredException("401"), Snapshot.Problem.AUTH_EXPIRED, false},
            {new BlockedException("403"), Snapshot.Problem.BLOCKED, true},
            {new UsageClient.RetryLaterException("HTTP 429"), Snapshot.Problem.OFFLINE, true},
            {new IOException("x"), Snapshot.Problem.OFFLINE, true},
            {new UnrecognizedFormatException("x"), Snapshot.Problem.BAD_FORMAT, true},
        };
        for (Object[] c : cases) {
            String what = c[0].getClass().getSimpleName();
            Rig r = new Rig(ctx, true, COOKIES);
            try {
                r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
                r.fake.usage.put(ORG1, c[0]);
                Snapshot s = r.refresher.refresh();
                a.eq(what + " -> problema", c[1], s.problem);
                a.eq(what + " sin datos previos: sin modelo", false, s.hasData());
                a.eq(what + " -> hubo backoff", c[2], prefs(r).contains("backoff_next_allowed_at"));
                a.eq(what + " realmente llego a consultarse", 1, r.fake.usageCalls.size());
                a.isTrue(what + " no guardo muestra", samplesEmpty(a, r));
            } finally { r.close(); }
        }

        // Un fallo al pedir /organizations tambien se clasifica.
        Rig r = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r.fake.orgsFail = new AuthExpiredException("401");
            a.eq("401 en /organizations -> AUTH_EXPIRED", Snapshot.Problem.AUTH_EXPIRED,
                    r.refresher.refresh().problem);
            a.eq("y se pidio /organizations", 1, r.fake.orgCalls);
        } finally { r.close(); }

        // Un RuntimeException inesperado no escapa de refresh().
        Rig r2 = new Rig(ctx, true, COOKIES);
        try {
            r2.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r2.fake.onUsage = () -> { throw new IllegalStateException("boom"); };
            Snapshot s = r2.refresher.refresh();
            a.eq("RuntimeException no escapa -> BAD_FORMAT", Snapshot.Problem.BAD_FORMAT, s.problem);
        } finally { r2.close(); }
    }

    private static boolean samplesEmpty(Assert a, Rig r) {
        try { return r.samples.load(r.now[0]).isEmpty(); } catch (IOException e) { return false; }
    }

    /** Regla D2 y la politica de red: un fallo de red NUNCA elige una organizacion por descarte. */
    private static void orgSelection(Assert a, Context ctx) {
        List<UsageClient.Org> two = Arrays.asList(
                new UsageClient.Org(ORG1, "Uno"), new UsageClient.Org(ORG2, "Dos"));

        // Sin pista, la 1a falla por RED y la 2a responde: NO se elige la 2a.
        Rig r = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r.fake.orgs = two;
            r.fake.usage.put(ORG1, new IOException("sin red"));
            r.fake.usage.put(ORG2, model(9, 9));
            Snapshot s = r.refresher.refresh();
            a.eq("red mala en la 1a: OFFLINE, no se elige la otra", Snapshot.Problem.OFFLINE, s.problem);
            a.isTrue("y no se pinto la cuota de la 2a", !s.hasData());
            a.eq("no se siguio sondeando tras el fallo", Arrays.asList(ORG1), r.fake.usageCalls);
            a.isTrue("no se guardo muestra de la 2a", samplesEmpty(a, r));
        } finally { r.close(); }

        // La 1a "no sirve" de verdad (el servidor contesto raro) y la 2a responde: se elige la 2a.
        Rig r2 = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r2.fake.orgs = two;
            r2.fake.usage.put(ORG1, new UnrecognizedFormatException("x"));
            r2.fake.usage.put(ORG2, model(9, 19));
            Snapshot s = r2.refresher.refresh();
            a.isTrue("una no sirve y otra responde: sin problema", s.problem == null);
            a.eq("se muestra la que responde", 19.0, s.model.weekly.percent);
        } finally { r2.close(); }

        // Las dos responden y no hay pista: que elija el usuario; no se escribe nada.
        Rig r3 = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r3.fake.orgs = two;
            r3.fake.usage.put(ORG1, model(1, 11));
            r3.fake.usage.put(ORG2, model(2, 22));
            Snapshot s = r3.refresher.refresh();
            a.eq("varias responden -> CHOOSE_ORG", Snapshot.Problem.CHOOSE_ORG, s.problem);
            a.isTrue("CHOOSE_ORG sin datos", !s.hasData());
            a.isTrue("CHOOSE_ORG no guarda muestra", samplesEmpty(a, r3));
            a.isTrue("CHOOSE_ORG no activa backoff", !prefs(r3).contains("backoff_next_allowed_at"));
            a.eq("las orgs quedaron recordadas para Ajustes", 2, r3.meta.knownOrgs().size());
        } finally { r3.close(); }

        // Ninguna sirve -> BAD_FORMAT.
        Rig r4 = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r4.fake.orgs = two;
            r4.fake.usage.put(ORG1, new UnrecognizedFormatException("x"));
            r4.fake.usage.put(ORG2, new UnrecognizedFormatException("x"));
            a.eq("ninguna sirve -> BAD_FORMAT", Snapshot.Problem.BAD_FORMAT,
                    r4.refresher.refresh().problem);
            a.eq("se probaron las dos", 2, r4.fake.usageCalls.size());
        } finally { r4.close(); }

        // Seleccion manual: solo se consulta esa, usando las orgs ya conocidas.
        Rig r5 = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r5.meta.rememberOrgs(two);
            prefs(r5).edit().putString(SettingsActivity.KEY_ORG, ORG2).commit();
            r5.fake.usage.put(ORG1, model(1, 11));
            r5.fake.usage.put(ORG2, model(2, 22));
            Snapshot s = r5.refresher.refresh();
            a.eq("manual: se muestra la elegida", 22.0, s.model.weekly.percent);
            a.eq("manual: solo se consulto esa", Arrays.asList(ORG2), r5.fake.usageCalls);
            a.eq("manual: con orgs conocidas no se pide /organizations", 0, r5.fake.orgCalls);
        } finally { r5.close(); }

        // lastActiveOrg que responde gana sin sondear la otra.
        Rig r6 = new Rig(ctx, true, "sessionKey=falsa; lastActiveOrg=" + ORG2);
        try {
            r6.fake.orgs = two;
            r6.fake.usage.put(ORG1, model(1, 11));
            r6.fake.usage.put(ORG2, model(2, 22));
            Snapshot s = r6.refresher.refresh();
            a.eq("pista: se muestra la activa", 22.0, s.model.weekly.percent);
            a.eq("pista: solo se consulto esa", Arrays.asList(ORG2), r6.fake.usageCalls);
        } finally { r6.close(); }
    }

    /** El logout llega mientras se consulta: lo que vuelve no debe resucitar el historico. */
    private static void logoutDuringFetch(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            r.fake.onUsage = () -> r.session.clear();   // "cerrar sesion" en pleno vuelo
            Snapshot s = r.refresher.refresh();
            a.eq("logout en vuelo: NO_SESSION", Snapshot.Problem.NO_SESSION, s.problem);
            a.eq("la consulta si ocurrio (no pasa en vacio)", 1, r.fake.usageCalls.size());
            a.isTrue("logout en vuelo: no se escribio muestra", samplesEmpty(a, r));
            a.isTrue("logout en vuelo: no se guardo modelo", r.meta.lastModel() == null);
        } finally { r.close(); }
    }

    private static void lastBeforeFirstFetch(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            Snapshot s = r.refresher.last();
            a.eq("sesion sin consulta previa -> LOADING, no OFFLINE", Snapshot.Problem.LOADING, s.problem);
            a.isTrue("LOADING sin datos", !s.hasData());
        } finally { r.close(); }
    }

    /** Dos refrescos a la vez no se solapan: el candado serializa la parte de red y de disco. */
    private static void serialized(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger maxInside = new AtomicInteger();
            r.fake.onUsage = () -> {
                int n = inside.incrementAndGet();
                maxInside.accumulateAndGet(n, Math::max);
                try { Thread.sleep(80); } catch (InterruptedException ignored) { }
                inside.decrementAndGet();
            };
            Thread[] ts = new Thread[3];
            Snapshot[] out = new Snapshot[3];
            for (int i = 0; i < ts.length; i++) {
                final int k = i;
                ts[i] = new Thread(() -> out[k] = r.refresher.refresh());
                ts[i].start();
            }
            for (Thread t : ts) t.join();
            a.eq("las tres consultas ocurrieron", 3, r.fake.usageCalls.size());
            a.eq("nunca dos consultas a la vez", 1, maxInside.get());
            boolean allOk = true;
            for (Snapshot s : out) allOk &= s != null && s.problem == null;
            a.isTrue("las tres terminaron bien", allOk);
            a.eq("las tres muestras se guardaron sin pisarse", 3, r.samples.load(T0).size());
        } catch (Exception e) {
            a.fail("serializacion lanzo " + e.getClass().getSimpleName());
        } finally { r.close(); }
    }

    /**
     * Ruling 5: quien escribe (UsageRefresher) y quien borra (Session.logout) usan la MISMA via.
     * Se afirma contra el refrescador de produccion construido de verdad, no contra una copia de
     * la formula de la ruta: si alguno construye el almacen a mano y diverge, esto falla.
     */
    private static void productionPathTied(Assert a, Context ctx) {
        UsageRefresher prod = new UsageRefresher(ctx);
        String writer = prod.samples() == null ? null : prod.samples().dir().getAbsolutePath();
        a.isTrue("el refrescador de produccion abrio su almacen", writer != null);
        a.eq("escritor y logout comparten directorio",
                Session.samplesFor(ctx).dir().getAbsolutePath(), writer);
        a.eq("y es el de produccion de SampleStore.of",
                SampleStore.of(ctx).dir().getAbsolutePath(), writer);
    }

    /** Ruling 3: el logout borra tambien lo que guarda SnapshotStore. */
    private static void logoutClearsSnapshot(Assert a, Context ctx) {
        String name = "cuw-logout-snap-test-" + System.nanoTime();
        long n = System.nanoTime();
        SessionStore ss = new SessionStore(ctx, "logout-snap-" + n + ".bin", "cuw-logout-snap-" + n);
        SampleStore samples = new SampleStore(new File(ctx.getCacheDir(), "logout-snap-samples-" + n));
        try {
            SnapshotStore meta = new SnapshotStore(ctx, name);
            meta.remember(model(1, 2), T0);
            meta.rememberOrgs(Arrays.asList(new UsageClient.Org(ORG1, "Uno")));
            a.isTrue("antes: hay modelo", meta.lastModel() != null);
            a.isTrue("antes: hay hora", meta.lastFetchInstant() != null);
            a.eq("antes: hay orgs", 1, meta.knownOrgs().size());
            // Las escrituras son apply(): se espera a que esten en disco para que "antes" sea cierto.
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit();

            a.isTrue("logout devuelve true", Session.logout(ctx, ss, samples, name, false));

            SnapshotStore after = new SnapshotStore(ctx, name);
            a.isTrue("logout borra el modelo", after.lastModel() == null);
            a.isTrue("logout borra la hora", after.lastFetchInstant() == null);
            a.eq("logout borra las orgs", 0, after.knownOrgs().size());
        } finally {
            ss.clear();
            samples.clear();
            ctx.deleteSharedPreferences(name);
        }
    }
}
