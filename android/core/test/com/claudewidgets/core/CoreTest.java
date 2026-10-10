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

    /**
     * Centinela habitual de "sin limite". Ya no pasa el parseo (R1 lo anula), pero el nucleo
     * tampoco puede lanzar si le llega como Instant directo; antes tumbaba el widget.
     */
    private static final String LEJANO = "9999-12-31T23:59:59Z";

    public static void run(Assert a) {
        farFuture(a);
        weekBeforeMin(a);
        yearOutOfRange(a);
        strictFormat(a);
        parseWithoutCause(a);
    }

    /**
     * R0: forma canonica y nada mas. `OffsetDateTime.parse` acepta cosas que otras
     * implementaciones del contrato rechazan, y con `+00:00:30` ademas desplaza medio minuto.
     */
    private static void strictFormat(Assert a) {
        String[] malas = {
            "+002026-10-02T12:00:00Z",      // anio con signo
            "2026-10-02T12:00Z",            // sin segundos
            "2026-10-02T12:00:00+00",       // desplazamiento sin minutos
            "2026-10-02T12:00:00+00:00:30", // desplazamiento con segundos
            "2026-10-02T12:00:00.Z",        // fraccion vacia
            "2026-10-02 12:00:00Z",         // espacio en vez de T
            "2026-10-02T12:00:00Z[UTC]",    // anotacion de zona
            "2026-06-30T23:59:60Z",         // segundo 60
            "2026-10-02T12:00:00",          // sin desplazamiento
            "26-10-02T12:00:00Z",           // anio de dos digitos
            "2026-10-02T12:00:00.1234567890Z", // fraccion de 10 digitos
            "2026-10-02t12:00:00z",         // minusculas: la regex es sensible
            "2026-10-02T12:00:00+19:00",    // desplazamiento mayor que +-18:00
            "2026-10-02T12:00:00-23:59",
        };
        for (String m : malas) a.eq("se rechaza " + m, null, Parser.instant(m));

        String[] buenas = {
            "2026-10-02T12:00:00Z",
            "2026-10-02T12:00:00.123Z",
            "2026-10-02T12:00:00.614725-06:00",
            "2026-10-02T12:00:00+05:30",
            "2026-10-02T12:00:00.123456789Z",  // nueve digitos: el maximo
            "2026-10-02T12:00:00+18:00",       // el limite, valido
            "2026-10-02T12:00:00-18:00",
        };
        for (String b : buenas) a.isTrue("se acepta " + b, Parser.instant(b) != null);
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

        // Con resets_at en el anio 9999 el inicio de la ventana (resets_at - 5 h) queda en el
        // futuro: `elapsed` sale NEGATIVO y cae en `elapsed < 60 s`, asi que no proyecta.
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

    /**
     * `resets_at - 7 dias` por debajo de Instant.MIN no lanza: devuelve null, como el
     * `checked_sub` de Rust, y History cae a `a.t` (R3) o a medianoche (R4).
     */
    private static void weekBeforeMin(Assert a) {
        a.eq("minusWeek(Instant.MIN) es null", null, History.minusWeek(Instant.MIN));
        a.eq("minusWeek(MIN + 7 dias - 1 s) es null", null,
                History.minusWeek(Instant.MIN.plusSeconds(7 * 86400 - 1)));
        a.eq("minusWeek(MIN + 7 dias) es MIN", Instant.MIN,
                History.minusWeek(Instant.MIN.plusSeconds(7 * 86400)));
        a.eq("minusWeek normal resta 7 dias", Instant.parse("2026-10-09T20:00:00Z"),
                History.minusWeek(Instant.parse("2026-10-16T20:00:00Z")));
    }

    /** RFC 3339 solo admite cuatro digitos de anio; OffsetDateTime acepta hasta +-999999999. */
    private static void yearOutOfRange(Assert a) {
        a.eq("anio de 9 digitos se degrada a null", null,
                Parser.instant("+999999999-12-31T23:59:59Z"));
        a.eq("anio negativo enorme se degrada a null", null,
                Parser.instant("-999999999-01-01T00:00:00Z"));
        a.eq("el anio 9999 (centinela de sin limite) es nulo, R1", null, Parser.instant(LEJANO));
        a.isTrue("el anio 9998 si se acepta", Parser.instant("9998-12-31T23:59:59Z") != null);
        a.isTrue("un anio normal se acepta", Parser.instant("2026-10-03T00:00:00Z") != null);
    }

    /**
     * Parser.parse no encadena la causa del fallo de parseo: el mensaje de JsonException lleva un
     * fragmento de la entrada y los cuerpos de respuesta no se registran nunca.
     */
    private static void parseWithoutCause(Assert a) {
        // Control: Json.parse por su cuenta SI mete el literal en su mensaje. Si esto dejara de
        // cumplirse, la comprobacion de getCause() == null pasaria por el motivo equivocado.
        String jsonMsg = "<<no lanzo>>";
        try {
            Json.parse("[1e999]");
        } catch (Json.JsonException e) {
            jsonMsg = String.valueOf(e.getMessage());
        }
        a.isTrue("control: JsonException lleva 1e999 en su mensaje", jsonMsg.contains("1e999"));

        for (Source src : Source.values()) {
            Throwable t = null;
            try {
                Parser.parse("[1e999]", src);
            } catch (Exception e) {
                t = e;
            }
            a.isTrue("[1e999] " + src + ": lanza UnrecognizedFormatException",
                    t instanceof UnrecognizedFormatException);
            a.isTrue("[1e999] " + src + ": getCause() es null", t != null && t.getCause() == null);
            a.isTrue("[1e999] " + src + ": el mensaje no lleva el literal",
                    t != null && !String.valueOf(t.getMessage()).contains("1e999"));
        }
    }
}
