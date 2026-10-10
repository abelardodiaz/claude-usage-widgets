package com.claudewidgets.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprueba que el nucleo de produccion carga y corre bajo ART, no solo en una JVM.
 * Sin E/S de archivos ni APIs posteriores a Java 8: lo que no corra aqui no corre en el telefono.
 */
public final class AndroidSmoke {
    public static void main(String[] args) throws Exception {
        String json = "{\"five_hour\":{\"utilization\":31,\"resets_at\":\"2026-10-02T19:19:59+00:00\"},"
                + "\"seven_day\":{\"utilization\":77,\"resets_at\":\"2026-10-02T23:59:59+00:00\"},"
                + "\"limits\":[{\"kind\":\"weekly_scoped\",\"percent\":28,\"resets_at\":null,"
                + "\"scope\":{\"model\":{\"display_name\":\"Fable\"}}}],"
                + "\"seven_day_breakdown\":{\"rows\":[{\"key\":\"chat\",\"display_name\":\"Chats\",\"percent\":40}]}}";
        UsageModel m = Parser.parse(json, Source.CLAUDE_AI);
        System.out.println("parse   : session=" + m.session.percent + " weekly=" + m.weekly.percent
                + " scoped=" + m.scoped.size() + " breakdown=" + m.breakdown.size());

        ZoneId tz = ZoneId.of("America/New_York");   // zona IANA real bajo ART
        Instant now = Instant.parse("2026-11-02T17:00:00Z");
        List<Sample> ss = new ArrayList<>();
        ss.add(new Sample(Instant.parse("2026-10-31T16:00:00Z"), 10, Instant.parse("2026-11-05T05:00:00Z")));
        ss.add(new Sample(now, 59, Instant.parse("2026-11-05T05:00:00Z")));
        DayUsage d = History.compute(new Window(59, Instant.parse("2026-11-05T05:00:00Z")), ss, now, tz);
        System.out.println("history : per_day=" + d.perDay + " hoy=" + d.todayUsed
                + " cuota=" + d.quotaToday);

        System.out.println("project : " + Projection.session(50,
                Instant.parse("2026-10-02T21:00:00Z"), Instant.parse("2026-10-02T18:00:00Z")));
        System.out.println("colors  : " + Colors.bar(85) + " " + Colors.today(0.5, -0.266667)
                + " " + Colors.todayFill(0.5, 0.0).state
                + " " + Colors.paceMark(Instant.parse("2026-10-10T06:00:00Z"), now));

        boolean ok = Math.abs(d.perDay.getOrDefault("2026-11-01", 0.0) - 25.0) < 0.001;
        System.out.println(ok ? "ART OK: el dia de 25 h se reparte bien" : "ART FALLA");
        if (!ok) System.exit(1);
    }
}
