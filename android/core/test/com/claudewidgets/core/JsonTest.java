package com.claudewidgets.core;

import java.util.List;
import java.util.Map;

/**
 * Pruebas del lector JSON. Recibe datos de la red, asi que lo que importa aqui no es
 * el camino feliz sino que lo roto falle de forma controlada.
 */
public final class JsonTest {

    public static void run(Assert a) {
        basics(a);
        strings(a);
        numbers(a);
        broken(a);
        limits(a);
    }

    private static void basics(Assert a) {
        a.eq("objeto vacio", 0, ((Map<?, ?>) Json.parse("{}")).size());
        a.eq("arreglo vacio", 0, ((List<?>) Json.parse("[]")).size());
        a.eq("null", null, Json.parse("null"));
        a.eq("true", Boolean.TRUE, Json.parse("true"));
        a.eq("false", Boolean.FALSE, Json.parse("false"));
        a.eq("anidado", "z", Json.parse("{\"a\":{\"b\":[1,\"z\"]}}") instanceof Map
                ? ((List<?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse("{\"a\":{\"b\":[1,\"z\"]}}"))
                        .get("a")).get("b")).get(1) : null);
        a.eq("espacios alrededor", Boolean.TRUE, Json.parse("  \t\r\n true \n "));
        // Claves duplicadas: gana la ultima.
        a.eq("clave duplicada gana la ultima", 2.0,
                ((Map<?, ?>) Json.parse("{\"k\":1,\"k\":2}")).get("k"));
    }

    private static void strings(Assert a) {
        a.eq("escapes simples", "\"\\/\b\f\n\r\t",
                Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
        a.eq("escape unicode", "A", Json.parse("\"\\u0041\""));
        a.eq("par sustituto", "\uD83D\uDE00", Json.parse("\"\\uD83D\\uDE00\""));
        a.eq("acentos directos", "ñé", Json.parse("\"ñé\""));
        a.throwsOf("control sin escapar", Json.JsonException.class,
                () -> Json.parse("\"a\u0001b\""));
        a.throwsOf("escape desconocido", Json.JsonException.class, () -> Json.parse("\"\\x\""));
        a.throwsOf("unicode incompleto", Json.JsonException.class, () -> Json.parse("\"\\u00\""));
        a.throwsOf("cadena sin cerrar", Json.JsonException.class, () -> Json.parse("\"abc"));
    }

    private static void numbers(Assert a) {
        a.eq("entero", 0.0, Json.parse("0"));
        a.eq("negativo", -17.0, Json.parse("-17"));
        a.eq("decimal", 101.5, Json.parse("101.5"));
        a.eq("exponente", 1200.0, Json.parse("1.2e3"));
        a.eq("exponente negativo", 0.0012, Json.parse("1.2e-3"));
        // Enteros grandes que caben en double sin perder valor.
        a.eq("entero grande exacto", 9007199254740992.0, Json.parse("9007199254740992"));
        a.throwsOf("cero a la izquierda", Json.JsonException.class, () -> Json.parse("01"));
        a.throwsOf("punto sin decimales", Json.JsonException.class, () -> Json.parse("1."));
        a.throwsOf("solo signo", Json.JsonException.class, () -> Json.parse("-"));
        a.throwsOf("NaN", Json.JsonException.class, () -> Json.parse("NaN"));
        a.throwsOf("Infinity", Json.JsonException.class, () -> Json.parse("Infinity"));
        a.throwsOf("desbordamiento a Infinity", Json.JsonException.class,
                () -> Json.parse("1e400"));
    }

    private static void broken(Assert a) {
        a.throwsOf("vacio", Json.JsonException.class, () -> Json.parse(""));
        a.throwsOf("nulo", Json.JsonException.class, () -> Json.parse(null));
        a.throwsOf("truncado", Json.JsonException.class, () -> Json.parse("{\"a\":"));
        a.throwsOf("coma sobrante en objeto", Json.JsonException.class,
                () -> Json.parse("{\"a\":1,}"));
        a.throwsOf("coma sobrante en arreglo", Json.JsonException.class,
                () -> Json.parse("[1,]"));
        a.throwsOf("basura al final", Json.JsonException.class, () -> Json.parse("{} x"));
        a.throwsOf("clave sin comillas", Json.JsonException.class, () -> Json.parse("{a:1}"));
        a.throwsOf("falta dos puntos", Json.JsonException.class, () -> Json.parse("{\"a\" 1}"));
        a.throwsOf("corchete cruzado", Json.JsonException.class, () -> Json.parse("{\"a\":1]"));
    }

    private static void limits(Assert a) {
        StringBuilder hondo = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH + 5; i++) hondo.append('[');
        a.throwsOf("demasiado anidamiento", Json.JsonException.class,
                () -> Json.parse(hondo.toString()));

        // Justo en el limite si debe entrar.
        StringBuilder justo = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH; i++) justo.append('[');
        for (int i = 0; i < Json.MAX_DEPTH; i++) justo.append(']');
        a.isTrue("anidamiento en el limite entra", Json.parse(justo.toString()) instanceof List);

        char[] grande = new char[Json.MAX_INPUT + 1];
        java.util.Arrays.fill(grande, ' ');
        a.throwsOf("entrada demasiado grande", Json.JsonException.class,
                () -> Json.parse(new String(grande)));
    }
}
