package com.claudewidgets.core;

/** Estado de la barra de hoy (R7). El texto es el que usan los fixtures. */
public enum TodayState {
    /** Sin cuota calculable (`quota_today` nulo). */
    UNKNOWN("unknown"),
    /** `quota_today <= 0`: la semana ya esta en el 100 % o por encima, hoy no queda nada. */
    EXHAUSTED("exhausted"),
    /** Cuota positiva. */
    OK("ok");

    private final String wire;

    TodayState(String wire) { this.wire = wire; }

    @Override public String toString() { return wire; }
}
