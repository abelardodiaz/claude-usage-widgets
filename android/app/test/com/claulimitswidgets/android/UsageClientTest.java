package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

public final class UsageClientTest {

    public static void run(Assert a) {
        // 200 con JSON: pasa.
        a.eq("200 json", null, classify(200, "application/json", "{}"));
        a.eq("200 json con charset", null,
                classify(200, "application/json; charset=utf-8", "{}"));

        // 401: sesion vencida (Review Focus 2).
        a.eq("401", "auth", classify(401, "application/json", "{}"));

        // Bloqueo (Review Focus 3): 403, cf-mitigated, o HTML donde se espera JSON.
        a.eq("403", "blocked", classify(403, "application/json", "{}"));
        a.eq("200 pero html", "blocked", classify(200, "text/html", "<!DOCTYPE html>"));
        a.eq("200 sin content-type y cuerpo html", "blocked",
                classify(200, null, "  <html><head>"));
        a.eq("cf-mitigated", "blocked", classifyWithHeader(200, "application/json", "{}", "challenge"));

        // 429 y 5xx: ni vencida ni bloqueada; se reintenta con backoff.
        a.eq("429", "retry", classify(429, "application/json", "{}"));
        a.eq("500", "retry", classify(500, "application/json", "{}"));
        a.eq("503", "retry", classify(503, "application/json", "{}"));

        // Cualquier otro codigo raro: formato no reconocido, no se inventa nada.
        a.eq("418", "format", classify(418, "application/json", "{}"));

        // Bordes adicionales: el reto manda sobre el codigo; una redireccion no se sigue.
        a.eq("cf-mitigated con 403", "blocked", classifyWithHeader(403, "text/html", "x", "challenge"));
        a.eq("cf-mitigated vacio no cuenta", null, classifyWithHeader(200, "application/json", "{}", ""));
        a.eq("302", "format", classify(302, null, ""));
        a.eq("404", "format", classify(404, "application/json", "{}"));
        a.eq("599", "retry", classify(599, null, ""));
        a.eq("html con ctype mayusculas", "blocked", classify(200, "Text/HTML; charset=UTF-8", "x"));

        // Los mensajes de error no llevan cuerpo ni cookie.
        String body = "SECRETO-DEL-CUERPO";
        noLeak(a, "mensaje 403", messageOf(403, "text/html", body), body);
        noLeak(a, "mensaje 418", messageOf(418, "text/html", body), body);
        noLeak(a, "mensaje html", messageOf(200, "text/html", body), body);

        // uuid: forma 8-4-4-4-12; lo demas se rechaza antes de tocar la red.
        a.eq("uuid valido", null, uuidResult("123e4567-e89b-12d3-a456-426614174000"));
        a.eq("uuid mayusculas", null, uuidResult("123E4567-E89B-12D3-A456-426614174000"));
        a.eq("uuid ../x", "format", uuidResult("../x"));
        a.eq("uuid con barra", "format", uuidResult("123e4567-e89b-12d3-a456-42661417/000"));
        a.eq("uuid 36 guiones", "format", uuidResult("------------------------------------"));
        a.eq("uuid con query", "format", uuidResult("123e4567-e89b-12d3-a456-42661417?x=1"));
        a.eq("uuid nulo", "format", uuidResult(null));
        a.eq("uuid vacio", "format", uuidResult(""));
        a.eq("uuid con salto final", "format", uuidResult("123e4567-e89b-12d3-a456-426614174000\n"));
        a.eq("usage ../x no toca la red", "format", usageResult("../x"));

        // parseOrgs con entradas sinteticas.
        a.eq("orgs normal", "u1|N", orgs("[{\"uuid\":\"u1\",\"name\":\"N\"}]"));
        a.eq("orgs cae a plan_display_name", "u1|P",
                orgs("[{\"uuid\":\"u1\",\"plan_display_name\":\"P\"}]"));
        a.eq("orgs sin nombre", "u1|null", orgs("[{\"uuid\":\"u1\"}]"));
        a.eq("orgs salta elemento sin uuid", "u2|null",
                orgs("[{\"name\":\"x\"},{\"uuid\":\"u2\"}]"));
        a.eq("orgs arreglo vacio", "format", orgs("[]"));
        a.eq("orgs sin uuid en ninguna", "format", orgs("[{\"name\":\"x\"}]"));
        a.eq("orgs raiz objeto", "format", orgs("{}"));
        a.eq("orgs json invalido", "format", orgs("<html>"));

        // Cookies minimas: solo sessionKey y lastActiveOrg.
        a.eq("minimal", "sessionKey=A; lastActiveOrg=B",
                UsageClient.minimalCookies("x=1; sessionKey=A; cf_clearance=Z; lastActiveOrg=B"));
        a.eq("minimal nulo", "", UsageClient.minimalCookies(null));
        a.eq("minimal sin ninguna", "", UsageClient.minimalCookies("x=1; y=2"));
        a.eq("org", "B", UsageClient.lastActiveOrg("sessionKey=A; lastActiveOrg=B"));
        a.eq("org ausente", null, UsageClient.lastActiveOrg("sessionKey=A"));
        a.eq("org vacia", null, UsageClient.lastActiveOrg("lastActiveOrg="));
    }

    private static void noLeak(Assert a, String what, String msg, String body) {
        a.eq(what + " lanzo", false, "<<no lanzo>>".equals(msg));
        a.eq(what + " con mensaje", false, msg.isEmpty() || "null".equals(msg));
        a.eq(what + " sin cuerpo", false, msg.contains(body));
    }

    private static String uuidResult(String u) {
        try {
            UsageClient.requireUuid(u);
            return null;
        } catch (com.claudewidgets.core.UnrecognizedFormatException e) {
            return "format";
        }
    }

    private static String usageResult(String u) {
        try {
            new UsageClient("", "t").usage(u);
            return null;
        } catch (com.claudewidgets.core.UnrecognizedFormatException e) {
            return "format";
        } catch (Exception e) {
            return "otro:" + e.getClass().getSimpleName();
        }
    }

    private static String orgs(String body) {
        try {
            StringBuilder sb = new StringBuilder();
            for (UsageClient.Org o : UsageClient.parseOrgs(body)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(o.uuid).append('|').append(o.name);
            }
            return sb.toString();
        } catch (com.claudewidgets.core.UnrecognizedFormatException e) {
            return "format";
        }
    }

    private static String messageOf(int code, String ctype, String body) {
        try {
            UsageClient.check(code, ctype, body, null);
            return "<<no lanzo>>";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String classify(int code, String ctype, String body) {
        return classifyWithHeader(code, ctype, body, null);
    }

    private static String classifyWithHeader(int code, String ctype, String body, String cfMitigated) {
        try {
            UsageClient.check(code, ctype, body, cfMitigated);
            return null;
        } catch (AuthExpiredException e) {
            return "auth";
        } catch (BlockedException e) {
            return "blocked";
        } catch (UsageClient.RetryLaterException e) {
            return "retry";
        } catch (com.claudewidgets.core.UnrecognizedFormatException e) {
            return "format";
        }
    }
}
