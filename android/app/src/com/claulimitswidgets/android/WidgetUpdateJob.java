package com.claulimitswidgets.android;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Esqueleto de F3; F4 lo llena. Los metodos existen para que LoginActivity, Session y
 * SettingsActivity compilen. Hoy ninguno hace su trabajo: no programan, no cancelan, no consultan
 * y no pintan. Ninguno finge exito. Los puntos de enganche de F4 estan marcados con "F4:".
 */
public final class WidgetUpdateJob {
    private WidgetUpdateJob() {}

    /** Accion del toque sobre un widget sin problema que arreglar: pide un refresco. */
    public static final String ACTION_TAP = "com.claulimitswidgets.android.WIDGET_TAP";

    /** Cuantos widgets hay puestos, de los dos tamanios. F4 (4.4) decide con esto si cancela. */
    public static int countAll(Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        return awm.getAppWidgetIds(new ComponentName(ctx, Widget4x1Provider.class)).length
                + awm.getAppWidgetIds(new ComponentName(ctx, Widget4x2Provider.class)).length;
    }

    /** F3: no programa nada. F4: registrar el job periodico (cada 15 min) en JobScheduler. */
    public static void schedule(Context ctx) {
        // F4: enganchar aqui JobScheduler. Lo llama LoginActivity tras entrar.
    }

    /** F3: no cancela nada. F4: cancelar el job periodico. Lo llama Session al cerrar sesion. */
    public static void cancel(Context ctx) {
        // F4: enganchar aqui JobScheduler.cancel. Lo llama Session al cerrar sesion.
    }

    /** F3: no refresca nada. F4: lanzar un refresco inmediato (UsageRefresher). */
    public static void runNow(Context ctx) {
        // F4: enganchar aqui el refresco inmediato. Lo llaman LoginActivity y los radios de Ajustes.
    }

    /** F3: no repinta nada. F4: cambiar la firma a (Context, Snapshot) y repintar los widgets. */
    public static void pushToWidgets(Context ctx) {
        // F4: enganchar aqui RemoteViews/AppWidgetManager. Session necesitara pasar un Snapshot.
    }

    /**
     * F3: no consulta nada, asi que NO llama a onDone: no hay refresco que haya terminado.
     * F4: consultar /organizations y llamar a onDone al volver del hilo de red.
     */
    public static void refreshOrgs(Context ctx, Runnable onDone) {
        // F4: enganchar aqui la consulta de organizaciones. Lo llama SettingsActivity al abrirse.
    }
}
