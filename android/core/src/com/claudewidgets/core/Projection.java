package com.claudewidgets.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** R5 (sesion) y R6 (semana). */
public final class Projection {

    private static final double FIVE_HOURS = 5 * 3600.0;
    private static final double SEVEN_DAYS = 7 * 86400.0;
    private static final double MIN_ELAPSED = 60.0;

    private Projection() {}

    /** R5. Los pasos se evaluan en orden: el primero que aplica decide. */
    public static Forecast session(double percent, Instant resetsAt, Instant now) {
        // 1. Sin reinicio o dato rancio: no se afirma nada.
        if (resetsAt == null || !now.isBefore(resetsAt)) return Forecast.NONE;
        // 2. Ya esta lleno. `beforeReset` es cierto porque el paso 1 descarto el dato rancio.
        if (percent >= 100) return new Forecast(now, Boolean.TRUE, Forecast.WINDOW);
        // 3. Ventana recien abierta o sin consumo: el ritmo no significa nada todavia.
        double elapsed = seconds(resetsAt.minusSeconds((long) FIVE_HOURS), now);
        if (percent <= 0 || elapsed < MIN_ELAPSED) return Forecast.NONE;
        // 4. Ritmo de la ventana.
        return at(now, percent, percent / elapsed, resetsAt, Forecast.WINDOW);
    }

    /** R6. */
    public static Forecast weekly(double percent, Instant resetsAt,
                                  List<Sample> samples, Instant now) {
        // 1.
        if (resetsAt == null || !now.isBefore(resetsAt)) return Forecast.NONE;

        // 2. Referencia de las ultimas 24 h, sobre las muestras ya depuradas por R3 paso 1.
        Sample ref = null;
        Instant since = now.minusSeconds(86400);
        for (Sample s : History.cleanSamples(samples, now)) {
            if (s.t.isBefore(since) || s.t.isAfter(now)) continue;
            if (!sameWindow(s.resetsAt, resetsAt)) continue;
            if (ref == null || s.t.isBefore(ref.t)) ref = s;
        }
        boolean rhythm24h = ref != null && seconds(ref.t, now) >= 3600.0;

        // 3. Lleno: gana sobre el ritmo, pero no sobre el dato rancio del paso 1.
        if (percent >= 100) {
            return new Forecast(now, Boolean.TRUE,
                    rhythm24h ? Forecast.RATE_24H : Forecast.WINDOW);
        }

        double rate;
        String basis;
        if (rhythm24h) {
            // 4.
            rate = (percent - ref.percent) / seconds(ref.t, now);
            basis = Forecast.RATE_24H;
            // Sin consumo o a la baja: no se proyecta, pero la base sigue siendo "24h".
            if (rate <= 0) return new Forecast(null, null, basis);
        } else {
            // 5.
            double elapsed = seconds(resetsAt.minusSeconds((long) SEVEN_DAYS), now);
            if (percent <= 0 || elapsed < MIN_ELAPSED) return Forecast.NONE;
            rate = percent / elapsed;
            basis = Forecast.WINDOW;
        }
        // 6.
        return at(now, percent, rate, resetsAt, basis);
    }

    /** R2: misma ventana si algun `resets_at` es nulo o distan menos de una hora. */
    static boolean sameWindow(Instant a, Instant b) {
        if (a == null || b == null) return true;
        return Math.abs(a.toEpochMilli() - b.toEpochMilli()) < 3600_000L;
    }

    private static Forecast at(Instant now, double percent, double rate,
                               Instant resetsAt, String basis) {
        double secondsToFull = (100 - percent) / rate;
        Instant hitsAt = now.plusNanos(Math.round(secondsToFull * 1e9));
        return new Forecast(hitsAt, hitsAt.isBefore(resetsAt), basis);
    }

    static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1e9;
    }
}
