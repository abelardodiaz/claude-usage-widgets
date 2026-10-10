package com.claulimitswidgets.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.claudewidgets.core.Color;
import com.claudewidgets.core.Colors;
import com.claudewidgets.core.TodayFill;
import com.claudewidgets.core.TodayState;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Snapshot -> RemoteViews. NO decide nada: los colores salen de `Colors` (R7) y los numeros del
 * Snapshot. Si aqui aparece un umbral, esta mal.
 */
public final class WidgetRenderer {

    private WidgetRenderer() {}

    public static RemoteViews render(Context ctx, Snapshot s, boolean compact) {
        return render(ctx, s, compact, Instant.now());
    }

    /** `now` inyectable: la edad ("hace 5 min") depende del reloj y se prueba con uno fijo. */
    static RemoteViews render(Context ctx, Snapshot s, boolean compact, Instant now) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(),
                compact ? R.layout.widget_4x1 : R.layout.widget_4x2);

        if (s.problem != null) {
            v.setTextViewText(R.id.notice, ctx.getString(problemText(s.problem)));
            v.setViewVisibility(R.id.notice, android.view.View.VISIBLE);
            v.setViewVisibility(R.id.numbers, s.hasData()
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        } else {
            v.setViewVisibility(R.id.notice, android.view.View.GONE);
            v.setViewVisibility(R.id.numbers, android.view.View.VISIBLE);
        }

        // La edad se escribe SIEMPRE, tenga o no datos. En el 4x2 `age` vive fuera de `numbers`,
        // y el lanzador actualiza con `reapply`: lo que no se reenvia NO se borra. Sin esto, al
        // pasar a NO_SESSION o LOADING se quedaria "hace 5 min" congelado bajo el aviso.
        // (Con `fetchedAt == null` devuelve "".)
        v.setTextViewText(R.id.age, age(ctx, s.fetchedAt, now));

        if (s.hasData()) {
            // Se TRUNCA hacia abajo, no se redondea: el color se decide sobre el valor crudo
            // (R0/R7) y un 84,6 redondeado diria "85%" en ambar, con 85 como umbral del rojo.
            // Truncado, el texto nunca cruza un umbral antes que el color.
            int sp = whole(s.model.session.percent);
            int wp = whole(s.model.weekly.percent);
            v.setTextViewText(R.id.session, ctx.getString(R.string.w_session, sp));
            v.setTextViewText(R.id.weekly, ctx.getString(R.string.w_weekly, wp));

            if (!compact) {
                bar(v, SESSION_BARS, s.model.session.percent,
                        Colors.bar(s.model.session.percent));
                bar(v, WEEKLY_BARS, s.model.weekly.percent,
                        Colors.bar(s.model.weekly.percent));
                // Estado, fraccion y color de hoy salen del nucleo (R7). La fraccion viene CRUDA
                // (puede pasar de 1: el dia que la semana LLEGA al 100%, estado OK y rojo; con
                // cuota 0 es EXHAUSTED y vale 1); aqui solo se acota para dibujar.
                TodayFill fill = Colors.todayFill(s.day.todayUsed, s.day.quotaToday);
                bar(v, TODAY_BARS, fill.fraction == null ? 0 : 100 * fill.fraction,
                        Colors.today(s.day.todayUsed, s.day.quotaToday));
                v.setTextViewText(R.id.today, todayText(ctx, s.day, fill));
                // Marca de ritmo parejo: cuanto de la ventana semanal transcurrio (R7).
                v.setViewVisibility(R.id.pace, s.paceMark == null
                        ? android.view.View.GONE : android.view.View.VISIBLE);
                if (s.paceMark != null) {
                    v.setProgressBar(R.id.pace, 100, (int) Math.round(s.paceMark * 100), false);
                }
                v.setTextViewText(R.id.forecast, forecast(ctx, s));
            }
        }

        // El toque lleva a donde se arregla el problema, no siempre a "refrescar": decirle
        // "toca para iniciar sesion" y que al tocar solo reintente seria mentirle.
        v.setOnClickPendingIntent(R.id.root, tapIntent(ctx, s, compact));
        return v;
    }

    /**
     * Con NO_SESSION o AUTH_EXPIRED abre el login; con CHOOSE_ORG abre Ajustes; en los demas
     * casos refresca. El PendingIntent corre con la identidad de la app, asi que el broadcast
     * llega al receptor aunque sea `exported="false"` (verificado en W0).
     *
     * "Mantener pulsado = abrir la app" NO es posible: la pulsacion larga sobre un widget la
     * consume el lanzador para moverlo, y un AppWidgetProvider no la ve. Por eso el acceso a
     * Ajustes vive en la pantalla de la app (Tarea 3.8) y en el toque cuando hay que elegir.
     */
    private static PendingIntent tapIntent(Context ctx, Snapshot s, boolean compact) {
        int req = compact ? 1 : 2;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent target = tapTarget(ctx, s, compact);
        // Con accion es el broadcast de refrescar; sin ella, una actividad.
        return target.getAction() != null
                ? PendingIntent.getBroadcast(ctx, req, target, flags)
                : PendingIntent.getActivity(ctx, req, target, flags);
    }

    /**
     * A donde lleva el toque. Separado del PendingIntent para poder probar las tres rutas.
     * Nunca lleva extras: ni cookie ni uuid viajan en un Intent.
     *
     * CALLEJONES SIN SALIDA que se deciden aqui y que cierra la 4.4 (la causa esta en otras tareas):
     *  - OFFLINE mezcla "sin red", "hipo transitorio del Keystore" y "fallo PERMANENTE del
     *    Keystore" (cambiar o quitar el bloqueo de pantalla). En el ultimo el widget dice "Sin
     *    conexion" para siempre aunque el WiFi este perfecto, y el toque solo reintenta. Hace
     *    falta distinguirlo en SessionStore y mandarlo al login como AUTH_EXPIRED.
     *  - CHOOSE_ORG manda a Ajustes, donde `knownOrgs()` hoy devuelve vacio: la instruccion
     *    "elige organizacion en Ajustes" es imposible de cumplir hasta que la 4.4 la cablee.
     */
    static Intent tapTarget(Context ctx, Snapshot s, boolean compact) {
        if (s.problem == Snapshot.Problem.NO_SESSION || s.problem == Snapshot.Problem.AUTH_EXPIRED) {
            return new Intent(ctx, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (s.problem == Snapshot.Problem.CHOOSE_ORG) {
            return new Intent(ctx, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return new Intent(ctx, compact ? Widget4x1Provider.class : Widget4x2Provider.class)
                .setAction(WidgetUpdateJob.ACTION_TAP);
    }

    private static int whole(double percent) { return (int) Math.floor(percent); }

    private static String todayText(Context ctx, com.claudewidgets.core.DayUsage d, TodayFill fill) {
        if (fill.state == TodayState.EXHAUSTED) return ctx.getString(R.string.w_today_exhausted);
        return ctx.getString(R.string.w_today, one(ctx, d.todayUsed),
                fill.state == TodayState.UNKNOWN ? "\u2014" : one(ctx, d.quotaToday));
    }

    private static Locale locale(Context ctx) {
        return ctx.getResources().getConfiguration().getLocales().get(0);
    }

    // Orden fijo: verde, ambar, rojo, gris. Debe coincidir con `index(Color)`.
    private static final int[] SESSION_BARS =
            {R.id.session_green, R.id.session_amber, R.id.session_red, R.id.session_gray};
    private static final int[] WEEKLY_BARS =
            {R.id.weekly_green, R.id.weekly_amber, R.id.weekly_red, R.id.weekly_gray};
    private static final int[] TODAY_BARS =
            {R.id.today_green, R.id.today_amber, R.id.today_red, R.id.today_gray};

    /**
     * Enciende la barra del color que toca y apaga las otras tres. RemoteViews no puede tenir
     * un drawable en API 29; `setProgressBar` y `setViewVisibility` si estan en su lista blanca.
     */
    private static void bar(RemoteViews v, int[] ids, double percent, Color color) {
        int wanted = index(color);
        int clamped = (int) Math.round(Math.max(0, Math.min(100, percent)));
        for (int i = 0; i < ids.length; i++) {
            boolean on = i == wanted;
            v.setViewVisibility(ids[i], on ? android.view.View.VISIBLE : android.view.View.GONE);
            if (on) v.setProgressBar(ids[i], 100, clamped, false);
        }
    }

    private static int index(Color c) {
        switch (c) {
            case GREEN: return 0;
            case AMBER: return 1;
            case RED:   return 2;
            case GRAY:  return 3;
            // Un color nuevo en el nucleo no puede volverse gris en silencio.
            default: throw new IllegalStateException("color sin barra: " + c);
        }
    }

    private static int problemText(Snapshot.Problem p) {
        switch (p) {
            case NO_SESSION:    return R.string.p_no_session;
            case AUTH_EXPIRED:  return R.string.p_auth_expired;
            case BLOCKED:       return R.string.p_blocked;
            case OFFLINE:       return R.string.p_offline;
            case CHOOSE_ORG:    return R.string.p_choose_org;
            case LOADING:       return R.string.p_loading;
            default:            return R.string.p_bad_format;
        }
    }

    private static String forecast(Context ctx, Snapshot s) {
        if (s.weeklyForecast == null || s.weeklyForecast.hitsAt == null) {
            return ctx.getString(R.string.w_no_forecast);
        }
        // "j" = la hora segun la preferencia 12/24 h del aparato.
        Locale loc = locale(ctx);
        String when = DateTimeFormatter.ofPattern(
                        android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEjm"), loc)
                .withZone(ZoneId.systemDefault()).format(s.weeklyForecast.hitsAt);
        return ctx.getString(R.string.w_full_at, when);
    }

    private static String age(Context ctx, Instant fetchedAt, Instant now) {
        if (fetchedAt == null) return "";
        long min = Duration.between(fetchedAt, now).toMinutes();
        if (min < 1) return ctx.getString(R.string.w_age_now);
        if (min < 60) return ctx.getString(R.string.w_age_min, min);
        return ctx.getString(R.string.w_age_hour, min / 60);
    }

    private static String one(Context ctx, double v) {
        return String.format(locale(ctx), "%.1f%%", v);
    }
}
