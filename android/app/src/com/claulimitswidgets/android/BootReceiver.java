package com.claulimitswidgets.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Tras reiniciar (o actualizar la app) deja el widget andando.
 *
 * El job periodico persiste al reinicio (`setPersisted(true)` + RECEIVE_BOOT_COMPLETED), pero el
 * primer ciclo tardaria hasta 15 min y, si el sistema lo perdio, no llegaria nunca: aqui se
 * reprograma y se pide un refresco inmediato. Ese refresco es un TRABAJO UNICO de JobScheduler
 * (`runSoon`) y no un hilo: un receptor que lanza un hilo y vuelve puede perder el proceso antes
 * de que termine la red. Solo encola: no hay red ni disco en el hilo principal (la comprobacion
 * de sesion va a un hilo, con `goAsync`).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        // Mirar si hay archivo de sesion es disco: no en el hilo principal. goAsync mantiene vivo
        // el receptor mientras el hilo decide; lo que decide solo ENCOLA trabajos (sin red).
        final PendingResult pending = goAsync();   // puede ser null fuera de un receptor real
        offMain(r -> new Thread(r, "cuw-boot").start(),
                () -> handle(action, () -> WidgetUpdateJob.countAll(ctx),
                        () -> new SessionStore(ctx).isAbsent(), () -> WidgetUpdateJob.schedule(ctx),
                        () -> WidgetUpdateJob.cancel(ctx), () -> WidgetUpdateJob.runSoon(ctx)),
                () -> {
                    if (pending != null) pending.finish();
                });
    }

    /**
     * `work` en `spawn` y `finish` exactamente una vez pase lo que pase (el trabajo lanza, o el
     * hilo ni arranca): un `goAsync` sin `finish` es un ANR del receptor.
     */
    static void offMain(java.util.concurrent.Executor spawn, Runnable work, Runnable finish) {
        final Runnable done = WidgetUpdateJob.once(finish);
        try {
            spawn.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException | Error ignored) {
                    // El siguiente arranque o el toque en el widget lo reintentan.
                } finally {
                    done.run();
                }
            });
        } catch (RuntimeException | Error e) {
            done.run();
        }
    }

    /** La decision, sin tocar el sistema: las pruebas no pueden programar jobs ni consultar. */
    static void handle(String action, java.util.function.IntSupplier widgets,
                       java.util.function.BooleanSupplier noSessionFile, Runnable schedule,
                       Runnable cancel, Runnable runSoon) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        // Sin widgets puestos no hay a quien actualizar: ni se programa ni se consulta.
        if (widgets.getAsInt() == 0) return;
        // Sin sesion tampoco: el periodico despertaria cada 15 min para nada. Se quita el que
        // hubiera quedado persistido; al entrar, LoginActivity lo reprograma.
        if (noSessionFile.getAsBoolean()) {
            cancel.run();
            return;
        }
        schedule.run();
        runSoon.run();
    }
}
