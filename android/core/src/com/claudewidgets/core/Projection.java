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
        if (percent >= 100) return new Forecast(now, Boolean.TRUE, Basis.WINDOW);
        // 3. Ventana recien abierta o sin consumo: el ritmo no significa nada todavia.
        double elapsed = seconds(resetsAt.minusSeconds((long) FIVE_HOURS), now);
        if (percent <= 0 || elapsed < MIN_ELAPSED) return Forecast.NONE;
        // 4. Ritmo de la ventana.
        return at(now, percent, percent / elapsed, resetsAt, Basis.WINDOW);
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
                    rhythm24h ? Basis.RATE_24H : Basis.WINDOW);
        }

        double rate;
        Basis basis;
        if (rhythm24h) {
            // 4.
            rate = (percent - ref.percent) / seconds(ref.t, now);
            basis = Basis.RATE_24H;
            // Sin consumo o a la baja: no se proyecta, pero la base sigue siendo "24h".
            if (rate <= 0) return new Forecast(null, null, basis);
        } else {
            // 5.
            double elapsed = seconds(resetsAt.minusSeconds((long) SEVEN_DAYS), now);
            if (percent <= 0 || elapsed < MIN_ELAPSED) return Forecast.NONE;
            rate = percent / elapsed;
            basis = Basis.WINDOW;
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
                               Instant resetsAt, Basis basis) {
        double secondsToFull = (100 - percent) / rate;
        if (!Double.isFinite(secondsToFull)) return Forecast.NONE;
        // Se suma en segundos y nanos aparte: `plusNanos(round(s * 1e9))` saturaria el long y
        // daria una fecha falsa en silencio con ritmos minusculos.
        long whole = (long) secondsToFull;
        // A MILISEGUNDOS, no a nanosegundos (R0): los instantes se comparan a esa resolucion.
        // Con nanos, un `hits_at` a menos de medio milisegundo de `resets_at` daria un
        // `before_reset` distinto al de una implementacion que redondee a ms.
        //
        // NINGUN FIXTURE distingue este caso: haria falta uno cuyo `expected` booleano dependa
        // de la septima cifra significativa de un double, y eso seria mas fragil que util. La
        // regla vale porque hace coincidir a las dos implementaciones por construccion, no
        // porque una prueba lo verifique.
        long millis = Math.round((secondsToFull - whole) * 1000.0);
        Instant hitsAt;
        try {
            hitsAt = now.plusSeconds(whole).plusMillis(millis);
        } catch (RuntimeException e) {
            // Fuera del rango de Instant: no se puede decir cuando, como en los demas
            // casos imposibles de R5 y R6.
            return Forecast.NONE;
        }
        return new Forecast(hitsAt, hitsAt.isBefore(resetsAt), basis);
    }

    /**
     * Segundos entre dos instantes, en doble.
     *
     * No se usa {@code Duration.toNanos()}: desborda el {@code long} a partir de unos 292 anios,
     * y {@code "9999-12-31T23:59:59Z"} es un centinela habitual de "sin limite" que vendria en
     * una respuesta perfectamente valida. Un dato valido no puede tumbar el widget.
     */
    static double seconds(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return d.getSeconds() + d.getNano() / 1e9;
    }
}
