package com.claulimitswidgets.android;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * 4x2 con barras y proyeccion. `exported="false"`: verificado en One UI (spike B).
 *
 * No hereda del otro proveedor: el sistema instancia cada `AppWidgetProvider` por su cuenta y
 * heredar entre proveedores registrados confunde el despacho de `onUpdate`. Lo comun vive en
 * {@link ProviderUpdate}, que tiene prueba.
 */
public class Widget4x2Provider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        // goAsync mantiene vivo el receptor mientras el trabajo corre fuera del hilo principal;
        // puede ser null fuera de un receptor real. Todo el cuerpo vive en ProviderUpdate.
        ProviderUpdate.onUpdate(ctx, awm, ids, goAsync(), compact());
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (WidgetUpdateJob.ACTION_TAP.equals(intent.getAction())) {
            // goAsync: el receptor sigue vivo mientras dura el refresco (ver WidgetUpdateJob.tap).
            WidgetUpdateJob.tap(ctx, goAsync());
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
