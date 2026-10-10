package com.claulimitswidgets.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * 4x2 con barras y proyeccion. `exported="false"`: verificado en One UI (spike B).
 *
 * Se repite entero en vez de heredarlo del otro proveedor: el sistema instancia cada
 * `AppWidgetProvider` por su cuenta y heredar entre proveedores registrados confunde el
 * despacho de `onUpdate`.
 */
public class Widget4x2Provider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        // `onUpdate` corre en el hilo principal y `UsageRefresher.last()` hace disco y pasa por
        // el nucleo: StrictMode lo castigaria. `goAsync` mantiene vivo el receptor mientras tanto.
        final PendingResult pending = goAsync();   // puede ser null fuera de un receptor real
        final Context app = ctx.getApplicationContext();
        Runnable work = () -> {
            try {
                // La epoca se captura ANTES de leer: si el dueno cierra sesion mientras tanto, el
                // logout ya pinto "sin sesion" y esto NO puede pintar encima los porcentajes de
                // la cuenta cerrada (nadie los repintaria: el logout cancelo el job).
                long start = Session.EPOCH.get();
                Snapshot s = new UsageRefresher(app).last();
                WidgetUpdateJob.pushIfCurrent(s, start, Session.EPOCH::get, x -> {
                    for (int id : ids) awm.updateAppWidget(id, WidgetRenderer.render(app, x, compact()));
                });
                WidgetUpdateJob.schedule(app);
                // Primera vez: hay sesion pero ningun dato todavia. Sin esto el widget se queda en
                // "Actualizando..." hasta el primer ciclo del job, que puede tardar 15 minutos.
                if (s.problem == Snapshot.Problem.LOADING) WidgetUpdateJob.runNow(app);
            } catch (RuntimeException e) {
                // Un fallo aqui no puede dejar el widget en blanco: se pinta un aviso.
                try {
                    for (int id : ids) awm.updateAppWidget(id, WidgetRenderer.render(app,
                            Snapshot.of(Snapshot.Problem.OFFLINE), compact()));
                } catch (RuntimeException ignored) {
                    // Sin nada mas que hacer; el siguiente ciclo del job lo repinta.
                }
            } finally {
                if (pending != null) pending.finish();
            }
        };
        try {
            new Thread(work, "widget-update").start();
        } catch (RuntimeException | Error e) {
            // Sin hilo no hay trabajo, pero el PendingResult no puede quedar huerfano (ANR).
            if (pending != null) pending.finish();
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (WidgetUpdateJob.ACTION_TAP.equals(intent.getAction())) {
            WidgetUpdateJob.runNow(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onDisabled(Context ctx) {
        // Si no queda ningun widget de ningun tamanio, no hay a quien actualizar.
        if (WidgetUpdateJob.countAll(ctx) == 0) WidgetUpdateJob.cancel(ctx);
    }

    boolean compact() { return false; }
}
