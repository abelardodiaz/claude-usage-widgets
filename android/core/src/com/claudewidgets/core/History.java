package com.claudewidgets.core;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * R2, R3 y R4: cuanto se consumio cada dia local y cuanto toca hoy.
 *
 * Nota de portabilidad: se usa {@code instant.atZone(tz).toLocalDate()} y no
 * {@code LocalDate.ofInstant(instant, tz)}, que es de Java 9. Android trae {@code java.time}
 * desde API 26 con la superficie de Java 8, y este nucleo tiene que correr con minSdk 31.
 */
public final class History {

    private static final double SEVEN_DAYS_SECONDS = 7 * 86400.0;

    private History() {}

    /**
     * R3 paso 1: fuera las muestras futuras, orden ascendente por `t`, y de las que comparten
     * `t` se conserva solo la ultima en el orden de entrada.
     */
    public static List<Sample> cleanSamples(List<Sample> samples, Instant now) {
        Map<Instant, Sample> byInstant = new LinkedHashMap<>();
        for (Sample s : samples) {
            if (s.t.isAfter(now)) continue;
            byInstant.put(s.t, s);   // la ultima con ese `t` pisa a las anteriores
        }
        List<Sample> out = new ArrayList<>(byInstant.values());
        out.sort(Comparator.comparing(s -> s.t));
        return out;
    }

    public static DayUsage compute(Window weekly, List<Sample> samples, Instant now, ZoneId tz) {
        List<Sample> clean = cleanSamples(samples, now);

        // R3 pasos 2 y 3.
        List<Contribution> contribs = new ArrayList<>();
        for (int i = 1; i < clean.size(); i++) {
            Sample a = clean.get(i - 1);
            Sample b = clean.get(i);
            double delta;
            Instant start;
            if (Projection.sameWindow(a.resetsAt, b.resetsAt)) {
                delta = b.percent - a.percent;
                start = a.t;
            } else {
                // Hubo reinicio: lo de `b` se consumio dentro de su propia ventana.
                delta = b.percent;
                Instant windowStart = minusWeek(b.resetsAt);
                // Sin inicio representable (desborda), como en Rust: inicio = a.t.
                start = windowStart == null || !windowStart.isAfter(a.t) ? a.t : windowStart;
                if (start.isAfter(b.t)) start = b.t;
            }
            if (delta <= 0) continue;
            contribs.add(new Contribution(start, b.t, delta, b.resetsAt));
        }
        Map<String, Double> perDay = new TreeMap<>();
        for (Contribution c : contribs) spread(perDay, c.start, c.end, c.delta, tz);
        perDay.values().removeIf(v -> v <= 0);

        LocalDate today = now.atZone(tz).toLocalDate();
        Instant midnight = today.atStartOfDay(tz).toInstant();
        boolean fresh = weekly.resetsAt != null && now.isBefore(weekly.resetsAt);

        // R4: si la ventana actual empezo hoy (medianoche < resets_at - 7 dias <= now), "hoy"
        // empieza en el reinicio y solo cuenta el consumo de esa ventana. Si no, a medianoche
        // y today_used = per_day[hoy], como siempre. per_day no cambia en ningun caso.
        Instant dayStart = midnight;
        double todayUsed = perDay.getOrDefault(today.toString(), 0.0);
        if (fresh) {
            Instant windowStart = minusWeek(weekly.resetsAt);
            // Sin inicio representable (desborda), como en Rust: day_start = medianoche.
            // R0: a milisegundos con piso, como same_window y before_reset.
            long startMs = windowStart == null ? Long.MIN_VALUE : windowStart.toEpochMilli();
            if (startMs > midnight.toEpochMilli() && startMs <= now.toEpochMilli()) {
                dayStart = windowStart;
                todayUsed = usedSince(contribs, dayStart, weekly.resetsAt);
            }
        }

        // R4: `partial` es estricto, una muestra exactamente en day_start no cuenta como anterior.
        boolean partial = true;
        for (Sample s : clean) {
            if (s.t.toEpochMilli() < dayStart.toEpochMilli()) { partial = false; break; }
        }

        Double quotaToday = null;
        if (fresh) {
            double base = Math.max(weekly.percent - todayUsed, 0);
            double daysLeft = Projection.seconds(dayStart, weekly.resetsAt) / 86400.0;
            quotaToday = (100 - base) / Math.max(daysLeft, 1);
        }
        return new DayUsage(perDay, todayUsed, quotaToday, partial);
    }

    /**
     * `resetsAt - 7 dias`, o null si no es representable (por debajo de {@link Instant#MIN}).
     * Simetrico al `checked_sub` de Rust: `minusSeconds` lanzaria DateTimeException.
     */
    static Instant minusWeek(Instant resetsAt) {
        try {
            return resetsAt.minusSeconds((long) SEVEN_DAYS_SECONDS);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** R3 paso 2: aporte de un par con delta > 0, consumido en [start, end]; resetsAt es el de b. */
    private static final class Contribution {
        final Instant start;
        final Instant end;
        final double delta;
        final Instant resetsAt;

        Contribution(Instant start, Instant end, double delta, Instant resetsAt) {
            this.start = start;
            this.end = end;
            this.delta = delta;
            this.resetsAt = resetsAt;
        }
    }

    /**
     * R4: consumo de la ventana de `weekly` desde `dayStart`. Solo cuentan los aportes cuyo `b`
     * esta en esa ventana (R2), y de cada uno la parte de [start, end] posterior a `dayStart`.
     * Un intervalo de duracion 0 aporta entero si `end >= dayStart` y nada si no.
     */
    private static double usedSince(List<Contribution> contribs, Instant dayStart,
                                    Instant weeklyResetsAt) {
        double used = 0;
        for (Contribution c : contribs) {
            if (!Projection.sameWindow(c.resetsAt, weeklyResetsAt)) continue;
            double total = Projection.seconds(c.start, c.end);
            if (total <= 0) {
                if (c.end.toEpochMilli() >= dayStart.toEpochMilli()) used += c.delta;
                continue;
            }
            Instant from = c.start.isAfter(dayStart) ? c.start : dayStart;
            double inside = Math.max(Projection.seconds(from, c.end), 0);
            used += c.delta * inside / total;
        }
        return used;
    }

    /**
     * Reparte `delta` entre los dias locales que toca el intervalo, proporcional al tiempo real
     * que cae en cada uno. Un dia con cambio de horario dura 23 o 25 h y eso se respeta solo,
     * porque se mide con instantes y no con horas de reloj.
     */
    private static void spread(Map<String, Double> perDay, Instant from, Instant to,
                               double delta, ZoneId tz) {
        double total = Projection.seconds(from, to);
        if (total <= 0) {
            add(perDay, to.atZone(tz).toLocalDate(), delta);
            return;
        }
        LocalDate day = from.atZone(tz).toLocalDate();
        LocalDate last = to.atZone(tz).toLocalDate();
        while (!day.isAfter(last)) {
            Instant dayStart = day.atStartOfDay(tz).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(tz).toInstant();
            Instant lo = dayStart.isAfter(from) ? dayStart : from;
            Instant hi = dayEnd.isBefore(to) ? dayEnd : to;
            if (hi.isAfter(lo)) add(perDay, day, delta * Projection.seconds(lo, hi) / total);
            day = day.plusDays(1);
        }
    }

    private static void add(Map<String, Double> perDay, LocalDate day, double amount) {
        perDay.merge(day.toString(), amount, Double::sum);
    }
}
