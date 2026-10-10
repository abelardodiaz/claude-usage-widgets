package com.claulimitswidgets.android;

import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.Forecast;
import com.claudewidgets.core.UsageModel;

import java.time.Instant;

/** Todo lo que el widget necesita para pintarse, ya calculado por el nucleo. */
public final class Snapshot {

    /**
     * Que mostrar cuando no hay numeros que mostrar. `CHOOSE_ORG` no es un fallo: es que hay
     * varias organizaciones y ninguna pista de cual mira el usuario, asi que elegir por el
     * seria mostrarle una cuota que no es la suya.
     */
    public enum Problem { NO_SESSION, AUTH_EXPIRED, BLOCKED, OFFLINE, BAD_FORMAT, CHOOSE_ORG,
                          LOADING }

    public final UsageModel model;
    public final DayUsage day;
    public final Forecast sessionForecast;
    public final Forecast weeklyForecast;
    public final Double paceMark;
    public final Instant fetchedAt;
    /** Null = todo bien. Si no, el widget muestra el aviso y, si los hay, los datos viejos. */
    public final Problem problem;

    public Snapshot(UsageModel model, DayUsage day, Forecast sessionForecast,
                    Forecast weeklyForecast, Double paceMark, Instant fetchedAt,
                    Problem problem) {
        this.model = model;
        this.day = day;
        this.sessionForecast = sessionForecast;
        this.weeklyForecast = weeklyForecast;
        this.paceMark = paceMark;
        this.fetchedAt = fetchedAt;
        this.problem = problem;
    }

    /** Un snapshot sin datos, solo con el problema. */
    public static Snapshot of(Problem p) {
        return new Snapshot(null, null, null, null, null, null, p);
    }

    /** El mismo dato, marcado con un problema nuevo: asi el widget sigue mostrando algo util. */
    public Snapshot withProblem(Problem p) {
        return new Snapshot(model, day, sessionForecast, weeklyForecast, paceMark, fetchedAt, p);
    }

    public boolean hasData() { return model != null; }
}
