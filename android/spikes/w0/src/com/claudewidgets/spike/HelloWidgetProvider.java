package com.claudewidgets.spike;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Spike W0 / criterio B: el widget mas tonto posible. Muestra la hora de su ultima
 * actualizacion y se vuelve a actualizar al tocarlo. Sirve para confirmar que un APK
 * construido a mano en el telefono puede publicar un AppWidget y refrescarlo.
 */
public class HelloWidgetProvider extends AppWidgetProvider {

    static final String TAG = "SpikeW0";
    /** Accion propia: el toque en el widget vuelve aqui como broadcast dirigido. */
    static final String ACTION_TAP = "com.claudewidgets.spike.TAP";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        Log.i(TAG, "B.1 onUpdate con " + ids.length + " instancia(s)");
        for (int id : ids) render(ctx, awm, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_TAP.equals(intent.getAction())) {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, HelloWidgetProvider.class));
            Log.i(TAG, "B.1 toque recibido, refrescando " + ids.length + " instancia(s)");
            for (int id : ids) render(ctx, awm, id);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onEnabled(Context ctx) {
        Log.i(TAG, "B.1 primera instancia del widget creada");
    }

    private void render(Context ctx, AppWidgetManager awm, int id) {
        String now = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_hello);
        rv.setTextViewText(R.id.widget_time, now);

        Intent tap = new Intent(ctx, HelloWidgetProvider.class).setAction(ACTION_TAP);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);

        awm.updateAppWidget(id, rv);
        Log.i(TAG, "B.1 widget " + id + " actualizado a " + now);
    }
}
