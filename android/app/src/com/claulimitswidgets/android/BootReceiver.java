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
 * de que termine la red. Solo encola: no hay red ni disco en el hilo principal.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        // Sin widgets puestos no hay a quien actualizar: ni se programa ni se consulta.
        if (WidgetUpdateJob.countAll(ctx) == 0) return;
        WidgetUpdateJob.schedule(ctx);
        WidgetUpdateJob.runSoon(ctx);
    }
}
