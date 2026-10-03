package com.claudewidgets.core;

import java.time.Instant;

/** Resultado de R5 y R6. Los tres campos son nulos cuando no se puede proyectar. */
public final class Forecast {
    /** Base del calculo: "window", "24h" o nulo. */
    public static final String WINDOW = "window";
    public static final String RATE_24H = "24h";

    public final Instant hitsAt;
    public final Boolean beforeReset;
    public final String basis;

    public Forecast(Instant hitsAt, Boolean beforeReset, String basis) {
        this.hitsAt = hitsAt;
        this.beforeReset = beforeReset;
        this.basis = basis;
    }

    static final Forecast NONE = new Forecast(null, null, null);

    @Override public String toString() {
        return "Forecast(" + hitsAt + ", " + beforeReset + ", " + basis + ")";
    }
}
