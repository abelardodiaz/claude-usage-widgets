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
        Runnable onOrgs;

        @Override public List<UsageClient.Org> organizations() throws IOException,
                AuthExpiredException, BlockedException, UsageClient.RetryLaterException,
                UnrecognizedFormatException {
            orgCalls++;
            if (onOrgs != null) onOrgs.run();
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
        logoutInFlightWritesNothing(a, ctx);
        transientKeystore(a, ctx);
        recoveryPathNeverThrows(a, ctx);
        orgCacheRevalidates(a, ctx);
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
                    new UsageClient.Org(ORG2, null)), T0);
            a.eq("rememberOrgs guarda la hora de la lista", T0, s.orgsFetchedAt());
            // Independencia, en el orden que puede fallar: lo que rememberOrgs acaba de hacer no
            // debe haber pisado el modelo guardado antes por remember.
            a.eq("rememberOrgs no toca el modelo", 2.0, s.lastModel().weekly.percent);
            List<UsageClient.Org> orgs = s.knownOrgs();
            a.eq("dos orgs", 2, orgs.size());
            a.eq("uuid 1", ORG1, orgs.get(0).uuid);
            a.eq("la coma del nombre no parte la lista", "Mia  la buena", orgs.get(0).name);
            a.eq("uuid 2", ORG2, orgs.get(1).uuid);
            a.isTrue("nombre null sobrevive", orgs.get(1).name == null);
            // Y al reves: remember (despues de rememberOrgs) no pisa las orgs.
            s.remember(model(5, 6), T0);
            a.eq("remember no toca las orgs", 2, s.knownOrgs().size());
            a.eq("remember guardo lo suyo", 6.0, s.lastModel().weekly.percent);

            // Valor corrupto: se trata como "no hay", sin lanzar.
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    .putString("last_weekly_percent", "no-es-numero").commit();
            a.isTrue("porcentaje corrupto -> null", s.lastModel() == null);

            // forgetOrgs es estrecho: tira las orgs y deja el modelo.
            s.rememberOrgs(Arrays.asList(new UsageClient.Org(ORG1, "Uno")), T0);
            a.isTrue("forgetOrgs devuelve true", s.forgetOrgs());
            a.eq("forgetOrgs borra las orgs", 0, s.knownOrgs().size());
            a.isTrue("forgetOrgs borra su hora", s.orgsFetchedAt() == null);
            a.isTrue("forgetOrgs NO toca el modelo", s.lastFetchInstant() != null);
            s.remember(model(5, 6), T0);
            a.isTrue("clear devuelve true", s.clear());
            a.isTrue("clear: sin modelo", s.lastModel() == null);
            a.isTrue("clear: sin hora", s.lastFetchInstant() == null);
            a.eq("clear: sin orgs", 0, s.knownOrgs().size());
            a.isTrue("clear: sin hora de orgs", s.orgsFetchedAt() == null);
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

        // Un sessionKey vacio tampoco (mismo criterio que minimalCookies).
        Rig r3 = new Rig(ctx, true, "sessionKey=; otra=cosa");
        try {
            a.eq("sessionKey vacio -> NO_SESSION", Snapshot.Problem.NO_SESSION,
                    r3.refresher.refresh().problem);
        } finally { r3.close(); }

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
                r.fake.usage.put(ORG1, model(30, 40));
                a.isTrue(what + ": preparacion, primer refresco bueno", r.refresher.refresh().problem == null);
                int calls = r.fake.usageCalls.size();

                r.now[0] = T0.plusSeconds(3600);
                r.fake.usage.put(ORG1, c[0]);
                Snapshot s = r.refresher.refresh();
                a.eq(what + " -> problema", c[1], s.problem);
                // Lo que de verdad importa: el dato bueno anterior SIGUE ahi, con su hora vieja.
                a.isTrue(what + " conserva el dato viejo", s.hasData());
                a.eq(what + ": dato viejo exacto", 40.0, s.model.weekly.percent);
                a.eq(what + ": con la hora vieja", T0, s.fetchedAt);
                a.eq(what + " -> hubo backoff", c[2], prefs(r).contains("backoff_next_allowed_at"));
                a.isTrue(what + " realmente llego a consultarse", r.fake.usageCalls.size() > calls);
                try {
                    a.eq(what + " no guardo muestra nueva", 1, r.samples.load(r.now[0]).size());
                } catch (IOException e) { a.fail(what + ": no se pudo leer el almacen"); }
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
            r5.meta.rememberOrgs(two, T0);
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
            AtomicInteger tick = new AtomicInteger();
            r.refresher = new UsageRefresher(r.session, r.samples, r.meta, r.prefs, c -> r.fake,
                    () -> T0.plusSeconds(tick.incrementAndGet()));
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
            // t distintos: si el nucleo las deduplicara por t, aqui seguirian siendo tres. (Que no
            // se pisen al escribir lo garantiza tambien el candado de SampleStore; lo del
            // candado del refrescador es la comprobacion de arriba, maxInside.)
            a.eq("las tres muestras (t distintos) se guardaron", 3,
                    r.samples.load(T0.plusSeconds(600)).size());
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

    /**
     * Ruling 3, version que muerde: el almacen de snapshot vive en OTRO archivo de preferencias
     * que el que el logout vacia. Si se quita la linea de `snapshot.clear()` en Session.logout,
     * esta prueba falla (antes el vaciado general de las preferencias lo borraba igual).
     * Ademas ata la via de produccion como el ruling 5.
     */
    private static void logoutClearsSnapshot(Assert a, Context ctx) {
        long n = System.nanoTime();
        String snapName = "cuw-logout-snap-A-" + n;
        String logoutPrefs = "cuw-logout-snap-B-" + n;
        SessionStore ss = new SessionStore(ctx, "logout-snap-" + n + ".bin", "cuw-logout-snap-" + n);
        SampleStore samples = new SampleStore(new File(ctx.getCacheDir(), "logout-snap-samples-" + n));
        try {
            SnapshotStore meta = new SnapshotStore(ctx, snapName);
            meta.remember(model(1, 2), T0);
            meta.rememberOrgs(Arrays.asList(new UsageClient.Org(ORG1, "Uno")), T0);
            ctx.getSharedPreferences(snapName, Context.MODE_PRIVATE).edit().commit();
            a.isTrue("antes: hay modelo", meta.lastModel() != null);
            a.isTrue("antes: hay hora", meta.lastFetchInstant() != null);
            a.eq("antes: hay orgs", 1, meta.knownOrgs().size());
            a.isTrue("antes: hay hora de orgs", meta.orgsFetchedAt() != null);

            a.isTrue("logout devuelve true",
                    Session.logout(ctx, ss, samples, meta, logoutPrefs, false));

            a.isTrue("logout borra el modelo", meta.lastModel() == null);
            a.isTrue("logout borra la hora", meta.lastFetchInstant() == null);
            a.eq("logout borra las orgs", 0, meta.knownOrgs().size());
            a.isTrue("logout borra la hora de las orgs", meta.orgsFetchedAt() == null);
        } finally {
            ss.clear();
            samples.clear();
            ctx.deleteSharedPreferences(snapName);
            ctx.deleteSharedPreferences(logoutPrefs);
        }
        // La via de produccion: el refrescador guarda por donde el logout borra.
        UsageRefresher prod = new UsageRefresher(ctx);
        a.eq("refrescador y logout comparten el archivo de preferencias",
                Session.snapshotFor(ctx).prefsName(), prod.meta().prefsName());
        a.eq("y es el de produccion", SettingsActivity.PREFS, prod.meta().prefsName());
    }

    /** Un logout que llega EN VUELO no deja nada reescrito: ni uuid, ni claves de espera. */
    private static void logoutInFlightWritesNothing(Assert a, Context ctx) {
        // (1) Llega mientras se pide /organizations (antes de que el refrescador guarde la lista).
        Rig r = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(1, 2));
            r.fake.onOrgs = () -> Session.logout(ctx, r.session, r.samples, r.meta, r.prefsName, false);
            Snapshot s = r.refresher.refresh();
            a.eq("logout durante /organizations: la consulta ocurrio", 1, r.fake.orgCalls);
            a.eq("logout durante /organizations -> NO_SESSION", Snapshot.Problem.NO_SESSION, s.problem);
            a.eq("no resucitaron las orgs", 0, r.meta.knownOrgs().size());
            a.eq("y no se hizo ni una peticion /usage con la cookie revocada", 0, r.fake.usageCalls.size());
            // Instancia VIVA (donde caeria un apply() tardio), no una nueva tras el borrado.
            a.isTrue("el archivo de preferencias sigue vacio", r.prefs.getAll().isEmpty());
        } finally { r.close(); }

        // (1b) Solo la epoca cambia (p. ej. logout y nuevo login muy seguidos): tampoco se escribe.
        Rig r1b = new Rig(ctx, true, "sessionKey=falsa");
        try {
            r1b.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r1b.fake.usage.put(ORG1, model(1, 2));
            r1b.fake.onOrgs = () -> Session.EPOCH.incrementAndGet();
            Snapshot s = r1b.refresher.refresh();
            a.eq("epoca cambiada: la consulta ocurrio", 1, r1b.fake.orgCalls);
            a.eq("epoca cambiada -> NO_SESSION", Snapshot.Problem.NO_SESSION, s.problem);
            a.eq("epoca cambiada: no se guardaron orgs", 0, r1b.meta.knownOrgs().size());
            a.isTrue("epoca cambiada: no se guardo modelo", r1b.meta.lastModel() == null);
        } finally { r1b.close(); }

        // (1c) El logout gana la carrera DESPUES de la ultima comprobacion (la tercera lectura del
        // reloj es justo la de `now`, tras `alive()` y antes de escribir): lo escrito se deshace.
        Rig r1c = new Rig(ctx, true, COOKIES);
        try {
            r1c.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r1c.fake.usage.put(ORG1, model(30, 40));
            int[] reads = {0};
            r1c.refresher = new UsageRefresher(r1c.session, r1c.samples, r1c.meta, r1c.prefs,
                    c -> r1c.fake, () -> {
                        if (++reads[0] == 3) {
                            Session.logout(ctx, r1c.session, r1c.samples, r1c.meta, r1c.prefsName, false);
                        }
                        return T0;
                    });
            Snapshot s = r1c.refresher.refresh();
            a.eq("el logout se disparo en la ventana (no pasa en vacio)", true, reads[0] >= 3);
            a.eq("logout tras la comprobacion -> NO_SESSION", Snapshot.Problem.NO_SESSION, s.problem);
            a.isTrue("se deshizo la muestra", samplesEmpty(a, r1c));
            a.isTrue("se deshizo el modelo", r1c.meta.lastModel() == null);
            a.isTrue("se deshicieron las orgs", r1c.meta.knownOrgs().isEmpty());
            a.isTrue("preferencias vacias", r1c.prefs.getAll().isEmpty());
        } finally { r1c.close(); }

        // (1d) Igual para keepOld: el logout cae entre la comprobacion y el apply() de la espera
        // (la tercera lectura del reloj es la de dentro del bloque de escritura).
        Rig r1d = new Rig(ctx, true, COOKIES);
        try {
            r1d.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r1d.fake.usage.put(ORG1, new IOException("sin red"));
            int[] reads = {0};
            r1d.refresher = new UsageRefresher(r1d.session, r1d.samples, r1d.meta, r1d.prefs,
                    c -> r1d.fake, () -> {
                        if (++reads[0] == 3) {
                            Session.logout(ctx, r1d.session, r1d.samples, r1d.meta, r1d.prefsName, false);
                        }
                        return T0;
                    });
            r1d.refresher.refresh();
            a.eq("keepOld: la consulta fallo de verdad", 1, r1d.fake.usageCalls.size());
            a.isTrue("keepOld: el logout se disparo en la ventana", reads[0] >= 3);
            a.isTrue("keepOld: no quedaron claves de espera", !r1d.prefs.contains("backoff_next_allowed_at"));
            a.isTrue("keepOld: preferencias vacias", r1d.prefs.getAll().isEmpty());
        } finally { r1d.close(); }

        // (1e) Que hasSession() de falso SIN logout (epoca igual) no destruye datos legitimos:
        // para borrar solo cuenta la epoca. Se simula vaciando la sesion desde la misma lectura
        // del reloj que cae tras la ultima comprobacion; no hay Session.logout, asi que no es un cierre.
        Rig r1e = new Rig(ctx, true, COOKIES);
        try {
            r1e.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r1e.fake.usage.put(ORG1, model(30, 40));
            int[] reads = {0};
            r1e.refresher = new UsageRefresher(r1e.session, r1e.samples, r1e.meta, r1e.prefs,
                    c -> r1e.fake, () -> {
                        if (++reads[0] == 3) r1e.session.clear();
                        return T0;
                    });
            Snapshot s = r1e.refresher.refresh();
            a.isTrue("1e: la sesion se vacio en la ventana", !r1e.session.hasSession());
            a.isTrue("1e: sin logout no se destruye el modelo", r1e.meta.lastModel() != null);
            a.eq("1e: ni la muestra", 1, r1e.samples.load(T0).size());
            a.isTrue("1e: y el Snapshot sigue con datos", s.hasData());
        } catch (IOException e) {
            a.fail("1e: no se pudo leer el almacen");
        } finally { r1e.close(); }

        // (2) Llega mientras la consulta de uso falla por red: keepOld no escribe la espera.
        Rig r2 = new Rig(ctx, true, COOKIES);
        try {
            r2.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r2.fake.usage.put(ORG1, new IOException("sin red"));
            r2.fake.onUsage = () -> Session.logout(ctx, r2.session, r2.samples, r2.meta, r2.prefsName, false);
            r2.refresher.refresh();
            a.eq("logout durante /usage: la consulta ocurrio", 1, r2.fake.usageCalls.size());
            // Se mira la instancia VIVA del refrescador (donde escribiria) y no se espera a apply().
            a.isTrue("no resucitaron las claves de espera", r2.prefs.getAll().isEmpty());
            a.isTrue("ni la de la espera en concreto", !r2.prefs.contains("backoff_next_allowed_at"));
        } finally { r2.close(); }
    }

    /** Un fallo transitorio del Keystore no es "sin sesion": se conserva el dato y la sesion. */
    private static void transientKeystore(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            a.isTrue("preparacion: refresco bueno", r.refresher.refresh().problem == null);

            boolean[] fail = {true};
            UsageRefresher flaky = new UsageRefresher(r.session,
                    () -> { if (fail[0]) throw new java.security.GeneralSecurityException("hipo"); return COOKIES; },
                    r.samples, r.meta, r.prefs, c -> r.fake, () -> r.now[0]);
            r.now[0] = T0.plusSeconds(3600);
            Snapshot s = flaky.refresh();
            a.eq("hipo del Keystore -> OFFLINE, no NO_SESSION", Snapshot.Problem.OFFLINE, s.problem);
            a.isTrue("hipo del Keystore: sigue el dato", s.hasData());
            if (s.hasData()) a.eq("hipo del Keystore: el dato de antes", 40.0, s.model.weekly.percent);
            // Cinco toques con el Keystore ocupado: ninguna peticion, ningun backoff.
            for (int i = 0; i < 5; i++) flaky.refresh();
            a.isTrue("hipo: no cuenta como intento", !r.prefs.contains("backoff_attempt"));
            a.isTrue("hipo: no pone espera", !r.prefs.contains("backoff_next_allowed_at"));
            a.eq("hipo: no hubo peticiones nuevas", 1, r.fake.usageCalls.size());
            // Cuando vuelve el Keystore se consulta de inmediato.
            fail[0] = false;
            r.fake.usage.put(ORG1, model(31, 41));
            Snapshot after = flaky.refresh();
            a.isTrue("tras el hipo hay datos", after.hasData());
            if (after.hasData()) a.eq("tras el hipo se consulta sin esperar", 41.0, after.model.weekly.percent);
            a.isTrue("hipo del Keystore: la sesion NO se borro", r.session.hasSession());

            // load() que devuelve null si es "sin sesion".
            UsageRefresher none = new UsageRefresher(r.session, () -> null, r.samples, r.meta,
                    r.prefs, c -> r.fake, () -> r.now[0]);
            a.eq("load() null -> NO_SESSION", Snapshot.Problem.NO_SESSION, none.refresh().problem);
        } finally { r.close(); }
    }

    /** Contrato: `refresh()` y `last()` devuelven un Snapshot pase lo que pase, tambien al recuperarse. */
    private static void recoveryPathNeverThrows(Assert a, Context ctx) {
        Rig r = new Rig(ctx, true, COOKIES);
        try {
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r.fake.usage.put(ORG1, model(30, 40));
            boolean[] armed = {false};
            UsageRefresher bad = new UsageRefresher(r.session, r.samples, r.meta, r.prefs,
                    c -> r.fake, () -> {
                        if (armed[0]) throw new IllegalStateException("reloj roto");
                        return T0;
                    });
            a.isTrue("preparacion: refresco bueno", bad.refresh().problem == null);
            armed[0] = true;   // ahora TODO lo que lea el reloj lanza: tambien keepOld y last()
            Snapshot s = null;
            boolean threw = false;
            try { s = bad.refresh(); } catch (RuntimeException e) { threw = true; }
            a.isTrue("refresh() no lanza aunque el camino de recuperacion falle", !threw);
            a.isTrue("devuelve un Snapshot con problema", s != null && s.problem != null);
            threw = false;
            try { bad.last(); } catch (RuntimeException e) { threw = true; }
            a.isTrue("last() no lanza aunque el reloj falle", !threw);
            // Y no queda pegajoso: con el reloj sano vuelve a funcionar.
            armed[0] = false;
            r.now[0] = T0;
            a.isTrue("sin el fallo, el siguiente refresco va bien", bad.refresh().problem == null);
        } finally { r.close(); }
    }

    /** La lista de organizaciones recordada se revalida: caduca y no atrapa en BAD_FORMAT. */
    private static void orgCacheRevalidates(Assert a, Context ctx) {
        String OLD = "99999999-9999-4999-8999-999999999999";
        // (a) cache vieja: la pista apunta a una org que ya no sirve; se re-pide y se recupera.
        Rig r = new Rig(ctx, true, "sessionKey=falsa; lastActiveOrg=" + OLD);
        try {
            r.meta.rememberOrgs(Arrays.asList(new UsageClient.Org(OLD, "Vieja")), T0);
            r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Nueva"));
            r.fake.usage.put(OLD, new UnrecognizedFormatException("x"));
            r.fake.usage.put(ORG1, model(1, 77));
            Snapshot s = r.refresher.refresh();
            a.eq("cache con org vieja: se re-pidio /organizations", 1, r.fake.orgCalls);
            a.isTrue("cache con org vieja: se recupera", s.problem == null);
            if (s.hasData()) a.eq("cache con org vieja: muestra la nueva", 77.0, s.model.weekly.percent);
            else a.fail("cache con org vieja: no hay datos");
            a.isTrue("la cache se actualizo", !r.meta.knownOrgs().isEmpty()
                    && ORG1.equals(r.meta.knownOrgs().get(0).uuid));
        } finally { r.close(); }

        // (a2) varias responden (ambiguous): NO se revalida, es el estado sin backoff.
        Rig ra = new Rig(ctx, true, "sessionKey=falsa; lastActiveOrg=" + OLD);
        try {
            ra.meta.rememberOrgs(Arrays.asList(new UsageClient.Org(ORG1, "Uno"),
                    new UsageClient.Org(ORG2, "Dos")), T0);
            ra.fake.usage.put(OLD, new UnrecognizedFormatException("x"));
            ra.fake.usage.put(ORG1, model(1, 11));
            ra.fake.usage.put(ORG2, model(2, 22));
            for (int i = 0; i < 3; i++) {
                a.eq("ambiguous " + i, Snapshot.Problem.CHOOSE_ORG, ra.refresher.refresh().problem);
            }
            a.eq("ambiguous no pide /organizations", 0, ra.fake.orgCalls);
        } finally { ra.close(); }

        // (b) caducidad por tiempo: con eleccion posible igualmente se re-pide pasadas 6 h.
        Rig r2 = new Rig(ctx, true, COOKIES);
        try {
            r2.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
            r2.fake.usage.put(ORG1, model(1, 2));
            r2.refresher.refresh();
            a.eq("1er refresco pide /organizations", 1, r2.fake.orgCalls);
            r2.now[0] = T0.plusSeconds(5 * 3600);
            r2.refresher.refresh();
            a.eq("a las 5 h se usa la cache", 1, r2.fake.orgCalls);
            r2.now[0] = T0.plusSeconds(7 * 3600);
            r2.refresher.refresh();
            a.eq("a las 7 h se re-pide", 2, r2.fake.orgCalls);
        } finally { r2.close(); }
    }
}
