package com.claudewidgets.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector JSON minimo, sin dependencias, para que el mismo codigo corra en Android y en una JVM.
 *
 * Lee datos que vienen de la red, asi que lo que importa no es el camino feliz sino que lo roto
 * falle de forma controlada: nunca StackOverflowError ni OutOfMemoryError, siempre
 * {@link JsonException}, que el parseo traduce a {@code unrecognized_format}.
 *
 * Decisiones fijadas (no son arbitrarias, se prueban en JsonTest):
 * <ul>
 *   <li>Claves duplicadas: gana la ultima.
 *   <li>Numeros: siempre {@code Double}. Se rechazan NaN e infinitos, incluido el desbordamiento
 *       de un literal demasiado grande.
 *   <li>Cadenas: escapes completos con {@code \\uXXXX} y pares sustitutos. Los caracteres de
 *       control sin escapar se rechazan, como manda RFC 8259.
 * </ul>
 *
 * Valores devueltos: {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Double}, {@code Boolean} o {@code null}.
 */
public final class Json {

    /** Profundidad maxima de anidamiento. Por encima, error en vez de desbordar la pila. */
    public static final int MAX_DEPTH = 64;
    /** Tamanio maximo de entrada en caracteres (1 MiB). */
    public static final int MAX_INPUT = 1024 * 1024;

    /** Entrada que no es JSON valido, o que excede los limites. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        JsonException(String message) { super(message); }
    }

    private final String s;
    private int i;
    private int depth;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) throw new JsonException("entrada nula");
        if (text.length() > MAX_INPUT) {
            throw new JsonException("entrada de " + text.length()
                    + " caracteres; el maximo es " + MAX_INPUT);
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw new JsonException(p.at("basura despues del valor"));
        return v;
    }

    // ------------------------------------------------------------------ valores

    private Object value() {
        if (++depth > MAX_DEPTH) throw new JsonException(at("anidamiento mayor que " + MAX_DEPTH));
        try {
            if (i >= s.length()) throw new JsonException(at("se esperaba un valor"));
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:  return number();
            }
        } finally {
            depth--;
        }
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { i++; return out; }
        while (true) {
            ws();
            if (peek() != '"') throw new JsonException(at("se esperaba una clave entre comillas"));
            String k = string();
            ws();
            expect(':');
            ws();
            out.put(k, value());   // clave duplicada: gana la ultima
            ws();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw new JsonException(at("se esperaba ',' o '}'"));
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> out = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; return out; }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw new JsonException(at("se esperaba ',' o ']'"));
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new JsonException(at("cadena sin cerrar"));
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) throw new JsonException(at("escape incompleto"));
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':  b.append(hex4()); break;
                    default: throw new JsonException(at("escape desconocido '\\" + e + "'"));
                }
            } else if (c < 0x20) {
                throw new JsonException(at("caracter de control sin escapar U+"
                        + String.format("%04X", (int) c)));
            } else {
                b.append(c);
            }
        }
    }

    /**
     * Un {@code \\uXXXX}. Los pares sustitutos salen solos: cada mitad es una unidad de codigo
     * de Java, asi que al concatenarlas el String queda bien formado.
     */
    private char hex4() {
        if (i + 4 > s.length()) throw new JsonException(at("escape \\u incompleto"));
        int v = 0;
        for (int k = 0; k < 4; k++) {
            int d = Character.digit(s.charAt(i + k), 16);
            if (d < 0) throw new JsonException(at("escape \\u con un digito no hexadecimal"));
            v = v * 16 + d;
        }
        i += 4;
        return (char) v;
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        // Parte entera: o un 0 solo, o un digito 1-9 seguido de mas digitos.
        if (peek() == '0') {
            i++;
            if (isDigit(peek())) throw new JsonException(at("cero a la izquierda"));
        } else if (isDigit(peek())) {
            while (isDigit(peek())) i++;
        } else {
            throw new JsonException(at("se esperaba un numero"));
        }
        if (peek() == '.') {
            i++;
            if (!isDigit(peek())) throw new JsonException(at("punto sin decimales"));
            while (isDigit(peek())) i++;
        }
        if (peek() == 'e' || peek() == 'E') {
            i++;
            if (peek() == '+' || peek() == '-') i++;
            if (!isDigit(peek())) throw new JsonException(at("exponente sin digitos"));
            while (isDigit(peek())) i++;
        }
        String lit = s.substring(start, i);
        double d = Double.parseDouble(lit);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("numero fuera de rango: " + lit);
        }
        return d;
    }

    private Object literal(String word, Object val) {
        if (!s.startsWith(word, i)) throw new JsonException(at("se esperaba '" + word + "'"));
        i += word.length();
        return val;
    }

    // ------------------------------------------------------------------- utiles

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    /** Caracter actual, o '\0' si se acabo la entrada. */
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private char next() {
        if (i >= s.length()) throw new JsonException(at("entrada truncada"));
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (peek() != c) throw new JsonException(at("se esperaba '" + c + "'"));
        i++;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private String at(String msg) { return msg + " (posicion " + i + ")"; }
}
