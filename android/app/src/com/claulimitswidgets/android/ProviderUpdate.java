package com.claulimitswidgets.android;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * El cuerpo de `onUpdate` de los dos proveedores (4x1 y 4x2), en UN sitio.
 *
 * Antes estaba copiado byte a byte en cada proveedor y ninguno tenia prueba. Ahora todo lo que toca
 * el aparato entra por parametro (hilo, lectura, pintado, planificador), como en
 * `BootReceiver.handle` y `WidgetUpdateJob.runJob`: la prueba ejerce este camino entero sin
 * pintar los widgets del dueno ni programar su job.
 */
final class ProviderUpdate {

    private ProviderUpdate() {}

    /** El cableado de produccion. Lo unico que cambia entre un proveedor y otro es `compact`. */
    static void onUpdate(Context ctx, AppWidgetManager awm, int[] ids,
                         BroadcastReceiver.PendingResult pending, boolean compact) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        update(r -> new Thread(r, "widget-update").start(),
                () -> new UsageRefresher(app).last(), Session.EPOCH::get,
                s -> {
                    for (int id : ids) awm.updateAppWidget(id, WidgetRenderer.render(app, s, compact));
                },
                () -> new SessionStore(app).isAbsent(),
                () -> WidgetUpdateJob.schedule(app), () -> WidgetUpdateJob.cancel(app),
                () -> WidgetUpdateJob.runNow(app),
                () -> {
                    if (pending != null) pending.finish();   // null fuera de un receptor real
                });
    }

    /**
     * `onUpdate` corre en el hilo principal y `UsageRefresher.last()` hace disco y pasa por el
     * nucleo: StrictMode lo castigaria. El trabajo va a `spawn`, y `finish` (el `goAsync`) se
     * suelta exactamente una vez pase lo que pase, incluido que el hilo ni siquiera arranque.
     *
     * @param noSessionFile  true = no hay NI archivo de sesion (no el "no se puede leer" de
     *                       `hasSession()`: una llave perdida o un hipo no apagan el job)
     */
    static void update(Executor spawn, Supplier<Snapshot> last, LongSupplier epoch,
                       Consumer<Snapshot> paint, BooleanSupplier noSessionFile, Runnable schedule,
                       Runnable cancelJob, Runnable runNow, Runnable finish) {
        final Runnable done = WidgetUpdateJob.once(finish);
        Runnable work = () -> {
            try {
                // La epoca se captura ANTES de leer: si el dueno cierra sesion mientras tanto, el
                // logout ya pinto "sin sesion" y esto NO puede pintar encima los porcentajes de
                // la cuenta cerrada (nadie los repintaria: el logout cancelo el job).
                long start = epoch.getAsLong();
                Snapshot s = last.get();
                WidgetUpdateJob.pushIfCurrent(s, start, epoch, paint);
                if (s.problem == Snapshot.Problem.NO_SESSION && noSessionFile.getAsBoolean()) {
                    // Sin sesion no hay nada que consultar: el job despertaria cada 15 min para
                    // nada. LoginActivity.scheduleAfterLogin lo reprograma al entrar.
                    cancelJob.run();
                } else {
                    schedule.run();
                    // Primera vez: hay sesion pero ningun dato todavia. Sin esto el widget se
                    // queda en "Actualizando..." hasta el primer ciclo del job (hasta 15 min).
                    if (s.problem == Snapshot.Problem.LOADING) runNow.run();
                }
            } catch (RuntimeException e) {
                // Un fallo aqui no puede dejar el widget en blanco: se pinta un aviso.
                try {
                    paint.accept(Snapshot.of(Snapshot.Problem.OFFLINE));
                } catch (RuntimeException ignored) {
                    // Sin nada mas que hacer; el siguiente ciclo del job lo repinta.
                }
            } finally {
                done.run();
            }
        };
        try {
            spawn.execute(work);
        } catch (RuntimeException | Error e) {
            // Sin hilo no hay trabajo, pero el PendingResult no puede quedar huerfano (ANR).
            done.run();
        }
    }
}
