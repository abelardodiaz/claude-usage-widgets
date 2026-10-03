package com.claudewidgets.core;

import java.time.Instant;

/** Una ventana de cuota: sesion (5 h) o semana (7 dias). `resetsAt` puede ser nulo. */
public final class Bar {
    public final double percent;
    public final Instant resetsAt;

    public Bar(double percent, Instant resetsAt) {
        this.percent = percent;
        this.resetsAt = resetsAt;
    }

    @Override public String toString() { return "Bar(" + percent + ", " + resetsAt + ")"; }
}
