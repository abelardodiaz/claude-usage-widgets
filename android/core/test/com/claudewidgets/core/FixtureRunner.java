package com.claudewidgets.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Corre las cuatro familias de `spec/fixtures/` contra el nucleo. Los fixtures son la verdad:
 * si uno no pasa, se corrige la implementacion.
 */
public final class FixtureRunner {

    /** Tolerancias de R0. */
    static final double NUM_TOL = 0.001;
    static final long INSTANT_TOL_MS = 1000;

    private FixtureRunner() {}

    /**
     * Minimo de fixtures que deben existir. Sin esto, borrar `spec/fixtures/` entero dejaria el
     * corredor en verde con cero comprobaciones: una suite que no contrasta nada no falla nunca.
     * Se sube cuando el contrato crece.
     */
    static final int MIN_TOTAL = 65;

    public static void run(Assert a, Path fixtures) {
        int n = 0;
        n += family(a, fixtures.resolve("parse"), FixtureRunner::parseCase);
        n += family(a, fixtures.resolve("history"), FixtureRunner::historyCase);
        n += family(a, fixtures.resolve("projection"), FixtureRunner::projectionCase);
        n += family(a, fixtures.resolve("colors"), FixtureRunner::colorCase);
        System.out.println("  fixtures: " + n + " contrastados");
        if (n < MIN_TOTAL) {
            a.fail("solo se contrastaron " + n + " fixtures; se esperaban al menos " + MIN_TOTAL);
        }
    }

    // ------------------------------------------------------------------- parseo

    private static void parseCase(Assert a, String name, Map<?, ?> fx) {
        Source source = Source.of((String) fx.get("source"));
        Map<?, ?> expected = (Map<?, ?>) fx.get("expected");
        Object expectedError = expected.get("error");

        UsageModel m;
        try {
            m = Parser.parse(fx.get("input"), source);
        } catch (UnrecognizedFormatException e) {
            if (!"unrecognized_format".equals(expectedError)) {
                a.fail(name + ": no se esperaba error, pero fallo con \"" + e.getMessage() + "\"");
            }
            return;
        }
        if (expectedError != null) {
            a.fail(name + ": se esperaba el error '" + expectedError + "' y no se produjo");
            return;
        }

        a.eq(name + ".source", expected.get("source"), m.source.toString());
        window(a, name + ".session", (Map<?, ?>) expected.get("session"), m.session);
        window(a, name + ".weekly", (Map<?, ?>) expected.get("weekly"), m.weekly);

        List<?> es = (List<?>) expected.get("scoped");
        a.eq(name + ".scoped.tamanio", es.size(), m.scoped.size());
        for (int i = 0; i < Math.min(es.size(), m.scoped.size()); i++) {
            Map<?, ?> e = (Map<?, ?>) es.get(i);
            ScopedLimit g = m.scoped.get(i);
            a.eq(name + ".scoped[" + i + "].label", e.get("label"), g.label);
            a.near(name + ".scoped[" + i + "].percent", (Double) e.get("percent"), g.percent, NUM_TOL);
            instant(a, name + ".scoped[" + i + "].resets_at", e.get("resets_at"), g.resetsAt);
        }

        List<?> eb = (List<?>) expected.get("breakdown");
        a.eq(name + ".breakdown.tamanio", eb.size(), m.breakdown.size());
        for (int i = 0; i < Math.min(eb.size(), m.breakdown.size()); i++) {
            Map<?, ?> e = (Map<?, ?>) eb.get(i);
            BreakdownRow g = m.breakdown.get(i);
            a.eq(name + ".breakdown[" + i + "].key", e.get("key"), g.key);
            a.eq(name + ".breakdown[" + i + "].label", e.get("label"), g.label);
            a.near(name + ".breakdown[" + i + "].percent", (Double) e.get("percent"), g.percent, NUM_TOL);
        }
    }

    // ----------------------------------------------------------------- historial

    private static void historyCase(Assert a, String name, Map<?, ?> fx) {
        Map<?, ?> in = (Map<?, ?>) fx.get("input");
        Map<?, ?> exp = (Map<?, ?>) fx.get("expected");
        Map<?, ?> w = (Map<?, ?>) in.get("weekly");
        Window weekly = new Window((Double) w.get("percent"), Parser.instant(w.get("resets_at")));
        Instant now = Parser.instant(in.get("now"));
        ZoneId tz = ZoneId.of((String) in.get("tz"));

        DayUsage d = History.compute(weekly, samples(in.get("samples")), now, tz);

        Map<?, ?> expDays = (Map<?, ?>) exp.get("per_day");
        Set<String> keys = new TreeSet<>();
        for (Object k : expDays.keySet()) keys.add((String) k);
        keys.addAll(d.perDay.keySet());
        for (String k : keys) {
            // Una entrada con 0 equivale a ausente (R4).
            double want = expDays.get(k) == null ? 0.0 : (Double) expDays.get(k);
            double got = d.perDay.getOrDefault(k, 0.0);
            a.near(name + ".per_day[" + k + "]", want, got, NUM_TOL);
        }
        a.near(name + ".today_used", (Double) exp.get("today_used"), d.todayUsed, NUM_TOL);
        Double wantQuota = (Double) exp.get("quota_today");
        if (wantQuota == null || d.quotaToday == null) {
            a.eq(name + ".quota_today", wantQuota, d.quotaToday);
        } else {
            a.near(name + ".quota_today", wantQuota, d.quotaToday, NUM_TOL);
        }
        a.eq(name + ".partial", exp.get("partial"), d.partial);
    }

    // ---------------------------------------------------------------- proyeccion

    private static void projectionCase(Assert a, String name, Map<?, ?> fx) {
        Map<?, ?> in = (Map<?, ?>) fx.get("input");
        Map<?, ?> exp = (Map<?, ?>) fx.get("expected");
        Instant now = Parser.instant(in.get("now"));
        Instant resetsAt = Parser.instant(in.get("resets_at"));
        double percent = (Double) in.get("percent");

        Forecast f = "session".equals(in.get("kind"))
                ? Projection.session(percent, resetsAt, now)
                : Projection.weekly(percent, resetsAt, samples(in.get("samples")), now);

        instant(a, name + ".hits_at", exp.get("hits_at"), f.hitsAt);
        a.eq(name + ".before_reset", exp.get("before_reset"), f.beforeReset);
        a.eq(name + ".basis", exp.get("basis"), f.basis == null ? null : f.basis.toString());
    }

    private static List<Sample> samples(Object raw) {
        List<Sample> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<?, ?> m = (Map<?, ?>) o;
            out.add(new Sample(Parser.instant(m.get("t")), (Double) m.get("percent"),
                    Parser.instant(m.get("resets_at"))));
        }
        return out;
    }

    // ------------------------------------------------------------------- colores

    private static void colorCase(Assert a, String name, Map<?, ?> fx) {
        Map<?, ?> in = (Map<?, ?>) fx.get("input");
        Map<?, ?> exp = (Map<?, ?>) fx.get("expected");
        String bar = (String) in.get("bar");
        if ("pace_mark".equals(bar)) {
            Double got = Colors.paceMark(Parser.instant(in.get("resets_at")),
                    Parser.instant(in.get("now")));
            Double want = (Double) exp.get("mark");
            if (want == null || got == null) {
                a.eq(name + ".mark", want, got);
            } else {
                a.near(name + ".mark", want, got, NUM_TOL);
            }
            return;
        }
        Color got;
        if ("today".equals(bar)) {
            got = Colors.today((Double) in.get("today_used"), (Double) in.get("quota_today"));
        } else {
            got = Colors.bar((Double) in.get("percent"));
        }
        a.eq(name + ".color", exp.get("color"), got.toString());
    }

    private static void window(Assert a, String what, Map<?, ?> expected, Window got) {
        a.near(what + ".percent", (Double) expected.get("percent"), got.percent, NUM_TOL);
        instant(a, what + ".resets_at", expected.get("resets_at"), got.resetsAt);
    }

    // -------------------------------------------------------------------- utiles

    /** Compara instantes como instantes, no como texto (R0), con 1 s de tolerancia. */
    static void instant(Assert a, String what, Object expectedText, Instant got) {
        if (expectedText == null) {
            a.eq(what, null, got);
            return;
        }
        Instant expected = Parser.instant(expectedText);
        if (expected == null) {
            // Que un fixture traiga un instante ilegible no puede pasar por "se esperaba null":
            // coincidiria por casualidad y taparia el error del fixture.
            a.fail(what + ": el fixture trae un instante invalido <" + expectedText + ">");
            return;
        }
        if (got == null) {
            a.fail(what + ": esperado <" + expected + "> obtenido <null>");
            return;
        }
        long diff = Math.abs(expected.toEpochMilli() - got.toEpochMilli());
        if (diff > INSTANT_TOL_MS) {
            a.fail(what + ": esperado <" + expected + "> obtenido <" + got + ">");
        } else {
            a.isTrue(what, true);
        }
    }

    interface Case { void check(Assert a, String name, Map<?, ?> fixture); }

    private static int family(Assert a, Path dir, Case body) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("no se pudo leer " + dir, e);
        }
        if (files.isEmpty()) {
            a.fail("la familia " + dir.getFileName() + " no tiene ningun fixture");
            return 0;
        }
        for (Path f : files) {
            String name = dir.getFileName() + "/" + f.getFileName();
            Map<?, ?> fx;
            try {
                fx = (Map<?, ?>) Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("no se pudo leer " + f, e);
            }
            body.check(a, name, fx);
        }
        return files.size();
    }
}
