package com.claulimitswidgets.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.claudewidgets.core.Assert;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entrada obligatoria de F5: los siete puntos de deuda que dejo F4. Todo con entradas SINTETICAS y
 * almacenes de nombre propio (N2): nada toca la sesion, las muestras, el job ni los widgets del
 * dueno, ni el unificador de refrescos ni la epoca globales.
 */
public final class F5DebtTest {

    private static final String ORG1 = "11111111-1111-4111-8111-111111111111";
    private static final String ORG2 = "22222222-2222-4222-8222-222222222222";
    private static final String COOKIES = "sessionKey=falsa; lastActiveOrg=" + ORG1;
    private static final String NEW_COOKIES = "sessionKey=nueva; lastActiveOrg=" + ORG1;
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private F5DebtTest() {}

    public static void run(Assert a, Context ctx) {
        providerUpdate(a);
        coalescing(a, ctx);
        cancelHook(a);
        clientCancel(a);
        stopJob(a);
        noJobWithoutSession(a);
        bootOffMain(a);
        guardedLogout(a, ctx);
        staleAuthWait(a, ctx);
        minors(a, ctx);
        keyLostMarker(a, ctx);
        r1Fixes(a);
    }

    // ================= 1. ProviderUpdate =================

    private static final class Probe {
        final List<String> log = new ArrayList<>();
        final List<Snapshot> painted = new ArrayList<>();
        final AtomicLong epoch = new AtomicLong(7);
        Snapshot last = Snapshot.of(Snapshot.Problem.OFFLINE);
        boolean noSessionFile;
        Runnable inLast = () -> { };
        boolean paintThrows;
        java.util.concurrent.Executor spawn = Runnable::run;

        void run() {
            ProviderUpdate.update(spawn, () -> { inLast.run(); return last; }, epoch::get,
                    s -> { if (paintThrows) throw new IllegalStateException("x"); painted.add(s); },
                    () -> noSessionFile, () -> log.add("schedule"), () -> log.add("cancel"),
                    () -> log.add("runNow"), () -> log.add("finish"));
        }
    }

    private static void providerUpdate(Assert a) {
        Probe p = new Probe();
        p.last = Snapshot.of(Snapshot.Problem.OFFLINE);
        p.run();
        a.eq("proveedor con sesion: programa y avisa una vez", Arrays.asList("schedule", "finish"), p.log);
        a.eq("proveedor con sesion: pinta lo leido", 1, p.painted.size());
        a.isTrue("proveedor con sesion: lo que pinta es lo que leyo", p.painted.size() > 0 && p.painted.get(0) == p.last);

        p = new Probe();
        p.last = Snapshot.of(Snapshot.Problem.LOADING);
        p.run();
        a.eq("primera vez (LOADING): programa, pide el primer refresco y avisa",
                Arrays.asList("schedule", "runNow", "finish"), p.log);

        // Punto 3: sin NINGUN archivo de sesion no se programa; se cancela.
        p = new Probe();
        p.last = Snapshot.of(Snapshot.Problem.NO_SESSION);
        p.noSessionFile = true;
        p.run();
        a.eq("sin sesion: cancela el job, NO lo programa", Arrays.asList("cancel", "finish"), p.log);
        a.eq("sin sesion: igual pinta 'sin sesion'", 1, p.painted.size());

        // Control: NO_SESSION porque hasSession() no pudo leer, con el archivo ahi (llave perdida,
        // sin descriptores): no es 'sin sesion', y apagar el job seria la via que borra por un hipo.
        p = new Probe();
        p.last = Snapshot.of(Snapshot.Problem.NO_SESSION);
        p.noSessionFile = false;
        p.run();
        a.eq("NO_SESSION con archivo presente: sigue programando", Arrays.asList("schedule", "finish"), p.log);

        // Un cierre de sesion durante la lectura: no se pinta encima de lo que pinto el logout.
        p = new Probe();
        Probe q = p;
        p.inLast = () -> q.epoch.incrementAndGet();
        p.last = Snapshot.of(Snapshot.Problem.OFFLINE);
        p.run();
        a.eq("epoca cambiada durante la lectura: no pinta", 0, p.painted.size());
        a.isTrue("epoca cambiada: avisa igual", p.log.contains("finish"));

        // Un fallo de lectura pinta el aviso y suelta el goAsync.
        p = new Probe();
        Probe q2 = p;
        p.inLast = () -> { throw new IllegalStateException("x"); };
        p.run();
        a.eq("lectura que lanza: pinta OFFLINE", 1, p.painted.size());
        if (p.painted.size() > 0) a.eq("lectura que lanza: es OFFLINE", Snapshot.Problem.OFFLINE, p.painted.get(0).problem);
        a.eq("lectura que lanza: avisa una vez y no programa", Arrays.asList("finish"), p.log);

        // Hasta el pintado de emergencia puede fallar: el goAsync se suelta igual.
        p = new Probe();
        p.paintThrows = true;
        p.run();
        a.eq("pintar que lanza: avisa exactamente una vez", 1, Collections.frequency(p.log, "finish"));

        // Sin hilo no hay trabajo, pero el PendingResult no queda huerfano.
        for (Throwable t : new Throwable[] {new java.util.concurrent.RejectedExecutionException(),
                new OutOfMemoryError("prueba")}) {
            p = new Probe();
            p.spawn = r -> { throw sneaky(t); };
            p.run();
            a.eq("sin hilo (" + t.getClass().getSimpleName() + "): avisa una vez", Arrays.asList("finish"), p.log);
        }

        // Asincrono de verdad: avisa desde otro hilo, despues de pintar.
        p = new Probe();
        CountDownLatch fin = new CountDownLatch(1);
        Probe q3 = p;
        ProviderUpdate.update(r -> new Thread(r).start(), () -> q3.last, q3.epoch::get,
                s -> q3.painted.add(s), () -> false, () -> { }, () -> { }, () -> { }, fin::countDown);
        try { a.isTrue("asincrono: acaba avisando", fin.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.eq("asincrono: pinto antes de avisar", 1, p.painted.size());

        a.isTrue("4x1 es compacto", new Widget4x1Provider().compact());
        a.isTrue("4x2 no es compacto", !new Widget4x2Provider().compact());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    // ================= 2. Refrescos unidos =================

    private static UsageRefresher withCo(UsageRefresherTest.Rig r, Coalescer co, Cancel cancel) {
        return new UsageRefresher(r.session, r.session::load, r.samples, r.meta, r.prefs,
                c -> r.fake, () -> r.now[0], r.epoch, co, cancel);
    }

    private static UsageRefresherTest.Rig rig(Context ctx) {
        UsageRefresherTest.Rig r = new UsageRefresherTest.Rig(ctx, true, COOKIES);
        r.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"));
        r.fake.usage.put(ORG1, UsageRefresherTest.model(30, 40));
        return r;
    }

    private static void coalescing(Assert a, Context ctx) {
        // a) dos refrescos seguidos (4202 y luego 4201, o un toque): UNA consulta.
        UsageRefresherTest.Rig r = rig(ctx);
        try {
            long[] ms = {1000};
            Coalescer co = new Coalescer(() -> ms[0]);
            Snapshot s1 = withCo(r, co, Cancel.NONE).refresh();
            a.isTrue("preparacion: el primer refresco consulta", r.fake.usageCalls.size() == 1 && s1.problem == null);
            ms[0] += 1000;
            Snapshot s2 = withCo(r, co, Cancel.NONE).refresh();
            a.eq("dos refrescos seguidos: UNA sola consulta a claude.ai", 1, r.fake.usageCalls.size());
            a.isTrue("el segundo recibe el resultado del primero", s2 == s1);

            // b) pasada la ventana, se consulta de nuevo (el toque de las 3 de la tarde no es mudo).
            ms[0] += Coalescer.WINDOW_MS;
            withCo(r, co, Cancel.NONE).refresh();
            a.eq("pasada la ventana: consulta de nuevo", 2, r.fake.usageCalls.size());

            // c) login / eleccion de organizacion entre medias: NO se traga el refresco.
            ms[0] += 1000;
            co.invalidate();
            withCo(r, co, Cancel.NONE).refresh();
            a.eq("tras invalidar (login, organizacion): consulta de nuevo", 3, r.fake.usageCalls.size());
            ms[0] += 1000;
            withCo(r, co, Cancel.NONE).refresh();
            a.eq("y el siguiente ya se une a ese", 3, r.fake.usageCalls.size());

            // d) cierre de sesion entre medias (la epoca cambia): nada se reutiliza.
            r.epoch.incrementAndGet();
            withCo(r, co, Cancel.NONE).refresh();
            a.eq("tras un cierre de sesion: consulta de nuevo", 4, r.fake.usageCalls.size());

            // Control: sin unificador (las pruebas de siempre) cada refresco consulta.
            r.refresher.refresh();
            r.refresher.refresh();
            a.eq("sin unificador: cada refresco consulta", 6, r.fake.usageCalls.size());
        } finally { r.close(); }

        // e) concurrencia: uno en vuelo y otro que llega DESPUES de un login. El segundo NO puede
        //    contentarse con el resultado del primero (que consulto con la cookie vieja).
        r = rig(ctx);
        try {
            Coalescer co = new Coalescer(() -> 5000L);
            CountDownLatch inFlight = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            r.fake.onUsage = () -> {
                inFlight.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            };
            UsageRefresherTest.Rig fr = r;
            Thread t1 = new Thread(() -> withCo(fr, co, Cancel.NONE).refresh());
            t1.start();
            boolean started = inFlight.await(5, TimeUnit.SECONDS);
            a.isTrue("preparacion: el primero esta en vuelo", started);
            // Llega un segundo refresco SIN login: se une al primero.
            Thread t2 = new Thread(() -> withCo(fr, co, Cancel.NONE).refresh());
            t2.start();
            // Llega un tercero tras un login.
            co.invalidate();
            Thread t3 = new Thread(() -> withCo(fr, co, Cancel.NONE).refresh());
            t3.start();
            Thread.sleep(200);   // que t2 y t3 lleguen al candado
            r.fake.onUsage = null;
            release.countDown();
            t1.join(8000); t2.join(8000); t3.join(8000);
            // t1 consulta; t2 llego antes del login y lo cubre el que empiece despues (t3 o el
            // propio t2 si gana el candado); como mucho 2 consultas, y al menos una posterior al login.
            int calls = r.fake.usageCalls.size();
            a.isTrue("en vuelo + login: habia que consultar de nuevo tras el login (" + calls + ")", calls >= 2);
            a.isTrue("en vuelo + login: aun asi se unen (no 3)", calls <= 2);
        } catch (InterruptedException e) {
            a.fail("coalescing interrumpida");
        } finally { r.close(); }

        // f) un refresco cortado no se comparte, y no cuenta para la espera.
        r = rig(ctx);
        try {
            Coalescer co = new Coalescer(() -> 9000L);
            Cancel c = new Cancel();
            r.fake.onUsage = c::cancel;
            r.fake.usage.put(ORG1, new IOException("abortada"));
            Snapshot cut = withCo(r, co, c).refresh();
            a.isTrue("cortado: devuelve el dato de siempre sin lanzar", cut != null);
            a.isTrue("cortado: NO suma un intento de espera", !r.prefs.contains("backoff_attempt"));
            a.isTrue("cortado: NO pone espera", !r.prefs.contains("backoff_next_allowed_at"));
            r.fake.onUsage = null;
            r.fake.usage.put(ORG1, UsageRefresherTest.model(31, 41));
            Snapshot again = withCo(r, co, Cancel.NONE).refresh();
            a.eq("cortado: lo siguiente consulta (nada reutilizado)", 2, r.fake.usageCalls.size());
            a.isTrue("cortado: lo siguiente trae datos", again.hasData());
            if (again.hasData()) a.eq("cortado: y trae el dato nuevo", 41.0, again.model.weekly.percent);
            // Control: el MISMO fallo sin corte SI cuenta como intento (la prueba no pasa en vacio).
            UsageRefresherTest.Rig r2 = rig(ctx);
            try {
                r2.fake.usage.put(ORG1, new IOException("sin red"));
                withCo(r2, new Coalescer(() -> 1L), Cancel.NONE).refresh();
                a.isTrue("control: sin corte, el fallo SI suma intento", r2.prefs.contains("backoff_attempt"));
            } finally { r2.close(); }
        } finally { r.close(); }

        // El invalidar de produccion es el de clearBackoff(prefs): login y eleccion de organizacion.
        // Con las preferencias de una plataforma propia: las del dueno (cuw) no se tocan (N2).
        r = rig(ctx);
        try {
            r.prefs.edit().putInt("backoff_attempt", 3).putLong("backoff_next_allowed_at", 99L)
                    .putString("backoff_last_problem", "OFFLINE").putString("manual_org", "x").commit();
            long v = Coalescer.SHARED.version();
            UsageRefresher.clearBackoff(r.prefs);
            a.isTrue("clearBackoff(prefs) invalida el unificador de produccion", Coalescer.SHARED.version() > v);
            a.isTrue("clearBackoff(prefs): quita el intento", !r.prefs.contains("backoff_attempt"));
            a.isTrue("clearBackoff(prefs): quita la espera", !r.prefs.contains("backoff_next_allowed_at"));
            a.isTrue("clearBackoff(prefs): quita el problema", !r.prefs.contains("backoff_last_problem"));
            a.eq("clearBackoff(prefs): no toca lo demas", "x", r.prefs.getString("manual_org", null));
        } finally { r.close(); }
    }

    // ================= 2b. Cancel / UsageClient / onStopJob =================

    private static void cancelHook(Assert a) {
        Cancel c = new Cancel();
        int[] ran = {0};
        c.setHook(() -> ran[0]++);
        a.isTrue("sin cancelar: no corre el gancho", ran[0] == 0 && !c.isCancelled());
        c.cancel();
        c.cancel();
        a.isTrue("cancelar: marca y corre el gancho", c.isCancelled() && ran[0] == 2);
        // El corte llego ANTES de registrar la conexion: no se pierde.
        Cancel early = new Cancel();
        early.cancel();
        int[] late = {0};
        early.setHook(() -> late[0]++);
        a.eq("corte previo: el gancho nuevo corre enseguida", 1, late[0]);
        early.setHook(null);
        // Un gancho que lanza no tumba a quien corta.
        Cancel bad = new Cancel();
        bad.setHook(() -> { throw new IllegalStateException("x"); });
        boolean threw = false;
        try { bad.cancel(); } catch (RuntimeException e) { threw = true; }
        a.isTrue("gancho que lanza: cancel no lanza", !threw && bad.isCancelled());
        Cancel.NONE.setHook(() -> { throw new IllegalStateException("no deberia correr"); });
        Cancel.NONE.cancel();
        a.isTrue("NONE nunca se cancela ni guarda ganchos", !Cancel.NONE.isCancelled());
    }

    /** Una conexion falsa: bloquea en la respuesta hasta que la aborten. NO hay red. */
    private static final class FakeConn extends HttpURLConnection {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        volatile boolean disconnected;

        FakeConn() { super(null); }
        @Override public void disconnect() { disconnected = true; released.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() throws IOException {
            waiting.countDown();
            try { released.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            if (disconnected) throw new IOException("Socket closed");
            return 200;
        }
    }

    private static void clientCancel(Assert a) {
        FakeConn conn = new FakeConn();
        Cancel cancel = new Cancel();
        UsageClient client = new UsageClient("sessionKey=falsa", "ua", cancel, url -> conn);
        Throwable[] got = new Throwable[1];
        Thread t = new Thread(() -> {
            try { client.organizations(); } catch (Throwable e) { got[0] = e; }
        });
        t.start();
        try {
            a.isTrue("preparacion: la peticion esta esperando la red", conn.waiting.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            cancel.cancel();
            t.join(5000);
            a.isTrue("cortar: el hilo vuelve ya, sin esperar los timeouts", !t.isAlive());
            a.isTrue("cortar: tarda menos de 3 s", System.nanoTime() - t0 < 3_000_000_000L);
        } catch (InterruptedException e) {
            a.fail("clientCancel interrumpida");
        }
        a.isTrue("cortar: se aborto la conexion (disconnect)", conn.disconnected);
        a.isTrue("cortar: el fallo es un IOException", got[0] instanceof IOException);

        // Cortado antes de empezar: ni se abre la conexion.
        int[] opened = {0};
        Cancel pre = new Cancel();
        pre.cancel();
        Throwable pg = null;
        try {
            new UsageClient("sessionKey=falsa", "ua", pre, url -> { opened[0]++; return new FakeConn(); })
                    .organizations();
        } catch (Throwable e) { pg = e; }
        a.isTrue("cortado de antemano: IOException", pg instanceof IOException);
        a.eq("cortado de antemano: no abre ninguna conexion", 0, opened[0]);
    }

    private static void stopJob(Assert a) {
        Map<Integer, Cancel> running = new HashMap<>();
        // Un trabajo en marcha que espera a que lo corten.
        CountDownLatch inRefresh = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(1);
        Cancel[] seen = new Cancel[1];
        List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean started = WidgetUpdateJob.startJob(running, 4201, r -> new Thread(r).start(), () -> 2,
                () -> false, () -> log.add("cancelJob"),
                c -> {
                    seen[0] = c;
                    inRefresh.countDown();
                    long until = System.nanoTime() + 5_000_000_000L;
                    while (!c.isCancelled() && System.nanoTime() < until) {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                    }
                    return Snapshot.of(Snapshot.Problem.OFFLINE);
                }, () -> 1, s -> log.add("push"), () -> { log.add("finish"); end.countDown(); });
        try {
            a.isTrue("trabajo: arranca", started);
            a.isTrue("trabajo: llego al refresco", inRefresh.await(5, TimeUnit.SECONDS));
            a.isTrue("trabajo: queda registrado por id", running.containsKey(4201));
            WidgetUpdateJob.stopJob(running, 4202);
            a.isTrue("parar OTRO id no corta este", !seen[0].isCancelled());
            WidgetUpdateJob.stopJob(running, 4201);
            long end0 = System.nanoTime();
            Thread.sleep(300);   // deja que el hilo vea el corte y acabe
            a.isTrue("onStopJob: el trabajador se entera", seen[0].isCancelled());
            a.isTrue("onStopJob: queda desregistrado", !running.containsKey(4201));
            a.isTrue("trabajo cortado: NO pinta", !log.contains("push"));
            a.isTrue("trabajo cortado: NO llama a jobFinished (el sistema ya lo dio por parado)",
                    !log.contains("finish") && end.getCount() == 1);
            a.isTrue("trabajo cortado: tampoco toca el job", !log.contains("cancelJob"));
        } catch (InterruptedException e) {
            a.fail("stopJob interrumpida");
        }
        WidgetUpdateJob.stopJob(running, 99);   // un id que no existe no lanza

        // Un trabajo normal: pinta, avisa una vez y se desregistra.
        List<String> log2 = new ArrayList<>();
        Map<Integer, Cancel> r2 = new HashMap<>();
        boolean ok = WidgetUpdateJob.startJob(r2, 4202, Runnable::run, () -> 2, () -> false, () -> { },
                c -> Snapshot.of(Snapshot.Problem.OFFLINE), () -> 1, s -> log2.add("push"),
                () -> log2.add("finish"));
        a.isTrue("normal: arranca", ok);
        a.eq("normal: pinta y avisa una vez", Arrays.asList("push", "finish"), log2);
        a.isTrue("normal: desregistrado", r2.isEmpty());

        // Sin hilo: false y nada queda registrado.
        Map<Integer, Cancel> r3 = new HashMap<>();
        boolean no = WidgetUpdateJob.startJob(r3, 4201, x -> { throw new java.util.concurrent.RejectedExecutionException(); },
                () -> 2, () -> false, () -> { }, c -> null, () -> 1, s -> { }, () -> { });
        a.isTrue("sin hilo: false", !no);
        a.isTrue("sin hilo: nada registrado", r3.isEmpty());

        // El servicio real: onStopJob corta el trabajo registrado con ese id (un id de prueba,
        // que no es ninguno de los dos de produccion).
        Cancel mine = new Cancel();
        WidgetUpdateJob.RUNNING.put(4299, mine);
        try {
            boolean retry = new WidgetUpdateJob().onStop(4299);
            a.isTrue("onStop: corta el trabajo registrado", mine.isCancelled());
            a.isTrue("onStop: sigue sin pedir reintento", !retry);
            a.isTrue("onStop: lo desregistra", !WidgetUpdateJob.RUNNING.containsKey(4299));
        } finally {
            WidgetUpdateJob.RUNNING.remove(4299);
        }

        // Y el servicio real tolera un onStopJob sin parametros.
        a.isTrue("onStopJob(null) no lanza y no reintenta", !new WidgetUpdateJob().onStopJob(null));
    }

    // ================= 3. Nada de job sin sesion =================

    private static void noJobWithoutSession(Assert a) {
        java.util.concurrent.Executor sync = Runnable::run;
        List<String> log = new ArrayList<>();
        Snapshot good = Snapshot.of(Snapshot.Problem.OFFLINE);
        boolean r = WidgetUpdateJob.runJob(sync, () -> 2, () -> true, () -> log.add("cancel"),
                () -> { log.add("refresh"); return good; }, () -> 1, x -> log.add("push"),
                () -> log.add("finish"));
        a.isTrue("job sin sesion: arranca (true)", r);
        a.eq("job sin sesion: se cancela, NO consulta ni pinta, y avisa", Arrays.asList("cancel", "finish"), log);
        log.clear();
        WidgetUpdateJob.runJob(sync, () -> 2, () -> false, () -> log.add("cancel"),
                () -> { log.add("refresh"); return good; }, () -> 1, x -> log.add("push"),
                () -> log.add("finish"));
        a.eq("job con sesion y widgets: consulta normal", Arrays.asList("refresh", "push", "finish"), log);

        for (String action : new String[] {android.content.Intent.ACTION_BOOT_COMPLETED,
                android.content.Intent.ACTION_MY_PACKAGE_REPLACED}) {
            List<String> calls = new ArrayList<>();
            BootReceiver.handle(action, () -> 2, () -> true, () -> calls.add("schedule"),
                    () -> calls.add("cancel"), () -> calls.add("soon"));
            a.eq(action + " sin sesion: cancela, no programa ni pide refresco",
                    Arrays.asList("cancel"), calls);
        }
    }

    private static void bootOffMain(Assert a) {
        List<String> log = new ArrayList<>();
        BootReceiver.offMain(Runnable::run, () -> log.add("work"), () -> log.add("finish"));
        a.eq("offMain: trabajo y luego aviso", Arrays.asList("work", "finish"), log);
        log.clear();
        BootReceiver.offMain(Runnable::run, () -> { log.add("work"); throw new IllegalStateException("x"); },
                () -> log.add("finish"));
        a.eq("offMain: trabajo que lanza: avisa igual, una vez", Arrays.asList("work", "finish"), log);
        log.clear();
        BootReceiver.offMain(Runnable::run, () -> { throw new OutOfMemoryError("x"); }, () -> log.add("finish"));
        a.eq("offMain: trabajo con Error: avisa igual", Arrays.asList("finish"), log);
        log.clear();
        BootReceiver.offMain(r -> { throw new java.util.concurrent.RejectedExecutionException(); },
                () -> log.add("work"), () -> log.add("finish"));
        a.eq("offMain: sin hilo: no trabaja pero avisa", Arrays.asList("finish"), log);
        // Fuera del hilo que llama.
        Thread caller = Thread.currentThread();
        Thread[] where = new Thread[1];
        CountDownLatch fin = new CountDownLatch(1);
        BootReceiver.offMain(r -> new Thread(r).start(), () -> where[0] = Thread.currentThread(), fin::countDown);
        try { a.isTrue("offMain: avisa", fin.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.isTrue("offMain: el trabajo corre en otro hilo", where[0] != null && where[0] != caller);
    }

    // ================= 4. Doble toque en cerrar sesion =================

    private static void guardedLogout(Assert a, Context ctx) {
        View[] bs = {new View(ctx), new View(ctx), new View(ctx), new View(ctx)};
        AtomicBoolean busy = new AtomicBoolean();
        List<java.util.function.Consumer<Boolean>> pending = new ArrayList<>();
        List<Boolean> after = new ArrayList<>();
        boolean first = LoginActivity.guardedLogout(busy, bs, pending::add, after::add);
        a.isTrue("1er toque: lanza el cierre", first && pending.size() == 1);
        boolean allOff = true;
        for (View b : bs) allOff &= !b.isEnabled();
        a.isTrue("durante el cierre: los 4 botones inactivos", allOff);
        boolean second = LoginActivity.guardedLogout(busy, bs, pending::add, after::add);
        a.isTrue("2o toque (doble toque): NO lanza otro cierre", !second && pending.size() == 1);
        if (pending.size() > 0) pending.get(0).accept(true);
        boolean allOn = true;
        for (View b : bs) allOn &= b.isEnabled();
        a.isTrue("al volver el callback: los 4 botones activos", allOn);
        a.eq("al volver: avisa con el resultado", Arrays.asList(true), after);
        a.isTrue("y se puede cerrar de nuevo", LoginActivity.guardedLogout(busy, bs, pending::add, after::add)
                && pending.size() == 2);
        if (pending.size() > 1) pending.get(1).accept(false);
        a.eq("un cierre fallido tambien reactiva y avisa", Arrays.asList(true, false), after);

        // Si el cierre ni arranca, los botones no quedan muertos.
        boolean launched = LoginActivity.guardedLogout(busy, bs, cb -> { throw new IllegalStateException("x"); },
                after::add);
        boolean on = true;
        for (View b : bs) on &= b.isEnabled();
        a.isTrue("cierre que no arranca: botones activos de nuevo", launched && on);
        a.isTrue("cierre que no arranca: libre para reintentar", !busy.get());
    }

    // ================= 5. El 401 de la cookie vieja =================

    private static void staleAuthWait(Assert a, Context ctx) {
        // Control: sin login entre medias, el 401 SI pone la espera larga.
        final UsageRefresherTest.Rig r1 = rig(ctx);
        try {
            String[] cookie = {COOKIES};
            UsageRefresher ref = new UsageRefresher(r1.session, () -> cookie[0], r1.samples, r1.meta,
                    r1.prefs, c -> r1.fake, () -> r1.now[0], r1.epoch);
            r1.fake.usage.put(ORG1, new AuthExpiredException("401"));
            Snapshot s = ref.refresh();
            a.eq("control 401: AUTH_EXPIRED", Snapshot.Problem.AUTH_EXPIRED, s.problem);
            a.eq("control 401: espera de 6 h", T0.getEpochSecond() + UsageRefresher.AUTH_WAIT_SECONDS,
                    r1.prefs.getLong("backoff_next_allowed_at", -1));
        } finally { r1.close(); }

        // Login nuevo mientras la peticion vieja vuela: el 401 llega DESPUES de clearBackoff.
        final UsageRefresherTest.Rig r2 = rig(ctx);
        try {
            String[] cookie = {COOKIES};
            UsageRefresher ref = new UsageRefresher(r2.session, () -> cookie[0], r2.samples, r2.meta,
                    r2.prefs, c -> r2.fake, () -> r2.now[0], r2.epoch);
            r2.fake.onUsage = () -> {
                cookie[0] = NEW_COOKIES;                       // el dueno entra de nuevo...
                r2.prefs.edit().clear().commit();              // ...y el login borra la espera
            };
            r2.fake.usage.put(ORG1, new AuthExpiredException("401"));   // la cookie vieja da 401
            Snapshot s = ref.refresh();
            a.eq("401 de la cookie vieja: sigue diciendo AUTH_EXPIRED", Snapshot.Problem.AUTH_EXPIRED, s.problem);
            a.isTrue("401 de la cookie vieja: NO pisa el clearBackoff con 6 h",
                    !r2.prefs.contains("backoff_next_allowed_at"));
            a.isTrue("401 de la cookie vieja: ni anota el problema", !r2.prefs.contains("backoff_last_problem"));
        } finally { r2.close(); }

        // La carrera fina: el login llega ENTRE la comprobacion y la escritura. Se deshace.
        final UsageRefresherTest.Rig r3 = rig(ctx);
        try {
            String[] cookie = {COOKIES};
            int[] loads = {0};
            UsageRefresher ref = new UsageRefresher(r3.session, () -> {
                // 1a carga: al empezar; 2a: la comprobacion previa a escribir; 3a: la verificacion.
                loads[0]++;
                if (loads[0] == 3) cookie[0] = NEW_COOKIES;
                return cookie[0];
            }, r3.samples, r3.meta, r3.prefs, c -> r3.fake, () -> r3.now[0], r3.epoch);
            r3.fake.usage.put(ORG1, new AuthExpiredException("401"));
            ref.refresh();
            a.eq("verificacion: se leyo la cookie 3 veces (inicio, comprobacion, verificacion)", 3, loads[0]);
            a.isTrue("login tras la escritura: la espera se deshace", !r3.prefs.contains("backoff_next_allowed_at"));
        } finally { r3.close(); }

        // Keystore ocupado al comprobar: no se escribe nada (lo conservador: se reintenta en 15 min).
        final UsageRefresherTest.Rig r4 = rig(ctx);
        try {
            int[] loads = {0};
            UsageRefresher ref = new UsageRefresher(r4.session, () -> {
                if (++loads[0] > 1) throw new java.security.GeneralSecurityException("hipo");
                return COOKIES;
            }, r4.samples, r4.meta, r4.prefs, c -> r4.fake, () -> r4.now[0], r4.epoch);
            r4.fake.usage.put(ORG1, new AuthExpiredException("401"));
            ref.refresh();
            a.isTrue("cookie ilegible al comprobar: no pone la espera de 6 h",
                    !r4.prefs.contains("backoff_next_allowed_at"));
        } finally { r4.close(); }
    }

    // ================= 6. Los cuatro menores =================

    private static void minors(Assert a, Context ctx) {
        // 6a. El tope por reloj atrasado depende del problema y sale de Backoff.
        a.eq("Backoff.MAX es el tope de 30 min", 1800L, Backoff.MAX);
        UsageRefresherTest.Rig r = rig(ctx);
        try {
            a.isTrue("preparacion", r.refresher.refresh().problem == null);
            int before = r.fake.usageCalls.size();
            r.now[0] = r.now[0].plusSeconds(60);
            long t = r.now[0].getEpochSecond();
            // OFFLINE nunca espera mas de 30 min: una espera de 2 h es un reloj que retrocedio.
            r.prefs.edit().putLong("backoff_next_allowed_at", t + 2 * 3600).putString("backoff_last_problem", "OFFLINE").commit();
            r.refresher.refresh();
            a.isTrue("espera OFFLINE de 2 h (imposible): se ignora y se consulta", r.fake.usageCalls.size() > before);
            // Un 401 SI espera 6 h: 5 h es legitimo y se respeta.
            int b2 = r.fake.usageCalls.size();
            r.now[0] = r.now[0].plusSeconds(60);
            t = r.now[0].getEpochSecond();
            r.prefs.edit().putLong("backoff_next_allowed_at", t + 5 * 3600).putString("backoff_last_problem", "AUTH_EXPIRED").commit();
            Snapshot s = r.refresher.refresh();
            a.eq("espera AUTH_EXPIRED de 5 h (legitima): no se consulta", b2, r.fake.usageCalls.size());
            a.eq("espera AUTH_EXPIRED: sigue diciendo AUTH_EXPIRED", Snapshot.Problem.AUTH_EXPIRED, s.problem);
            // Pero ni un 401 espera mas de 6 h + margen.
            r.prefs.edit().putLong("backoff_next_allowed_at", t + 8 * 3600).putString("backoff_last_problem", "AUTH_EXPIRED").commit();
            r.refresher.refresh();
            a.isTrue("espera AUTH_EXPIRED de 8 h (imposible): se ignora", r.fake.usageCalls.size() > b2);
        } finally { r.close(); }

        // 6b. La marcha atras del aviso BORRA la clave, no escribe false.
        String pn = "cuw-f5-choosing-" + System.nanoTime();
        AtomicLong epoch = new AtomicLong();
        SharedPreferences real = ctx.getSharedPreferences(pn, Context.MODE_PRIVATE);
        // Un contexto cuyas preferencias avisan de un "logout" justo despues de anotar el aviso.
        Context spy = new android.content.ContextWrapper(ctx) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences base = super.getSharedPreferences(name, mode);
                return name.equals(pn) ? spyPrefs(base, epoch) : base;
            }
        };
        UsageRefresherTest.Rig r2 = new UsageRefresherTest.Rig(ctx, true, "sessionKey=falsa");
        try {
            r2.fake.orgs = Arrays.asList(new UsageClient.Org(ORG1, "Uno"), new UsageClient.Org(ORG2, "Dos"));
            r2.fake.usage.put(ORG1, UsageRefresherTest.model(30, 40));
            r2.fake.usage.put(ORG2, UsageRefresherTest.model(30, 40));
            SnapshotStore meta = new SnapshotStore(spy, pn);
            UsageRefresher ref = new UsageRefresher(r2.session, r2.samples, meta, r2.prefs,
                    c -> r2.fake, () -> r2.now[0], epoch);
            // Control: sin logout, el aviso queda anotado (si no, lo de abajo pasaria en vacio).
            Snapshot s0 = ref.refresh();
            a.eq("control: ambigua -> CHOOSE_ORG", Snapshot.Problem.CHOOSE_ORG, s0.problem);
            a.isTrue("control: el aviso quedo anotado como true", real.getBoolean("choosing_org", false));
            real.edit().clear().commit();
            epoch.set(0);
            armed[0] = true;
            ref.refresh();
            armed[0] = false;
            a.isTrue("el logout llego tras anotar: se deshace", !real.getBoolean("choosing_org", false));
            a.isTrue("y la clave no queda ni como false", !real.contains("choosing_org"));
        } finally {
            armed[0] = false;
            r2.close();
            ctx.deleteSharedPreferences(pn);
        }

        // 6c. Si el hilo del cierre ni arranca, el aviso llega igual (con false).
        CountDownLatch fin = new CountDownLatch(1);
        Boolean[] got = new Boolean[1];
        Thread[] where = new Thread[1];
        Throwable esc = null;
        try {
            Session.runAsync(() -> true, ok -> { got[0] = ok; where[0] = Thread.currentThread(); fin.countDown(); },
                    starter -> { throw new OutOfMemoryError("prueba"); });
        } catch (Throwable t) { esc = t; }
        a.isTrue("hilo que no arranca: runAsync no deja escapar el Error", esc == null);
        try { a.isTrue("hilo que no arranca: avisa", fin.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.eq("hilo que no arranca: false", Boolean.FALSE, got[0]);
        a.isTrue("hilo que no arranca: el aviso vuelve al principal",
                where[0] == android.os.Looper.getMainLooper().getThread());
        CountDownLatch fin2 = new CountDownLatch(1);
        Session.runAsync(() -> true, ok -> fin2.countDown(), starter -> { throw new IllegalStateException("x"); });
        try { a.isTrue("hilo que lanza RuntimeException: avisa", fin2.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
    }

    /** true solo mientras la prueba 6b quiere simular el logout (el resto del tiempo es transparente). */
    private static final boolean[] armed = {false};

    /** Preferencias que, al guardar `choosing_org=true`, suben la epoca: "el logout llego justo despues". */
    private static SharedPreferences spyPrefs(SharedPreferences base, AtomicLong epoch) {
        return (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                F5DebtTest.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, m, args) -> {
                    Object out = m.invoke(base, args);
                    if (m.getName().equals("edit")) {
                        SharedPreferences.Editor real = (SharedPreferences.Editor) out;
                        boolean[] flagged = {false};
                        return java.lang.reflect.Proxy.newProxyInstance(
                                F5DebtTest.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
                                (p2, m2, a2) -> {
                                    if (m2.getName().equals("putBoolean") && "choosing_org".equals(a2[0])
                                            && Boolean.TRUE.equals(a2[1])) flagged[0] = true;
                                    Object r = m2.invoke(real, a2);
                                    if ((m2.getName().equals("apply") || m2.getName().equals("commit")) && flagged[0]
                                            && armed[0]) {
                                        flagged[0] = false;
                                        epoch.incrementAndGet();
                                    }
                                    return m2.getReturnType() == SharedPreferences.Editor.class ? p2 : r;
                                });
                    }
                    return out;
                });
    }

    // ================= 7. Marcador de llave perdida =================

    private static void keyLostMarker(Assert a, Context ctx) {
        final String file = "session-f5-lost-test.bin";
        final String alias = "cuw-session-f5-lost-test";
        javax.crypto.SecretKey soft = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        SessionStore good = new SessionStore(ctx, file, alias, () -> soft);
        SessionStore dead = new SessionStore(ctx, file, alias, () -> {
            throw new android.security.keystore.KeyPermanentlyInvalidatedException();
        });
        good.clear();
        File f = new File(ctx.getFilesDir(), file);
        File marker = new File(ctx.getFilesDir(), file + ".lost");
        try {
            good.save("sessionKey=falsa");
            a.isTrue("control: con llave sana hay sesion", good.hasSession());
            a.isTrue("control: sin marcador", !marker.exists() && !good.isKeyLost());

            Throwable got = null;
            try { dead.load(); } catch (Throwable e) { got = e; }
            a.isTrue("llave perdida: load lanza KeyLostException", got instanceof SessionStore.KeyLostException);
            a.isTrue("llave perdida: deja el marcador", marker.exists());
            a.isTrue("llave perdida: hasSession YA NO miente", !dead.hasSession());
            a.isTrue("llave perdida: tampoco para otra instancia (esta en disco)", !good.hasSession());
            a.isTrue("llave perdida: isKeyLost", dead.isKeyLost());
            // La via que borra la sesion NO se reabre: el archivo sigue y el job no se apaga.
            a.isTrue("llave perdida: el archivo de sesion NO se borra", f.exists());
            a.isTrue("llave perdida: no es 'sin archivo' (el job sigue)", !dead.isAbsent());

            // La widget: AUTH_EXPIRED (toque -> login), no NO_SESSION (que apagaria el job).
            UsageRefresherTest.Rig rr = new UsageRefresherTest.Rig(ctx, false, null);
            try {
                UsageRefresher lost = new UsageRefresher(dead, () -> { throw new SessionStore.KeyLostException(); },
                        rr.samples, rr.meta, rr.prefs, c -> rr.fake, () -> rr.now[0], rr.epoch);
                Snapshot s = lost.last();
                a.eq("last() con la llave perdida: AUTH_EXPIRED, no NO_SESSION", Snapshot.Problem.AUTH_EXPIRED, s.problem);
                rr.meta.remember(UsageRefresherTest.model(30, 40), T0);
                Snapshot withData = lost.last();
                a.eq("last() con datos y llave perdida: AUTH_EXPIRED", Snapshot.Problem.AUTH_EXPIRED, withData.problem);
                a.isTrue("last() con datos y llave perdida: conserva el dato", withData.hasData());
            } finally { rr.close(); }

            // Un hipo se cura solo: en cuanto la llave sirve de nuevo, load limpia el marcador.
            a.eq("hipo: con la llave de vuelta se lee", "sessionKey=falsa", good.load());
            a.isTrue("hipo: el marcador se quita", !marker.exists());
            a.isTrue("hipo: vuelve a haber sesion", good.hasSession() && !good.isKeyLost());

            // Un guardado nuevo regenera la llave y limpia el marcador.
            try { dead.load(); } catch (Exception ignored) { }
            a.isTrue("preparacion: otra vez marcado", marker.exists());
            good.save("sessionKey=otra");
            a.isTrue("save() limpia el marcador", !marker.exists());
            a.isTrue("save(): hay sesion", good.hasSession());

            // clear() (cierre de sesion) lo quita tambien.
            try { dead.load(); } catch (Exception ignored) { }
            a.isTrue("preparacion: marcado antes de clear", marker.exists());
            a.isTrue("clear() devuelve true", good.clear());
            a.isTrue("clear() quita el marcador", !marker.exists());

            // Si el marcador NO se puede borrar (aqui, un directorio con un hijo), clear() lo dice:
            // el retorno mira tambien el marcador, no solo el archivo y el temporal.
            File child = new File(marker, "hijo");
            try {
                a.isTrue("preparacion: marcador-directorio con hijo",
                        marker.mkdir() && child.createNewFile() && marker.exists());
                a.isTrue("clear() con marcador imborrable: devuelve false", !good.clear());
            } finally {
                if (!child.delete() | !marker.delete()) a.isTrue("limpieza del marcador-directorio", !marker.exists());
            }
        } catch (Exception e) {
            a.fail("keyLostMarker: " + e.getClass().getSimpleName());
        } finally {
            good.clear();
        }

        // Criterio de salida, por el lado del CONSUMIDOR: Session.logout borra el marcador.
        SessionStore s = new SessionStore(ctx, file, alias, () -> soft);
        SessionStore d2 = new SessionStore(ctx, file, alias, () -> {
            throw new java.security.UnrecoverableKeyException("x");
        });
        String prefsName = "cuw-f5-logout-lost-" + System.nanoTime();
        File samplesDir = new File(ctx.getCacheDir(), "f5-lost-samples-" + System.nanoTime());
        try {
            s.save("sessionKey=falsa");
            try { d2.load(); } catch (Exception ignored) { }
            a.isTrue("antes del logout: hay marcador", marker.exists());
            boolean ok = Session.logout(ctx, s, new SampleStore(samplesDir), new SnapshotStore(ctx, prefsName),
                    prefsName, false, null, null, new AtomicLong());
            a.isTrue("logout devuelve true", ok);
            a.isTrue("Session.logout borra el marcador de llave perdida", !marker.exists());
            a.isTrue("y el archivo de sesion", !f.exists());
        } catch (Exception e) {
            a.fail("logout con marcador: " + e.getClass().getSimpleName());
        } finally {
            s.clear();
            ctx.deleteSharedPreferences(prefsName);
        }
    }

    // ================= Ronda r1 =================

    private static void r1Fixes(Assert a) {
        // m43: aunque `work` lance una excepcion comprobada a lo bruto, `finish` corre UNA vez.
        AtomicInteger fins = new AtomicInteger();
        Throwable escaped = null;
        try {
            BootReceiver.offMain(Runnable::run, () -> { throw F5DebtTest.<RuntimeException>sneaky(new IOException("x")); },
                    fins::incrementAndGet);
        } catch (Throwable t) { escaped = t; }
        a.eq("offMain: work con excepcion comprobada: finish exactamente una vez", 1, fins.get());
        a.isTrue("offMain: work con excepcion comprobada: la excepcion no se traga", escaped instanceof IOException);

        // Red de seguridad del periodico: solo con archivo de sesion.
        List<String> log = new ArrayList<>();
        WidgetUpdateJob.healPeriodic(() -> false, () -> log.add("schedule"));
        a.eq("healPeriodic con sesion: programa", Arrays.asList("schedule"), log);
        log.clear();
        WidgetUpdateJob.healPeriodic(() -> true, () -> log.add("schedule"));
        a.isTrue("healPeriodic sin sesion: NO programa", log.isEmpty());

        // ... y corre en el hilo del refresco, antes de la primera vuelta, no en el de quien llama.
        Thread caller = Thread.currentThread();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread[] where = new Thread[1];
        CountDownLatch done = new CountDownLatch(1);
        WidgetUpdateJob.runNowWith(new WidgetUpdateJob.NowGate(),
                () -> { where[0] = Thread.currentThread(); order.add("heal"); },
                () -> { order.add("refresh"); return Snapshot.of(Snapshot.Problem.OFFLINE); },
                () -> 1, s -> order.add("paint"), done::countDown);
        try { a.isTrue("runNowWith: termina", done.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.eq("runNowWith: la red de seguridad va primero", Arrays.asList("heal", "refresh", "paint"), order);
        a.isTrue("runNowWith: la red de seguridad NO corre en el hilo de quien llama",
                where[0] != null && where[0] != caller);

        // Si la red de seguridad lanza, el refresco se hace igual y la puerta no queda cerrada.
        List<String> order2 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done2 = new CountDownLatch(1);
        WidgetUpdateJob.NowGate gate = new WidgetUpdateJob.NowGate();
        WidgetUpdateJob.runNowWith(gate, () -> { throw new IllegalStateException("x"); },
                () -> { order2.add("refresh"); return Snapshot.of(Snapshot.Problem.OFFLINE); },
                () -> 1, s -> order2.add("paint"), done2::countDown);
        try { a.isTrue("prelude que lanza: termina", done2.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.eq("prelude que lanza: refresca y pinta igual", Arrays.asList("refresh", "paint"), order2);
        a.isTrue("prelude que lanza: la puerta queda libre", gate.claim());

        // Igual si lo que lanza es un Error: el refresco del toque no se pierde.
        List<String> order3 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done3 = new CountDownLatch(1);
        WidgetUpdateJob.runNowWith(new WidgetUpdateJob.NowGate(), () -> { throw new AssertionError("x"); },
                () -> { order3.add("refresh"); return Snapshot.of(Snapshot.Problem.OFFLINE); },
                () -> 1, s -> order3.add("paint"), done3::countDown);
        try { a.isTrue("prelude que lanza un Error: termina", done3.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { a.fail("interrumpida"); }
        a.eq("prelude que lanza un Error: refresca y pinta igual", Arrays.asList("refresh", "paint"), order3);
    }
}
