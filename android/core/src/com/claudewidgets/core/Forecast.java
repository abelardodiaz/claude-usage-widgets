package com.claudewidgets.core;

import java.time.Instant;

/** Resultado de R5 y R6. Los tres campos son nulos cuando no se puede proyectar. */
public final class Forecast {
    public final Instant hitsAt;
    public final Boolean beforeReset;
    public final Basis basis;

    public Forecast(Instant hitsAt, Boolean beforeReset, Basis basis) {
        this.hitsAt = hitsAt;
        this.beforeReset = beforeReset;
        this.basis = basis;
    }

    static final Forecast NONE = new Forecast(null, null, null);

    @Override public String toString() {
        return "Forecast(" + hitsAt + ", " + beforeReset + ", " + basis + ")";
    }
}
