package com.claudewidgets.core;

import java.time.Instant;

/**
 * Resultado de R5 y R6. Cuando no se puede proyectar, `hitsAt` y `beforeReset` son nulos; `basis`
 * NO siempre: sin consumo o a la baja en el ritmo de 24 h (paso 4) trae {@code RATE_24H} (la base
 * sigue siendo "24h"), y solo es nulo en {@link #NONE}.
 */
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
