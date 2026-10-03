package com.claudewidgets.core;

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
 * desde API 26 con la superficie de Java 8, y este nucleo tiene que correr con minSdk 29.
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

    public static DayUsage compute(Bar weekly, List<Sample> samples, Instant now, ZoneId tz) {
        List<Sample> clean = cleanSamples(samples, now);

        // R3 pasos 2 y 3.
        Map<String, Double> perDay = new TreeMap<>();
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
                Instant windowStart = b.resetsAt.minusSeconds((long) SEVEN_DAYS_SECONDS);
                start = a.t.isAfter(windowStart) ? a.t : windowStart;
                if (start.isAfter(b.t)) start = b.t;
            }
            if (delta <= 0) continue;
            spread(perDay, start, b.t, delta, tz);
        }
        perDay.values().removeIf(v -> v <= 0);

        LocalDate today = now.atZone(tz).toLocalDate();
        double todayUsed = perDay.getOrDefault(today.toString(), 0.0);

        // R4: `partial` es estricto, una muestra exactamente a medianoche no cuenta como anterior.
        Instant midnight = today.atStartOfDay(tz).toInstant();
        boolean partial = true;
        for (Sample s : clean) {
            if (s.t.isBefore(midnight)) { partial = false; break; }
        }

        Double quotaToday = null;
        if (weekly.resetsAt != null && now.isBefore(weekly.resetsAt)) {
            double base = Math.max(weekly.percent - todayUsed, 0);
            double daysLeft = Projection.seconds(midnight, weekly.resetsAt) / 86400.0;
            quotaToday = (100 - base) / Math.max(daysLeft, 1);
        }
        return new DayUsage(perDay, todayUsed, quotaToday, partial);
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
