package com.claudewidgets.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R1 (OAuth de Claude Code) y R1b (claude.ai). Las dos respuestas tienen la misma forma, asi que
 * es el mismo codigo con distinto {@link Source}.
 *
 * El parseo es deliberadamente defensivo: el endpoint no esta documentado y puede cambiar. La
 * unica condicion que aborta es que falte lo esencial (sesion o semana con su porcentaje); todo
 * lo demas se degrada a nulo o se omite, nunca se inventa.
 */
public final class Parser {

    private Parser() {}

    public static UsageModel parse(String json, Source source) throws UnrecognizedFormatException {
        Object root;
        try {
            root = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new UnrecognizedFormatException("la respuesta no es JSON valido", e);
        }
        return parse(root, source);
    }

    /** Misma regla sobre un arbol ya leido, para quien ya tiene la respuesta parseada. */
    public static UsageModel parse(Object root, Source source) throws UnrecognizedFormatException {
        if (!(root instanceof Map)) {
            throw new UnrecognizedFormatException("la respuesta no es un objeto JSON");
        }
        Map<?, ?> o = (Map<?, ?>) root;
        return new UsageModel(
                source,
                window(o, "five_hour"),
                window(o, "seven_day"),
                scoped(o.get("limits")),
                breakdown(o.get("seven_day_breakdown")));
    }

    /** Sesion y semana son obligatorias: si falta su `utilization` numerica, se aborta (R1). */
    private static Bar window(Map<?, ?> root, String key) throws UnrecognizedFormatException {
        Object raw = root.get(key);
        if (!(raw instanceof Map)) {
            throw new UnrecognizedFormatException("'" + key + "' no es un objeto");
        }
        Object util = ((Map<?, ?>) raw).get("utilization");
        if (!(util instanceof Double)) {
            throw new UnrecognizedFormatException("'" + key + ".utilization' no es un numero");
        }
        return new Bar((Double) util, instant(((Map<?, ?>) raw).get("resets_at")));
    }

    private static List<ScopedLimit> scoped(Object raw) {
        List<ScopedLimit> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String kind = nonEmpty(m.get("kind"));
            if (kind == null || "session".equals(kind) || "weekly_all".equals(kind)) continue;
            Object percent = m.get("percent");
            if (!(percent instanceof Double)) continue;
            out.add(new ScopedLimit(label(m.get("scope"), kind), (Double) percent,
                    instant(m.get("resets_at"))));
        }
        return out;
    }

    /** Etiqueta: modelo, si no superficie, si no el propio `kind`. Vacia cuenta como ausente. */
    private static String label(Object scope, String fallback) {
        if (scope instanceof Map) {
            Map<?, ?> s = (Map<?, ?>) scope;
            String model = displayName(s.get("model"));
            if (model != null) return model;
            String surface = displayName(s.get("surface"));
            if (surface != null) return surface;
        }
        return fallback;
    }

    private static String displayName(Object node) {
        return node instanceof Map ? nonEmpty(((Map<?, ?>) node).get("display_name")) : null;
    }

    private static List<BreakdownRow> breakdown(Object raw) {
        List<BreakdownRow> out = new ArrayList<>();
        if (!(raw instanceof Map)) return out;
        Object rows = ((Map<?, ?>) raw).get("rows");
        if (!(rows instanceof List)) return out;
        for (Object item : (List<?>) rows) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            if (!(m.get("key") instanceof String)) continue;
            Object percent = m.get("percent");
            if (!(percent instanceof Double)) continue;
            String key = (String) m.get("key");
            String name = nonEmpty(m.get("display_name"));
            out.add(new BreakdownRow(key, name != null ? name : key, (Double) percent));
        }
        return out;
    }

    /** Cadena no vacia, o nulo. Una cadena vacia cuenta como ausente (R1). */
    private static String nonEmpty(Object v) {
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.isEmpty() ? null : s;
    }

    /** RFC 3339 con desplazamiento. Lo que no se pueda leer se degrada a nulo (R1). */
    static Instant instant(Object v) {
        if (!(v instanceof String)) return null;
        try {
            return OffsetDateTime.parse((String) v).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
