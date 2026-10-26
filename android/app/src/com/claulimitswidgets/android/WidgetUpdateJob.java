package com.claulimitswidgets.android;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Actualiza los widgets cada 15 min (el minimo que respeta el sistema) y cuando se pide.
 *
 * Nada de red ni de disco en el hilo principal: `onStartJob` corre en el principal, asi que el
 * trabajo va a un hilo aparte. Un hilo crudo que lanza se lleva el proceso por delante, y por eso
 * todo pasa por {@link #cycle}, que atrapa y siempre avisa de que termino.
 *
 * Dos trabajos comparten este servicio: el PERIODICO ({@link #JOB_ID}) y uno de UNA VEZ al
 * arrancar el telefono ({@link #JOB_ID_BOOT}), que lo programa {@link BootReceiver}.
 */
public class WidgetUpdateJob extends JobService {

    /** Accion del toque sobre un widget sin problema que arreglar: pide un refresco. */
    public static final String ACTION_TAP = "com.claulimitswidgets.android.WIDGET_TAP";

    static final int JOB_ID = 4201;
    static final int JOB_ID_BOOT = 4202;
    static final long PERIOD_MS = 15 * 60 * 1000L;

    // ---- programacion -------------------------------------------------------------------

    /** El trabajo periodico tal como debe estar registrado. */
    static JobInfo periodicInfo(Context ctx) {
        return periodicInfo(ctx, JOB_ID);
    }

    /** Lo mismo con otro id: las pruebas lo usan para no tocar el trabajo real del dueno. */
    static JobInfo periodicInfo(Context ctx, int id) {
        return new JobInfo.Builder(id, new ComponentName(ctx, WidgetUpdateJob.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // Persiste al reinicio (necesita RECEIVE_BOOT_COMPLETED en el manifiesto; sin el
                // permiso `schedule` lanza SecurityException). BootReceiver lo reprograma por si
                // aun asi se perdio y ademas pide un refresco inmediato.
                .setPersisted(true)
                .build();
    }

    /** Un refresco unico, en cuanto haya red. No persiste: solo vale para este arranque. */
    static JobInfo bootInfo(Context ctx) {
        return new JobInfo.Builder(JOB_ID_BOOT, new ComponentName(ctx, WidgetUpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
    }

    /**
     * Registra el periodico si no esta ya (o si esta con otros parametros). Solo ENCOLA en el
     * sistema: no bloquea, no hace red. Lo llaman `Widget*Provider.onUpdate`, dentro de la
     * ventana de `goAsync()` (~10 s), y LoginActivity en el hilo principal. Devuelve false si no
     * pudo; no lanza, para que un fallo de programacion no pinte "Sin conexion" en el widget.
     */
    public static boolean schedule(Context ctx) {
        return ensure(ctx.getSystemService(JobScheduler.class), periodicInfo(ctx));
    }

    /**
     * Pide un refresco unico al sistema (lo usa BootReceiver). Es un trabajo, no un hilo suelto:
     * un receptor de arranque que lanza un hilo y vuelve puede perder el proceso antes de que
     * termine la red. Solo encola.
     */
    public static boolean runSoon(Context ctx) {
        return ensure(ctx.getSystemService(JobScheduler.class), bootInfo(ctx));
    }

    /** Sin el trabajo equivocado ya registrado, lo registra. Nunca lanza. */
    static boolean ensure(JobScheduler js, JobInfo want) {
        if (js == null) return false;
        try {
            JobInfo have = js.getPendingJob(want.getId());
            if (have != null && same(have, want)) return true;
            return js.schedule(want) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            return false;   // p. ej. SecurityException si falta el permiso; sin citar nada
        }
    }

    /**
     * Mismo trabajo en lo que importa; si un parametro cambia, se vuelve a registrar.
     * `getNetworkType` esta marcado obsoleto desde API 28 pero es la pareja exacta de
     * `setRequiredNetworkType` y sigue funcionando; `getRequiredNetwork` devuelve una peticion
     * armada por el sistema, que no se puede comparar con una ingenua.
     */
    @SuppressWarnings("deprecation")
    static boolean same(JobInfo a, JobInfo b) {
        return a.getId() == b.getId()
                && a.isPeriodic() == b.isPeriodic()
                && a.getIntervalMillis() == b.getIntervalMillis()
                && a.isPersisted() == b.isPersisted()
                && a.getNetworkType() == b.getNetworkType()
                && a.getService().equals(b.getService());
    }

    /** Cancela los dos trabajos. Lo llama Session al cerrar sesion y onDisabled al quitar el ultimo widget. */
    public static void cancel(Context ctx) {
        cancelAll(ctx.getSystemService(JobScheduler.class));
    }

    static void cancelAll(JobScheduler js) {
        if (js == null) return;
        js.cancel(JOB_ID);
        js.cancel(JOB_ID_BOOT);
    }

    // ---- refresco inmediato -------------------------------------------------------------

    /**
     * Cuantos refrescos inmediatos a la vez. Cinco toques seguidos no pueden ser cinco consultas
     * a claude.ai: uno en curso y, si llegan mas, UNO mas en cola (no se pierde: el ultimo toque
     * puede venir de elegir otra organizacion, y ese refresco tiene que ocurrir).
     */
    static final class NowGate {
        private boolean running;
        private boolean again;

        /** true = quien llama debe arrancar el hilo; false = ya hay uno y quedo anotado. */
        synchronized boolean claim() {
            if (running) { again = true; return false; }
            running = true;
            return true;
        }

        /** El hilo termino una vuelta: true = hay otra pendiente; false = ya libre. */
        synchronized boolean next() {
            if (again) { again = false; return true; }
            running = false;
            return false;
        }

        /** El hilo ni siquiera pudo arrancar. */
        synchronized void abort() { running = false; again = false; }
    }

    private static final NowGate GATE = new NowGate();

    /**
     * Actualizacion inmediata, fuera del periodo: al iniciar sesion, al tocar el widget y al
     * elegir organizacion. SOLO ENCOLA: arranca un hilo y vuelve, jamas hace la consulta aqui.
     * `Widget*Provider.onUpdate` la llama dentro de la ventana de `goAsync()` (~10 s) y
     * `onReceive(ACTION_TAP)` en el hilo principal sin `goAsync`: un refresco sincrono con red
     * ahi mataria el proceso.
     */
    public static void runNow(Context ctx) {
        runNow(ctx, () -> { });
    }

    /** Con aviso `done` al terminar (o enseguida si ya habia un refresco en curso). Solo ENCOLA. */
    public static void runNow(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        runNowWith(GATE, healPeriodic(app), () -> new UsageRefresher(app).refresh(), Session.EPOCH::get,
                s -> paintAll(app, s), done);
    }

    /**
     * Red de seguridad: `updatePeriodMillis="0"`, asi que el lanzador nunca llama a `onUpdate` por
     * tiempo, y el toque no programa nada. Si el periodico se perdio (cancelacion espuria, carrera
     * con el login), el toque o el login lo repone. Solo con sesion (no se programa sin ella) y
     * `schedule` es idempotente: no reinicia el periodo. Corre en el hilo del refresco (toca disco).
     */
    static Runnable healPeriodic(Context app) {
        return () -> healPeriodic(() -> new SessionStore(app).isAbsent(), () -> schedule(app));
    }

    static void healPeriodic(java.util.function.BooleanSupplier noSessionFile, Runnable schedule) {
        if (!noSessionFile.getAsBoolean()) schedule.run();
    }

    /**
     * El toque sobre un widget. Un hilo suelto lanzado desde `onReceive` puede perder el proceso
     * antes de acabar la red; con `goAsync` el receptor sigue vivo mientras dura el refresco. El
     * presupuesto de `goAsync` es de ~10 s, asi que se suelta a los 8 s pase lo que pase (una
     * sola vez: `finish` dos veces lanza). `pending` puede ser null fuera de un receptor real.
     */
    public static void tap(Context ctx, android.content.BroadcastReceiver.PendingResult pending) {
        Runnable fin = once(() -> {
            if (pending != null) pending.finish();
        });
        if (pending != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(fin, TAP_BUDGET_MS);
        }
        runNow(ctx, fin);
    }

    static final long TAP_BUDGET_MS = 8000L;

    /** `runNow` con todo inyectado: las pruebas no pueden tocar la red ni los widgets del dueno. */
    static void runNowWith(NowGate gate, Supplier<Snapshot> refresh, LongSupplier epoch,
                           Consumer<Snapshot> sink, Runnable done) {
        runNowWith(gate, () -> { }, refresh, epoch, sink, done);
    }

    /**
     * Con `prelude`: se corre en el hilo del refresco, antes de la primera vuelta; si lanza
     * (excepcion o Error), se ignora y el refresco sigue. Va DESPUES de `gate.claim()`: si ya hay
     * un refresco en vuelo, el prelude de este toque no corre (aceptado: el del vuelo ya curo o lo
     * hara el siguiente).
     */
    static void runNowWith(NowGate gate, Runnable prelude, Supplier<Snapshot> refresh, LongSupplier epoch,
                           Consumer<Snapshot> sink, Runnable done) {
        if (!gate.claim()) {
            // Ya hay un refresco en curso (y uno en cola anotado): nada que esperar.
            safely(done);
            return;
        }
        try {
            new Thread(() -> {
                try {
                    safely(prelude);
                    do {
                        cycle(refresh, epoch, sink, () -> { });
                    } while (gate.next());
                } catch (RuntimeException | Error e) {
                    gate.abort();   // no debe quedar la puerta cerrada ni llevarse el proceso
                } finally {
                    safely(done);
                }
            }, "cuw-refresh").start();
        } catch (RuntimeException | Error e) {
            gate.abort();   // sin hilo no hay trabajo, y la puerta no puede quedar cerrada
            safely(done);
        }
    }

    private static void safely(Runnable r) {
        try { r.run(); } catch (RuntimeException | Error ignored) { }
    }

    /**
     * Una vuelta completa: consultar, y pintar SOLO si no hubo un cierre de sesion entretanto.
     * `done` se llama siempre, haya salido bien o no (en el job es `jobFinished`).
     *
     * `refresh()` promete no lanzar, pero esto es lo que hace que la promesa no dependa de
     * recordarlo: un hilo crudo que lanza se lleva el proceso.
     */
    static void cycle(Supplier<Snapshot> refresh, LongSupplier epoch, Consumer<Snapshot> sink,
                      Runnable done) {
        try {
            long start = epoch.getAsLong();
            Snapshot s;
            try {
                s = refresh.get();
            } catch (RuntimeException e) {
                s = null;
            }
            if (s == null) s = Snapshot.of(Snapshot.Problem.BAD_FORMAT);
            pushIfCurrent(s, start, epoch, sink);
        } catch (RuntimeException ignored) {
            // Pintar fallo (p. ej. el lanzador se fue): el siguiente ciclo lo repinta.
        } finally {
            done.run();
        }
    }

    // ---- pintar -------------------------------------------------------------------------

    /**
     * Un cierre de sesion pinta "sin sesion" al terminar. Un refresco que empezo antes y acaba
     * despues traeria los numeros de la cuenta que se acaba de cerrar: pintarlos los
     * resucitaria en la pantalla de inicio. Por eso la comprobacion de la epoca y el pintado
     * van bajo el MISMO candado que el pintado del logout: o el refresco pinta antes de que
     * el logout empiece, o ve la epoca cambiada y calla. Es un candado corto (sin red ni disco
     * dentro, solo IPC al lanzador), a diferencia de UsageRefresher.LOCK.
     */
    private static final Object PUSH_LOCK = new Object();

    /** false = no se pinto porque la sesion se cerro desde que empezo el refresco. */
    static boolean pushIfCurrent(Snapshot s, long startEpoch, LongSupplier epoch,
                                 Consumer<Snapshot> sink) {
        synchronized (PUSH_LOCK) {
            if (epoch.getAsLong() != startEpoch) return false;
            sink.accept(s);
            return true;
        }
    }

    /**
     * El unico sitio donde el pintado incondicional (el de Session.logout, con "sin sesion") toma
     * el candado: el llamador pasa el sumidero (`paintAll` en produccion), como los ganchos de
     * Session.logout; asi las pruebas ejercen este camino sin tocar los widgets del dueno.
     */
    static void pushLocked(Snapshot s, Consumer<Snapshot> sink) {
        synchronized (PUSH_LOCK) {
            sink.accept(s);
        }
    }

    /**
     * Pinta todos los widgets de los dos tamanios. Si alguno no se deja pintar, los demas se
     * pintan igual y AL FINAL lanza: `Session.logout` no puede prometer "todo hecho" (true) si un
     * widget se quedo con los numeros de la cuenta cerrada.
     */
    static void paintAll(Context ctx, Snapshot s) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        boolean ok = paintEach(awm.getAppWidgetIds(new ComponentName(ctx, Widget4x1Provider.class)),
                id -> paint(awm, id, ctx, s, true));
        ok &= paintEach(awm.getAppWidgetIds(new ComponentName(ctx, Widget4x2Provider.class)),
                id -> paint(awm, id, ctx, s, false));
        if (!ok) throw new IllegalStateException("algun widget no se pudo pintar");
    }

    /** Intenta todos; false si alguno fallo. Un widget que falla no deja sin pintar a los demas. */
    static boolean paintEach(int[] ids, java.util.function.IntPredicate paintOne) {
        boolean ok = true;
        for (int id : ids) ok &= paintOne.test(id);
        return ok;
    }

    private static boolean paint(AppWidgetManager awm, int id, Context ctx, Snapshot s,
                                 boolean compact) {
        try {
            awm.updateAppWidget(id, WidgetRenderer.render(ctx, s, compact));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static int countAll(Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        return awm.getAppWidgetIds(new ComponentName(ctx, Widget4x1Provider.class)).length
             + awm.getAppWidgetIds(new ComponentName(ctx, Widget4x2Provider.class)).length;
    }

    // ---- organizaciones -----------------------------------------------------------------

    /**
     * Vuelve a leer /api/organizations y lo recuerda; despues llama a `onDone` desde el hilo de
     * red (quien llama lo pasa al principal). Lo llama Ajustes al abrirse. SOLO ENCOLA.
     *
     * NO llama a `refresh()`: ese, con pista y cache, se salta `/organizations` justo para
     * ahorrar peticiones, asi que la lista no se refrescaria nunca y encima gastaria una
     * consulta de uso. Aqui se pide la lista y nada mas; sin backoff, porque es una accion del
     * usuario. Si no hay sesion, falla la red o se cierra la sesion a medias, NO llama a onDone:
     * no hay refresco que haya terminado y Ajustes sigue usable con la lista vieja.
     */
    public static void refreshOrgs(Context ctx, Runnable onDone) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            new Thread(() -> {
                try {
                    if (fetchAndRememberOrgs(app)) onDone.run();
                } catch (RuntimeException | Error ignored) {
                    // Ajustes sigue usable con la lista vieja.
                }
            }, "cuw-orgs").start();
        } catch (RuntimeException | Error ignored) {
            // Sin hilo no hay refresco.
        }
    }

    /** true = la lista nueva quedo guardada. Hace red y disco: nunca en el hilo principal. */
    private static boolean fetchAndRememberOrgs(Context app) {
        long start = Session.EPOCH.get();
        try {
            SessionStore store = new SessionStore(app);
            if (!store.hasSession()) return false;
            String cookies = store.load();
            if (cookies == null) return false;
            UsageClient c = new UsageClient(cookies, UsageRefresher.userAgentOf(app));
            List<UsageClient.Org> orgs = c.organizations();   // la red, SIN el candado
            SnapshotStore meta = Session.snapshotFor(app);
            // El candado es a proposito y no sobra: `rememberOrgs` escribe las mismas
            // preferencias que un refresco en curso, y los dos pueden coincidir.
            synchronized (UsageRefresher.LOCK) {
                if (!rememberIfCurrent(meta, orgs, start, Session.EPOCH::get, Instant.now())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;   // sin citar nada: el mensaje podria llevar un trozo de respuesta
        }
    }

    /**
     * Guarda la lista solo si no se cerro sesion desde `start`, y deshace lo escrito si el cierre
     * llega entre la comprobacion y la escritura (como `UsageRefresher.committed`). Se decide por
     * la epoca, no por `hasSession()`: este camino BORRA y `hasSession()` puede dar falso con la
     * sesion intacta.
     */
    static boolean rememberIfCurrent(SnapshotStore meta, List<UsageClient.Org> orgs, long start,
                                     LongSupplier epoch, Instant at) {
        if (epoch.getAsLong() != start) return false;
        meta.rememberOrgs(orgs, at);
        if (epoch.getAsLong() != start) {
            meta.clear();
            return false;
        }
        return true;
    }

    // ---- el servicio --------------------------------------------------------------------

    /** `jobFinished(params, wantsReschedule)`: false, el periodico ya vuelve solo en su ciclo. */
    static final boolean RESCHEDULE_ON_FINISH = false;
    /** `onStopJob`: false, un reintento con la espera del sistema solo sumaria consultas. */
    static final boolean RETRY_ON_STOP = false;

    /** Los trabajos en marcha, por id: `onStopJob` avisa al hilo trabajador por aqui. */
    static final java.util.Map<Integer, Cancel> RUNNING =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        return startJob(RUNNING, params.getJobId(), r -> new Thread(r, "cuw-job").start(),
                () -> countAll(app), () -> new SessionStore(app).isAbsent(), () -> cancel(app),
                id -> cancelForeign(app, id),
                c -> new UsageRefresher(app, c).refresh(), Session.EPOCH::get,
                s -> paintAll(app, s), () -> jobFinished(params, RESCHEDULE_ON_FINISH));
    }

    /** Cancela un job que llega a este servicio con un id que no es de la app (ver `startJob`). */
    private static void cancelForeign(Context app, int id) {
        JobScheduler js = app.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(id);
    }

    /**
     * `runJob` con un aviso de corte propio: queda registrado por id mientras el hilo trabaja y
     * {@link #stopJob} lo dispara. Un trabajo cortado ni pinta (no hay resultado que valga) ni
     * llama a `jobFinished` (el sistema ya lo dio por terminado). Todo inyectado, para la prueba.
     */
    static boolean startJob(java.util.Map<Integer, Cancel> running, int jobId,
                            java.util.concurrent.Executor spawn,
                            java.util.function.IntSupplier widgets,
                            java.util.function.BooleanSupplier noSessionFile, Runnable cancelJob,
                            java.util.function.IntConsumer cancelForeign,
                            java.util.function.Function<Cancel, Snapshot> refresh,
                            LongSupplier epoch, Consumer<Snapshot> sink, Runnable finish) {
        // Solo el periodico y el de arranque son nuestros. Cualquier otro id registrado contra
        // este servicio (un job persistido de una version vieja del APK, una sonda que sobrevivio
        // a un reinicio) ejecutaria el ciclo completo con la cookie del dueno: se cancela ese id
        // y no se hace nada mas (ni red, ni pintado, ni hilo).
        if (jobId != JOB_ID && jobId != JOB_ID_BOOT) {
            cancelForeign.accept(jobId);
            finish.run();
            return false;
        }
        final Cancel cancel = new Cancel();
        running.put(jobId, cancel);
        boolean started = runJob(spawn, widgets, noSessionFile, cancelJob,
                () -> refresh.apply(cancel), epoch,
                s -> { if (!cancel.isCancelled()) sink.accept(s); },
                () -> { if (running.remove(jobId, cancel)) finish.run(); });
        if (!started) running.remove(jobId, cancel);
        return started;
    }

    /** El sistema corto el trabajo `jobId`: el hilo trabajador aborta la red en curso. */
    static void stopJob(java.util.Map<Integer, Cancel> running, int jobId) {
        Cancel c = running.remove(jobId);
        if (c != null) c.cancel();
    }

    /** `finish` como mucho una vez: dos avisos de `jobFinished` por el mismo trabajo son un error. */
    static Runnable once(Runnable finish) {
        AtomicBoolean finished = new AtomicBoolean();
        return () -> {
            if (finished.compareAndSet(false, true)) finish.run();
        };
    }

    /**
     * La decision de `onStartJob`, sin tocar el sistema. true = sigue trabajando en segundo
     * plano (hay que avisar con `finish`); false = no se pudo arrancar el trabajo y el sistema lo
     * da por terminado (el siguiente periodo reintenta). Si estamos en espera por backoff el
     * periodo (15 min) es mas largo que el primer escalon: `refresh()` se salta la red y el
     * siguiente ciclo reintenta.
     */
    static boolean runJob(java.util.concurrent.Executor spawn, java.util.function.IntSupplier widgets,
                          java.util.function.BooleanSupplier noSessionFile, Runnable cancel, Supplier<Snapshot> refresh, LongSupplier epoch,
                          Consumer<Snapshot> sink, Runnable finish) {
        Runnable done = once(finish);
        try {
            spawn.execute(() -> {
                try {
                    // El dueno quito el ultimo widget (o el sistema no llamo a onDisabled): no hay
                    // a quien actualizar ni razon para gastar una consulta ni seguir despertando.
                    // Tampoco hay a quien consultar sin sesion: ni archivo de sesion (no el "no se
                    // puede leer" de hasSession(): un hipo del Keystore no apaga el job).
                    // LoginActivity.scheduleAfterLogin lo reprograma al entrar.
                    if (widgets.getAsInt() == 0 || noSessionFile.getAsBoolean()) {
                        cancel.run();
                        done.run();
                        return;
                    }
                    cycle(refresh, epoch, sink, done);
                } catch (RuntimeException | Error e) {
                    done.run();
                }
            });
        } catch (RuntimeException | Error e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // El sistema corto el trabajo (se perdio la red, ahorro de bateria). Se avisa al hilo para
        // que aborte la conexion en vez de seguir hasta agotar los timeouts. No se pide reintento
        // propio: el periodico vuelve a correr en su ciclo.
        return onStop(params == null ? -1 : params.getJobId());
    }

    /** `onStopJob` sin `JobParameters` (que una prueba no puede construir): corta el trabajo `jobId`. */
    boolean onStop(int jobId) {
        stopJob(RUNNING, jobId);
        return RETRY_ON_STOP;
    }
}
