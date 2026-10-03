package com.claudewidgets.core;

import java.util.Collections;
import java.util.Map;

/** Resultado de R3 y R4. `quotaToday` nulo = no se puede calcular. */
public final class DayUsage {
    /** Claves `YYYY-MM-DD` locales. Solo dias con aporte > 0. */
    public final Map<String, Double> perDay;
    public final double todayUsed;
    public final Double quotaToday;
    /** Cierto si la historia no alcanza a antes de la medianoche de hoy. */
    public final boolean partial;

    public DayUsage(Map<String, Double> perDay, double todayUsed, Double quotaToday,
                    boolean partial) {
        this.perDay = Collections.unmodifiableMap(perDay);
        this.todayUsed = todayUsed;
        this.quotaToday = quotaToday;
        this.partial = partial;
    }
}
