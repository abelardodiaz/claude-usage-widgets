package com.claudewidgets.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Pruebas de regresion que los fixtures no cubren porque no son reglas del contrato, sino
 * defensa contra entradas validas pero extremas.
 */
public final class CoreTest {

    /** Centinela habitual de "sin limite" en una respuesta: valido, y antes tumbaba el widget. */
    private static final String LEJANO = "9999-12-31T23:59:59Z";

    public static void run(Assert a) {
        farFuture(a);
        yearOutOfRange(a);
    }

    /**
     * Un `resets_at` lejano pero valido no puede lanzar. Antes, `Duration.toNanos()` desbordaba
     * el long a partir de ~292 anios y salia ArithmeticException por los cuatro caminos.
     */
    private static void farFuture(Assert a) {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        Instant lejos = Instant.parse(LEJANO);

        a.isTrue("session con resets lejano no lanza",
                Projection.session(50, lejos, now) != null);
        a.isTrue("weekly con resets lejano no lanza",
                Projection.weekly(50, lejos, new ArrayList<>(), now) != null);

        // La ventana lleva abierta siglos, asi que `elapsed` es enorme y el ritmo no significa
        // nada: ninguna de las dos proyecta.
        a.eq("session lejana no proyecta", null, Projection.session(50, lejos, now).hitsAt);

        a.eq("paceMark con resets lejano se acota a 0", 0.0, Colors.paceMark(lejos, now));

        List<Sample> ss = new ArrayList<>();
        ss.add(new Sample(Instant.parse("2026-10-02T00:00:00Z"), 10, lejos));
        ss.add(new Sample(now, 20, lejos));
        DayUsage d = History.compute(new Window(20, lejos), ss, now, ZoneId.of("America/New_York"));
        a.near("History con resets lejano reparte igual", 10.0,
                d.perDay.values().stream().mapToDouble(Double::doubleValue).sum(), 0.001);
        a.isTrue("History con resets lejano da cuota", d.quotaToday != null);
    }

    /** RFC 3339 solo admite cuatro digitos de anio; OffsetDateTime acepta hasta +-999999999. */
    private static void yearOutOfRange(Assert a) {
        a.eq("anio de 9 digitos se degrada a null", null,
                Parser.instant("+999999999-12-31T23:59:59Z"));
        a.eq("anio negativo enorme se degrada a null", null,
                Parser.instant("-999999999-01-01T00:00:00Z"));
        a.isTrue("el anio 9999 si se acepta", Parser.instant(LEJANO) != null);
        a.isTrue("un anio normal se acepta", Parser.instant("2026-10-03T00:00:00Z") != null);
    }
}
