package com.claulimitswidgets.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Color;
import com.claudewidgets.core.TodayState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tarea 4.4. NADA de esto toca la red, el JobScheduler real ni los widgets del dueno: el
 * planificador es un doble, el refresco es un Supplier y el pintado un Consumer.
 *
 * Lo que NO se puede probar aqui (hace falta un reinicio de verdad): que el sistema entregue
 * BOOT_COMPLETED, que el job persistido sobreviva y que el primer ciclo llegue.
 */
public final class WidgetUpdateJobTest {

    private static final String TEST_PREFS = "cuw-job-test";

    private WidgetUpdateJobTest() {}

    public static void run(Assert a, Context ctx) {
        info(a, ctx);
        ensure(a, ctx);
        realSchedule(a, ctx);
        cancel(a);
        boot(a);
        gate(a);
        cycles(a);
        pushLock(a);
        logoutPushLock(a);
        jobLifecycle(a);
        logoutHooks(a, ctx);
        runNowDoesNotBlock(a);
        orgs(a, ctx);
        paintEach(a);
        errorsInThreads(a);
        manifest(a, ctx);
        sizes(a, ctx);
        enums(a);
    }

    // ---- doble del planificador ---------------------------------------------------------

    static final class FakeScheduler extends JobScheduler {
        final Map<Integer, JobInfo> pending = new HashMap<>();
        final List<Integer> cancelled = new ArrayList<>();
        int scheduleCalls;
        boolean throwOnSchedule;

        @Override public int schedule(JobInfo job) {
            scheduleCalls++;
            if (throwOnSchedule) throw new SecurityException("sin permiso");
            pending.put(job.getId(), job);
            return RESULT_SUCCESS;
        }
        @Override public int enqueue(JobInfo job, JobWorkItem work) { return RESULT_FAILURE; }
        @Override public void cancel(int id) { cancelled.add(id); pending.remove(id); }
        @Override public void cancelAll() { pending.clear(); }
        @Override public List<JobInfo> getAllPendingJobs() { return new ArrayList<>(pending.values()); }
        @Override public JobInfo getPendingJob(int id) { return pending.get(id); }
    }

    // ---- los dos trabajos ---------------------------------------------------------------

    @SuppressWarnings("deprecation")   // getNetworkType: ver WidgetUpdateJob.same
    private static void info(Assert a, Context ctx) {
        JobInfo p = WidgetUpdateJob.periodicInfo(ctx);
        a.eq("periodico: id", 4201, p.getId());
        a.isTrue("periodico: es periodico", p.isPeriodic());
        a.eq("periodico: cada 15 min", 15 * 60 * 1000L, p.getIntervalMillis());
        a.isTrue("periodico: persiste al reinicio", p.isPersisted());
        a.eq("periodico: cualquier red", JobInfo.NETWORK_TYPE_ANY, p.getNetworkType());
        a.eq("periodico: lo atiende WidgetUpdateJob", WidgetUpdateJob.class.getName(),
                p.getService().getClassName());

        JobInfo b = WidgetUpdateJob.bootInfo(ctx);
        a.eq("de arranque: id", 4202, b.getId());
        a.isTrue("de arranque: distinto del periodico", b.getId() != p.getId());
        a.isTrue("de arranque: unico, no periodico", !b.isPeriodic());
        a.isTrue("de arranque: no persiste (vale solo para este arranque)", !b.isPersisted());
        a.eq("de arranque: necesita red", JobInfo.NETWORK_TYPE_ANY, b.getNetworkType());
        a.eq("de arranque: lo atiende WidgetUpdateJob", WidgetUpdateJob.class.getName(),
                b.getService().getClassName());
    }

    private static void ensure(Assert a, Context ctx) {
        FakeScheduler js = new FakeScheduler();
        JobInfo want = WidgetUpdateJob.periodicInfo(ctx);
        a.isTrue("sin nada pendiente: programa", WidgetUpdateJob.ensure(js, want));
        a.eq("sin nada pendiente: una llamada a schedule", 1, js.scheduleCalls);
        a.isTrue("queda registrado el id", js.pending.containsKey(4201));
        a.isTrue("ya igual: devuelve true", WidgetUpdateJob.ensure(js, WidgetUpdateJob.periodicInfo(ctx)));
        a.eq("ya igual: NO reprograma (cada onUpdate la llama)", 1, js.scheduleCalls);

        // Un periodico registrado con otro periodo (de una version anterior) se reemplaza.
        FakeScheduler old = new FakeScheduler();
        old.pending.put(4201, new JobInfo.Builder(4201,
                new ComponentName(ctx, WidgetUpdateJob.class))
                .setPeriodic(30 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
        a.isTrue("otro periodo: se reprograma", WidgetUpdateJob.ensure(old, want));
        a.eq("otro periodo: schedule llamado", 1, old.scheduleCalls);
        a.eq("otro periodo: ahora es el bueno", 15 * 60 * 1000L, old.pending.get(4201).getIntervalMillis());

        // Igual en todo salvo no persistir: tambien se reemplaza (si no, no sobreviviria al reinicio).
        FakeScheduler np = new FakeScheduler();
        np.pending.put(4201, new JobInfo.Builder(4201, new ComponentName(ctx, WidgetUpdateJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        WidgetUpdateJob.ensure(np, want);
        a.isTrue("sin persistir: se reemplaza por el persistente", np.pending.get(4201).isPersisted());

        FakeScheduler bad = new FakeScheduler();
        bad.throwOnSchedule = true;
        boolean[] ok = {true};
        boolean res = true;
        try {
            res = WidgetUpdateJob.ensure(bad, want);
        } catch (RuntimeException e) {
            ok[0] = false;
        }
        a.isTrue("schedule que lanza (falta el permiso): ensure NO lanza", ok[0]);
        a.isTrue("schedule que lanza: devuelve false", !res);
        a.isTrue("sin planificador: false y no lanza", !WidgetUpdateJob.ensure(null, want));
    }

    /**
     * El JobScheduler DE VERDAD acepta el trabajo: ata manifiesto (BIND_JOB_SERVICE,
     * RECEIVE_BOOT_COMPLETED, ACCESS_NETWORK_STATE) y codigo. Sin cualquiera de los permisos,
     * schedule lanza SecurityException y `ensure` lo traga en un false: este es el unico sitio
     * donde se ve. (Lo encontro: el plan olvidaba ACCESS_NETWORK_STATE.)
     * No se cancela nada despues: es el mismo trabajo que el widget del dueno necesita tener.
     */
    @SuppressWarnings("deprecation")
    private static void realSchedule(Assert a, Context ctx) {
        // Primero se cancela: tras `install -r` el widget ya registro el 4201 en su onUpdate, y
        // `ensure` cortocircuita con un pendiente igual SIN llamar a schedule (pasaria en vacio).
        ctx.getSystemService(JobScheduler.class).cancel(4201);
        a.isTrue("antes de programar no hay pendiente",
                ctx.getSystemService(JobScheduler.class).getPendingJob(4201) == null);
        a.isTrue("JobScheduler real: schedule devuelve true", WidgetUpdateJob.schedule(ctx));
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        JobInfo got = js.getPendingJob(4201);
        a.isTrue("JobScheduler real: el periodico quedo registrado", got != null);
        if (got != null) {
            a.isTrue("JobScheduler real: periodico", got.isPeriodic());
            a.isTrue("JobScheduler real: persistido", got.isPersisted());
            a.isTrue("JobScheduler real: igual a lo pedido",
                    WidgetUpdateJob.same(got, WidgetUpdateJob.periodicInfo(ctx)));
        }
    }

    private static void cancel(Assert a) {
        FakeScheduler js = new FakeScheduler();
        WidgetUpdateJob.cancelAll(js);
        a.isTrue("cancela el periodico", js.cancelled.contains(4201));
        a.isTrue("cancela tambien el de arranque", js.cancelled.contains(4202));
        a.eq("y nada mas", 2, js.cancelled.size());
        boolean threw = false;
        try { WidgetUpdateJob.cancelAll(null); } catch (RuntimeException e) { threw = true; }
        a.isTrue("cancelAll(null) no lanza", !threw);
    }

    private static void boot(Assert a) {
        for (String action : new String[] {Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
            List<String> calls = new ArrayList<>();
            BootReceiver.handle(action, () -> 2, () -> calls.add("schedule"), () -> calls.add("soon"));
            a.eq(action + ": reprograma y pide un refresco, en ese orden",
                    Arrays.asList("schedule", "soon"), calls);
            calls.clear();
            BootReceiver.handle(action, () -> 0, () -> calls.add("schedule"), () -> calls.add("soon"));
            a.eq(action + ": sin widgets no hace nada", Collections.emptyList(), calls);
        }
        for (String other : new String[] {null, "", Intent.ACTION_USER_PRESENT,
                Intent.ACTION_LOCKED_BOOT_COMPLETED, "com.claulimitswidgets.android.BOOT_COMPLETED"}) {
            List<String> calls = new ArrayList<>();
            BootReceiver.handle(other, () -> 2, () -> calls.add("schedule"), () -> calls.add("soon"));
            a.eq("accion ajena <" + other + ">: no hace nada", Collections.emptyList(), calls);
        }
    }

    // ---- la puerta de refrescos inmediatos ----------------------------------------------

    private static void gate(Assert a) {
        WidgetUpdateJob.NowGate g = new WidgetUpdateJob.NowGate();
        a.isTrue("el primero arranca el hilo", g.claim());
        a.isTrue("el segundo NO arranca otro", !g.claim());
        a.isTrue("el tercero tampoco", !g.claim());
        a.isTrue("al terminar una vuelta hay UNA mas pendiente", g.next());
        a.isTrue("pero solo una, por mas toques que hubiera", !g.next());
        a.isTrue("libre otra vez: puede arrancar", g.claim());
        g.abort();
        a.isTrue("tras abortar (el hilo no arranco) tampoco queda cerrada", g.claim());
        a.isTrue("abortar borra lo pendiente", !g.next());
    }

    // ---- una vuelta ---------------------------------------------------------------------

    private static void cycles(Assert a) {
        Snapshot good = Snapshot.of(Snapshot.Problem.OFFLINE);
        List<Snapshot> got = new ArrayList<>();
        List<String> order = new ArrayList<>();
        WidgetUpdateJob.cycle(() -> good, () -> 7, s -> { got.add(s); order.add("push"); },
                () -> order.add("done"));
        a.eq("normal: se pinta lo que devolvio el refresco", Arrays.asList(good), got);
        a.eq("normal: se avisa DESPUES de pintar, una vez", Arrays.asList("push", "done"), order);

        got.clear(); order.clear();
        WidgetUpdateJob.cycle(() -> { throw new IllegalStateException("x"); }, () -> 7,
                s -> { got.add(s); order.add("push"); }, () -> order.add("done"));
        a.eq("refresh lanza: se pinta BAD_FORMAT", 1, got.size());
        if (got.size() == 1) a.eq("refresh lanza: el problema", Snapshot.Problem.BAD_FORMAT, got.get(0).problem);
        a.eq("refresh lanza: aun asi se avisa", Arrays.asList("push", "done"), order);

        got.clear();
        WidgetUpdateJob.cycle(() -> null, () -> 7, got::add, () -> { });
        a.eq("refresh devuelve null: BAD_FORMAT", 1, got.size());

        // Cierre de sesion mientras consultaba: NO se pinta el dato de la cuenta cerrada.
        got.clear(); order.clear();
        AtomicLong epoch = new AtomicLong(1);
        WidgetUpdateJob.cycle(() -> { epoch.incrementAndGet(); return good; }, epoch::get,
                s -> { got.add(s); order.add("push"); }, () -> order.add("done"));
        a.eq("logout durante el refresco: no se pinta nada", 0, got.size());
        a.eq("logout durante el refresco: aun asi se avisa", Arrays.asList("done"), order);

        // Control del caso anterior: sin cambio de epoca SI se pinta (si no, el de arriba seria vacio).
        got.clear();
        AtomicLong still = new AtomicLong(1);
        WidgetUpdateJob.cycle(() -> good, still::get, got::add, () -> { });
        a.eq("sin logout: se pinta", 1, got.size());

        // Pintar falla (el lanzador se fue): no lanza y se avisa igual.
        int[] doneCalls = {0};
        boolean[] threw = {false};
        try {
            WidgetUpdateJob.cycle(() -> good, () -> 1,
                    s -> { throw new IllegalStateException("lanzador"); }, () -> doneCalls[0]++);
        } catch (RuntimeException e) {
            threw[0] = true;
        }
        a.isTrue("el pintado que falla no lanza", !threw[0]);
        a.eq("el pintado que falla: se avisa una vez", 1, doneCalls[0]);

        a.isTrue("pushIfCurrent: epoca igual pinta", WidgetUpdateJob.pushIfCurrent(good, 5, () -> 5, s -> { }));
        a.isTrue("pushIfCurrent: epoca distinta calla", !WidgetUpdateJob.pushIfCurrent(good, 5, () -> 6, s -> { }));
    }

    /**
     * Sin relojes: mientras un refresco pinta (su sumidero espera un cerrojo), arranca `contender`
     * en otro hilo y se espera a que el hilo quede BLOQUEADO en el monitor. Si el candado no
     * existe, el hilo termina sin bloquearse y se detecta. Luego se suelta el primero y se afirma
     * el ORDEN: el sumidero del primero anoto "A fin" antes que el segundo pintara.
     */
    private static void exclusion(Assert a, String what, java.util.function.Consumer<List<String>> contender) {
        Snapshot s = Snapshot.of(Snapshot.Problem.OFFLINE);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> WidgetUpdateJob.pushIfCurrent(s, 1, () -> 1, x -> {
            inside.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            log.add("A fin");
        }));
        first.start();
        try {
            a.isTrue(what + ": el primero esta pintando", inside.await(5, TimeUnit.SECONDS));
            Thread second = new Thread(() -> contender.accept(log));
            second.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.getState() != Thread.State.BLOCKED && second.isAlive()
                    && System.nanoTime() < end) Thread.sleep(5);
            a.isTrue(what + ": el segundo queda BLOQUEADO en el candado (" + second.getState() + ")",
                    second.getState() == Thread.State.BLOCKED);
            release.countDown();
            second.join(5000);
            first.join(5000);
            a.eq(what + ": orden", Arrays.asList("A fin", "B"), new ArrayList<>(log));
        } catch (InterruptedException e) {
            a.fail(what + ": interrumpida");
        } finally {
            release.countDown();
        }
    }

    private static void pushLock(Assert a) {
        Snapshot s = Snapshot.of(Snapshot.Problem.OFFLINE);
        exclusion(a, "dos refrescos", log ->
                WidgetUpdateJob.pushIfCurrent(s, 1, () -> 1, x -> log.add("B")));
    }

    /** El pintado incondicional del logout (`pushLocked`) toma el mismo candado que el del refresco. */
    private static void logoutPushLock(Assert a) {
        Snapshot s = Snapshot.of(Snapshot.Problem.NO_SESSION);
        exclusion(a, "refresco vs pintado del logout", log ->
                WidgetUpdateJob.pushLocked(s, x -> log.add("B")));
    }

    // ---- el ciclo de vida del servicio --------------------------------------------------

    private static void jobLifecycle(Assert a) {
        a.isTrue("onStopJob (real, ignora params) no pide reintento",
                !new WidgetUpdateJob().onStopJob(null));
        a.isTrue("onStopJob no pide reintento", !WidgetUpdateJob.RETRY_ON_STOP);
        a.isTrue("jobFinished no pide reprogramar", !WidgetUpdateJob.RESCHEDULE_ON_FINISH);
        java.util.concurrent.Executor sync = Runnable::run;
        Snapshot good = Snapshot.of(Snapshot.Problem.OFFLINE);

        List<String> log = new ArrayList<>();
        boolean r = WidgetUpdateJob.runJob(sync, () -> 2, () -> log.add("cancel"),
                () -> { log.add("refresh"); return good; }, () -> 1, x -> log.add("push"),
                () -> log.add("finish"));
        a.isTrue("con widgets: arranca (true)", r);
        a.eq("con widgets: consulta, pinta y avisa una vez", Arrays.asList("refresh", "push", "finish"), log);

        log.clear();
        r = WidgetUpdateJob.runJob(sync, () -> 0, () -> log.add("cancel"),
                () -> { log.add("refresh"); return good; }, () -> 1, x -> log.add("push"),
                () -> log.add("finish"));
        a.isTrue("sin widgets: devuelve true (el aviso viene del hilo)", r);
        a.eq("sin widgets: se cancela, NO consulta ni pinta, y avisa", Arrays.asList("cancel", "finish"), log);

        // Un fallo en el hilo no puede dejar el trabajo sin avisar.
        log.clear();
        WidgetUpdateJob.runJob(sync, () -> { throw new IllegalStateException("x"); }, () -> log.add("cancel"),
                () -> good, () -> 1, x -> log.add("push"), () -> log.add("finish"));
        a.eq("el contador de widgets que lanza: avisa igual", Arrays.asList("finish"), log);

        // Aviso unico: `finish` que lanza la primera vez no se repite desde el catch.
        int[] finishes = {0};
        WidgetUpdateJob.runJob(sync, () -> 2, () -> { }, () -> good, () -> 1, x -> { },
                () -> { finishes[0]++; throw new IllegalStateException("jobFinished"); });
        a.eq("finish que lanza: se llama UNA sola vez", 1, finishes[0]);
        int[] n = {0};
        Runnable once = WidgetUpdateJob.once(() -> n[0]++);
        once.run(); once.run(); once.run();
        a.eq("once: una vez", 1, n[0]);

        // Sin hilo no hay trabajo: false y sin avisar (el sistema lo da por terminado).
        log.clear();
        r = WidgetUpdateJob.runJob(x -> { throw new java.util.concurrent.RejectedExecutionException(); },
                () -> 2, () -> { }, () -> good, () -> 1, x -> { }, () -> log.add("finish"));
        a.isTrue("sin hilo: false", !r);
        a.eq("sin hilo: no avisa", Collections.emptyList(), log);

        // Asincrono de verdad: vuelve true ya, y el aviso llega despues, desde otro hilo.
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(1);
        r = WidgetUpdateJob.runJob(x -> new Thread(x).start(), () -> 2, () -> { },
                () -> { try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } return good; },
                () -> 1, x -> { }, fin::countDown);
        a.isTrue("asincrono: true antes de terminar", r && fin.getCount() == 1);
        go.countDown();
        try { a.isTrue("asincrono: acaba avisando", fin.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
    }

    /** Session.logout llama a cancelar el job y a pintar "sin sesion", en ese orden y DESPUES de borrar. */
    private static void logoutHooks(Assert a, Context ctx) {
        final String file = "session-hooks-test.bin", alias = "cuw-session-hooks-test", prefs = "cuw-hooks-test";
        SessionStore st = new SessionStore(ctx, file, alias);
        st.clear();
        try {
            st.save("sessionKey=falsa");
        } catch (Exception e) {
            a.fail("logoutHooks: no se pudo preparar la sesion");
            return;
        }
        SampleStore samples = new SampleStore(new java.io.File(ctx.getCacheDir(), "hooks-samples-" + System.nanoTime()));
        List<String> log = new ArrayList<>();
        List<Snapshot> pushed = new ArrayList<>();
        boolean ok = Session.logout(ctx, st, samples, new SnapshotStore(ctx, prefs), prefs, false,
                () -> log.add("cancel:sesion=" + st.hasSession()),
                x -> { log.add("push"); pushed.add(x); }, new java.util.concurrent.atomic.AtomicLong());
        a.isTrue("logout con ganchos: true", ok);
        a.eq("cancela el job y luego pinta, ya con la sesion borrada",
                Arrays.asList("cancel:sesion=false", "push"), log);
        a.eq("pinta exactamente un snapshot", 1, pushed.size());
        if (pushed.size() == 1) {
            a.eq("y es NO_SESSION", Snapshot.Problem.NO_SESSION, pushed.get(0).problem);
            a.isTrue("sin numeros", !pushed.get(0).hasData());
        }
        // Un gancho que lanza no impide el resto, y devuelve false.
        st.clear();
        log.clear();
        ok = Session.logout(ctx, st, samples, new SnapshotStore(ctx, prefs), prefs, false,
                () -> { throw new IllegalStateException("x"); }, x -> log.add("push"), new java.util.concurrent.atomic.AtomicLong());
        a.isTrue("cancel que lanza: logout false", !ok);
        a.eq("cancel que lanza: aun asi pinta", Arrays.asList("push"), log);
        // Sin ganchos (realDevice=false de siempre): ni cancela ni pinta.
        log.clear();
        a.isTrue("sin ganchos: true", Session.logout(ctx, st, samples, new SnapshotStore(ctx, prefs), prefs, false, null, null,
                new java.util.concurrent.atomic.AtomicLong()));
        st.clear();
    }

    // ---- runNow no bloquea --------------------------------------------------------------

    private static void runNowDoesNotBlock(Assert a) {
        WidgetUpdateJob.NowGate g = new WidgetUpdateJob.NowGate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger pushed = new AtomicInteger();
        java.util.function.Supplier<Snapshot> slow = () -> {
            refreshes.incrementAndGet();
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return Snapshot.of(Snapshot.Problem.OFFLINE);
        };
        long t0 = System.nanoTime();
        WidgetUpdateJob.runNowWith(g, slow, () -> 1, s -> pushed.incrementAndGet());
        // Cuatro toques mas mientras el primero sigue consultando.
        for (int i = 0; i < 4; i++) WidgetUpdateJob.runNowWith(g, slow, () -> 1, s -> pushed.incrementAndGet());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        a.isTrue("runNow vuelve YA aunque el refresco este colgado (" + ms + " ms)", ms < 1000);
        try {
            a.isTrue("el refresco arranco en otro hilo", started.await(5, TimeUnit.SECONDS));
            a.eq("mientras consulta no se pinto nada", 0, pushed.get());
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pushed.get() < 2 && System.nanoTime() < end) Thread.sleep(20);
            Thread.sleep(200);
        } catch (InterruptedException e) {
            a.fail("runNow interrumpida");
        } finally {
            release.countDown();
        }
        a.eq("cinco toques = un refresco en curso + UNO en cola", 2, refreshes.get());
        a.eq("y se pintaron los dos", 2, pushed.get());
    }

    // ---- Ajustes y organizaciones -------------------------------------------------------

    private static void orgs(Assert a, Context ctx) {
        ctx.deleteSharedPreferences(TEST_PREFS);
        SnapshotStore meta = new SnapshotStore(ctx, TEST_PREFS);
        try {
            a.eq("Ajustes: sin lista recordada, vacia", 0, SettingsActivity.knownOrgs(meta).size());
            Instant at = Instant.parse("2026-10-09T12:00:00Z");
            List<UsageClient.Org> two = Arrays.asList(new UsageClient.Org("uuid-uno", "Uno"),
                    new UsageClient.Org("uuid-dos", null));

            // Cierre durante el refresco: no se guarda.
            a.isTrue("orgs: epoca cambiada antes, no guarda",
                    !WidgetUpdateJob.rememberIfCurrent(meta, two, 3, () -> 4, at));
            a.eq("orgs: y no quedo nada", 0, meta.knownOrgs().size());

            // Cierre entre la comprobacion y la escritura: se deshace.
            AtomicInteger calls = new AtomicInteger();
            a.isTrue("orgs: cierre a mitad, devuelve false",
                    !WidgetUpdateJob.rememberIfCurrent(meta, two, 3,
                            () -> calls.incrementAndGet() == 1 ? 3 : 4, at));
            a.eq("orgs: cierre a mitad, se deshace", 0, meta.knownOrgs().size());

            a.isTrue("orgs: sin cierre, guarda", WidgetUpdateJob.rememberIfCurrent(meta, two, 3, () -> 3, at));
            // Lo que Ajustes pinta sale de ahi: el callejon CHOOSE_ORG.
            List<UsageClient.Org> shown = SettingsActivity.knownOrgs(meta);
            a.eq("Ajustes: ve las dos organizaciones", 2, shown.size());
            if (shown.size() == 2) {
                a.eq("Ajustes: la primera", "uuid-uno", shown.get(0).uuid);
                a.eq("Ajustes: su nombre", "Uno", shown.get(0).name);
                a.eq("Ajustes: la segunda sin nombre", null, shown.get(1).name);
            }
        } finally {
            meta.clear();
            ctx.deleteSharedPreferences(TEST_PREFS);
        }
        // Ajustes lee por la misma via que escribe el refrescador, no por una paralela.
        a.eq("Ajustes y refrescador comparten preferencias", SettingsActivity.PREFS,
                Session.snapshotFor(ctx).prefsName());
    }

    /** I4: un widget que falla no deja sin pintar a los demas, y el fallo SUBE. */
    private static void paintEach(Assert a) {
        List<Integer> seen = new ArrayList<>();
        boolean all = WidgetUpdateJob.paintEach(new int[] {1, 2, 3}, id -> { seen.add(id); return id != 2; });
        a.isTrue("paintEach: devuelve false si alguno falla", !all);
        a.eq("paintEach: intento los tres", Arrays.asList(1, 2, 3), seen);
        a.isTrue("paintEach: todos bien -> true", WidgetUpdateJob.paintEach(new int[] {1, 2}, id -> true));
        a.isTrue("paintEach: sin widgets -> true", WidgetUpdateJob.paintEach(new int[0], id -> false));
    }

    /** M3: un Error (p. ej. OutOfMemoryError) del refresco no atasca la puerta ni deja el aviso sin dar. */
    private static void errorsInThreads(Assert a) {
        WidgetUpdateJob.NowGate g = new WidgetUpdateJob.NowGate();
        CountDownLatch ran = new CountDownLatch(1);
        WidgetUpdateJob.runNowWith(g, () -> { ran.countDown(); throw new OutOfMemoryError("prueba"); },
                () -> 1, x -> { });
        boolean freed = false;
        try {
            a.isTrue("el refresco con Error llego a correr", ran.await(5, TimeUnit.SECONDS));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!freed && System.nanoTime() < end) {
                if (g.claim()) freed = true; else Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            a.fail("errorsInThreads interrumpida");
        }
        a.isTrue("tras un Error la puerta NO queda atascada", freed);

        int[] finishes = {0};
        boolean started = false;
        boolean threw = false;
        try {
            started = WidgetUpdateJob.runJob(Runnable::run, () -> 2, () -> { },
                    () -> { throw new OutOfMemoryError("prueba"); }, () -> 1, x -> { },
                    () -> finishes[0]++);
        } catch (Throwable t) {
            threw = true;
        }
        a.isTrue("runJob con Error: no se escapa", !threw);
        a.isTrue("runJob con Error: el trabajo arranco (true, el aviso viene del hilo)", started);
        a.eq("runJob con Error: se avisa exactamente una vez", 1, finishes[0]);
    }

    // ---- el manifiesto ------------------------------------------------------------------

    private static void manifest(Assert a, Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_PERMISSIONS));
            a.isTrue("RECEIVE_BOOT_COMPLETED declarado (Review Focus 4)",
                    pi.requestedPermissions != null && Arrays.asList(pi.requestedPermissions)
                            .contains("android.permission.RECEIVE_BOOT_COMPLETED"));

            a.isTrue("ACCESS_NETWORK_STATE declarado (sin el, un job con restriccion de red lanza)",
                    pi.requestedPermissions != null && Arrays.asList(pi.requestedPermissions)
                            .contains("android.permission.ACCESS_NETWORK_STATE"));

            ServiceInfo si = pm.getServiceInfo(new ComponentName(pkg, WidgetUpdateJob.class.getName()),
                    PackageManager.ComponentInfoFlags.of(0));
            a.eq("el servicio exige BIND_JOB_SERVICE", "android.permission.BIND_JOB_SERVICE", si.permission);
            a.isTrue("el servicio no se exporta", !si.exported);
            a.isTrue("el servicio esta habilitado", si.enabled);
        } catch (PackageManager.NameNotFoundException e) {
            a.fail("manifiesto: no se encontro el paquete o el servicio: " + e.getMessage());
        }
        try {
            android.content.pm.ActivityInfo ai = pm.getActivityInfo(
                    new ComponentName(pkg, SettingsActivity.class.getName()),
                    PackageManager.ComponentInfoFlags.of(0));
            int need = android.content.pm.ActivityInfo.CONFIG_ORIENTATION
                    | android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
                    | android.content.pm.ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
            a.eq("Ajustes maneja la rotacion (no relanza onCreate ni repite /organizations)",
                    need, ai.configChanges & need);
        } catch (PackageManager.NameNotFoundException e) {
            a.fail("manifiesto: no se encontro SettingsActivity");
        }
        for (String action : new String[] {Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
            List<ResolveInfo> rs = pm.queryBroadcastReceivers(new Intent(action).setPackage(pkg),
                    PackageManager.ResolveInfoFlags.of(0));
            boolean found = false;
            for (ResolveInfo r : rs) {
                if (BootReceiver.class.getName().equals(r.activityInfo.name)) {
                    found = r.activityInfo.enabled && r.activityInfo.exported;
                }
            }
            a.isTrue("BootReceiver recibe " + action + " (declarado, habilitado, exportado)", found);
        }
    }

    // ---- ruling 6: tamano del 4x2 y enums ------------------------------------------------

    private static void sizes(Assert a, Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        boolean found = false;
        for (AppWidgetProviderInfo p : awm.getInstalledProvidersForPackage(ctx.getPackageName(), null)) {
            if (!Widget4x2Provider.class.getName().equals(p.provider.getClassName())) continue;
            found = true;
            a.isTrue("4x2: minHeight al menos 170dp (" + p.minHeight / density + ")",
                    Math.round(p.minHeight / density) >= 170);
            a.isTrue("4x2: minResizeHeight al menos 170dp (" + p.minResizeHeight / density + ")",
                    Math.round(p.minResizeHeight / density) >= 170);
        }
        a.isTrue("el proveedor 4x2 esta registrado", found);
    }

    private static void enums(Assert a) {
        // Si el nucleo anade un valor, estas dos fallan y obligan a mirar el renderizador.
        a.eq("TodayState tiene 3 estados (WidgetRenderer.todayText los cubre)", 3, TodayState.values().length);
        a.eq("Color tiene 4 colores (hay una barra por cada uno)", 4, Color.values().length);
    }
}
