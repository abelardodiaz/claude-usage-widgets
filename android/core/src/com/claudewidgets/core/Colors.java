package com.claudewidgets.core;

import java.time.Instant;

/** R7. Decide colores; no dibuja nada. */
public final class Colors {

    private static final double WEEK_SECONDS = 7 * 86400.0;

    private Colors() {}

    /** Barras de sesion, semana y limitados. `percent` se usa crudo (R0). */
    public static Color bar(double percent) {
        if (percent < 60) return Color.GREEN;
        if (percent < 85) return Color.AMBER;
        return Color.RED;
    }

    /**
     * Relleno de la barra de hoy (R7). El orden importa: el nulo va primero porque `quotaToday`
     * es un {@link Double} y compararlo antes de descartarlo lanzaria NullPointerException.
     */
    public static TodayFill todayFill(double todayUsed, Double quotaToday) {
        if (quotaToday == null) return new TodayFill(TodayState.UNKNOWN, null);
        double q = quotaToday;
        if (q <= 0) return new TodayFill(TodayState.EXHAUSTED, 1.0);  // la semana ya se agoto
        return new TodayFill(TodayState.OK, todayUsed / q);              // crudo, sin acotar
    }

    /**
     * Barra de hoy, sobre {@link #todayFill}: UNKNOWN -> gris; EXHAUSTED (cuota <= 0) -> rojo;
     * OK, por el cociente (nunca multiplicacion cruzada): verde < 0.7, ambar < 1, rojo >= 1.
     */
    public static Color today(double todayUsed, Double quotaToday) {
        TodayFill fill = todayFill(todayUsed, quotaToday);
        if (fill.state == TodayState.UNKNOWN) return Color.GRAY;
        if (fill.state == TodayState.EXHAUSTED) return Color.RED;
        double ratio = fill.fraction;
        if (ratio < 0.7) return Color.GREEN;
        if (ratio < 1) return Color.AMBER;
        return Color.RED;
    }

    /**
     * Marca de ritmo parejo en la barra semanal: cuanto de la ventana transcurrio, en [0, 1].
     * Nula solo si no hay `resetsAt`. Con el reinicio ya pasado la marca es 1, no nula: la
     * ventana transcurrio entera.
     */
    public static Double paceMark(Instant resetsAt, Instant now) {
        if (resetsAt == null) return null;
        double left = Projection.seconds(now, resetsAt);  // no desborda (ver alli)
        double v = 1 - left / WEEK_SECONDS;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
