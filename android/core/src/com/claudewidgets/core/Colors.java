package com.claudewidgets.core;

import java.time.Duration;
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
     * Barra de hoy. El orden de R7 importa: el nulo va primero porque `quotaToday` es un
     * {@link Double} y compararlo antes de descartarlo lanzaria NullPointerException.
     */
    public static Color today(double todayUsed, Double quotaToday) {
        if (quotaToday == null) return Color.GRAY;
        double q = quotaToday;
        if (q < 0) return Color.RED;      // la cuota semanal ya se agoto
        if (q == 0) return Color.GRAY;
        double ratio = todayUsed / q;     // sobre el cociente, no con multiplicacion cruzada
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
        double left = Duration.between(now, resetsAt).toNanos() / 1e9;
        double v = 1 - left / WEEK_SECONDS;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
