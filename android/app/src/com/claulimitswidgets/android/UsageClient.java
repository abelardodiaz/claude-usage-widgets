package com.claulimitswidgets.android;

import com.claudewidgets.core.Json;
import com.claudewidgets.core.Parser;
import com.claudewidgets.core.Source;
import com.claudewidgets.core.UnrecognizedFormatException;
import com.claudewidgets.core.UsageModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consulta de uso contra claude.ai. Via principal del widget (spike A2: la peticion nativa no
 * recibio reto en las condiciones probadas).
 *
 * Lo que esta clase NO hace, a proposito:
 * - No registra cuerpos de respuesta. Solo codigo, content-type y longitud.
 * - No manda el jarro de cookies entero: solo `sessionKey` y `lastActiveOrg`.
 * - No sigue redirecciones: la cookie jamas debe viajar a otro host.
 * - No habla con ningun host que no sea claude.ai.
 */
public final class UsageClient {

    private static final String ORIGIN = "https://claude.ai";
    private static final String ORGS = ORIGIN + "/api/organizations";
    /** 1 MiB: lo mismo que acota el lector JSON, pero en BYTES y antes de decodificar. */
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int TIMEOUT_MS = 20000;
    /** Forma 8-4-4-4-12 de un uuid. Defensa contra inyeccion de ruta al armar la URL. */
    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Cuerpo de mas de MAX_BYTES. Mensaje constante, sin datos de la respuesta. */
    private static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
        TooLargeException() { super("respuesta de mas de " + MAX_BYTES + " bytes"); }
    }

    /** 429 y 5xx: ni vencida ni bloqueada. Se reintenta con {@link Backoff}. */
    public static final class RetryLaterException extends Exception {
        private static final long serialVersionUID = 1L;
        RetryLaterException(String message) { super(message); }
    }

    private final String cookieHeader;
    private final String userAgent;

    /** `cookieHeader` ya viene reducido al minimo: lo arma {@link #minimalCookies}. */
    public UsageClient(String cookieHeader, String userAgent) {
        this.cookieHeader = cookieHeader;
        this.userAgent = userAgent;
    }

    /**
     * De todo lo que guardo el WebView, deja solo lo que claude.ai necesita. Mandar el resto
     * seria entregar cookies de terceros sin ninguna razon.
     */
    public static String minimalCookies(String all) {
        if (all == null) return "";
        StringBuilder out = new StringBuilder();
        for (String piece : all.split(";")) {
            String p = piece.trim();
            if (p.startsWith("sessionKey=") || p.startsWith("lastActiveOrg=")) {
                if (out.length() > 0) out.append("; ");
                out.append(p);
            }
        }
        return out.toString();
    }

    /** El valor de `lastActiveOrg`, o null. Es una credencial: no se registra. */
    public static String lastActiveOrg(String all) {
        if (all == null) return null;
        for (String piece : all.split(";")) {
            String p = piece.trim();
            if (p.startsWith("lastActiveOrg=")) {
                String v = p.substring("lastActiveOrg=".length());
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * Una organizacion, con lo unico que la app necesita. El `uuid` es un dato de cuenta
     * privado; el `name` es lo que ve el usuario en Ajustes.
     */
    public static final class Org {
        public final String uuid;
        public final String name;    // puede ser null: entonces Ajustes muestra el uuid abreviado
        Org(String uuid, String name) { this.uuid = uuid; this.name = name; }
    }

    public List<Org> organizations() throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        return parseOrgs(get(ORGS));
    }

    /** Separado del transporte para probarlo con entradas sinteticas. */
    static List<Org> parseOrgs(String body) throws UnrecognizedFormatException {
        Object root = parseOrFail(body);
        if (!(root instanceof List)) {
            throw new UnrecognizedFormatException("/api/organizations no devolvio un arreglo");
        }
        List<Org> out = new ArrayList<>();
        for (Object item : (List<?>) root) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Object uuid = m.get("uuid");
            if (!(uuid instanceof String) || ((String) uuid).isEmpty()) continue;
            // `name` es el nombre de la organizacion; si falta, `plan_display_name` sirve para
            // distinguir dos. Si tampoco esta, Ajustes cae al uuid abreviado.
            String name = str(m.get("name"));
            if (name == null) name = str(m.get("plan_display_name"));
            out.add(new Org((String) uuid, name));
        }
        if (out.isEmpty()) throw new UnrecognizedFormatException("ninguna organizacion con uuid");
        return out;
    }

    private static String str(Object v) {
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.isEmpty() ? null : s;
    }

    public UsageModel usage(String orgUuid) throws IOException, AuthExpiredException,
            BlockedException, RetryLaterException, UnrecognizedFormatException {
        // El uuid viene de una respuesta del servidor o de una preferencia: no se concatena a
        // una URL sin mirarlo. Un valor con '/' o '?' cambiaria a que endpoint se llama.
        requireUuid(orgUuid);
        // orgUuid es un dato de cuenta: si falla, el mensaje no lo lleva.
        return Parser.parse(get(ORGS + "/" + orgUuid + "/usage"), Source.CLAUDE_AI);
    }

    /** Package-private para probarla sin red. El mensaje no lleva el valor recibido. */
    static void requireUuid(String orgUuid) throws UnrecognizedFormatException {
        if (orgUuid == null || !UUID_RE.matcher(orgUuid).matches()) {
            throw new UnrecognizedFormatException("uuid de organizacion con forma invalida");
        }
    }

    private static Object parseOrFail(String body) throws UnrecognizedFormatException {
        try {
            return Json.parse(body);
        } catch (Json.JsonException e) {
            // Ojo: el mensaje de la causa (JsonException) puede llevar un fragmento corto de la
            // entrada (un caracter de escape, hasta 32 de un numero). Se encadena para depurar,
            // pero nadie debe registrar la cadena de causas.
            throw new UnrecognizedFormatException("la respuesta no es JSON valido", e);
        }
    }

    private String get(String url) throws IOException, AuthExpiredException, BlockedException,
            RetryLaterException, UnrecognizedFormatException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Cookie", cookieHeader);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Referer", ORIGIN + "/");

            int code = c.getResponseCode();
            String ctype = c.getHeaderField("content-type");
            String cfMitigated = c.getHeaderField("cf-mitigated");
            // Primero por codigo y cabeceras, sin leer el cuerpo: un reto grande (con 403 o con
            // 200) no puede convertirse en un fallo de red. Si no es 200, check() siempre lanza;
            // si es 200 lanza ante cf-mitigated o content-type html. El HTML sin content-type
            // solo se ve con el cuerpo, por eso se vuelve a revisar tras leerlo.
            check(code, ctype, "", cfMitigated);
            InputStream in = c.getInputStream();
            String body = in == null ? "" : read(in);
            check(code, ctype, body, cfMitigated);
            return body;
        } catch (TooLargeException e) {
            throw e;
        } catch (IOException e) {
            // El mensaje original puede llevar la URL (con el uuid de la organizacion): solo
            // se conserva el tipo de fallo.
            throw new IOException("fallo de red: " + e.getClass().getSimpleName());
        } finally {
            c.disconnect();
        }
    }

    /**
     * Clasifica la respuesta. Package-private para poder probarla sin red.
     * No recibe la URL ni la cookie: nada de lo que entra aqui puede acabar en un log.
     */
    static void check(int code, String ctype, String body, String cfMitigated)
            throws AuthExpiredException, BlockedException, RetryLaterException,
            UnrecognizedFormatException {
        if (cfMitigated != null && !cfMitigated.isEmpty()) {
            throw new BlockedException("cf-mitigated presente");
        }
        if (code == 401) throw new AuthExpiredException("401");
        if (code == 403) throw new BlockedException("403");
        if (code == 429 || (code >= 500 && code < 600)) {
            throw new RetryLaterException("HTTP " + code);
        }
        if (code != 200) throw new UnrecognizedFormatException("HTTP " + code);
        if (looksLikeHtml(ctype, body)) {
            // 200 con HTML es la pagina de reto de Cloudflare, no una respuesta.
            throw new BlockedException("se esperaba JSON y llego HTML");
        }
    }

    private static boolean looksLikeHtml(String ctype, String body) {
        if (ctype != null && ctype.toLowerCase(Locale.ROOT).contains("text/html")) return true;
        String head = body.length() > 64 ? body.substring(0, 64) : body;
        String t = head.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("<!doctype") || t.startsWith("<html");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        try {
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new TooLargeException();
                }
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
