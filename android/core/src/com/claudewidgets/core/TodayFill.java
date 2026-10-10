package com.claudewidgets.core;

/**
 * Relleno de la barra de hoy (R7). `fraction` es nula con UNKNOWN, 1 con EXHAUSTED y, con OK,
 * `today_used / quota_today` crudo en doble precision, SIN acotar: puede pasar de 1. La UI la
 * acota a [0, 1] solo para dibujar.
 */
public final class TodayFill {
    public final TodayState state;
    public final Double fraction;

    public TodayFill(TodayState state, Double fraction) {
        this.state = state;
        this.fraction = fraction;
    }
}
