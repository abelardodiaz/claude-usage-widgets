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
        a.eq("mensaje 403 sin cuerpo", false, messageOf(403, "text/html", body).contains(body));
        a.eq("mensaje 418 sin cuerpo", false, messageOf(418, "text/html", body).contains(body));
        a.eq("mensaje html sin cuerpo", false, messageOf(200, "text/html", body).contains(body));

        // Cookies minimas: solo sessionKey y lastActiveOrg.
        a.eq("minimal", "sessionKey=A; lastActiveOrg=B",
                UsageClient.minimalCookies("x=1; sessionKey=A; cf_clearance=Z; lastActiveOrg=B"));
        a.eq("minimal nulo", "", UsageClient.minimalCookies(null));
        a.eq("minimal sin ninguna", "", UsageClient.minimalCookies("x=1; y=2"));
        a.eq("org", "B", UsageClient.lastActiveOrg("sessionKey=A; lastActiveOrg=B"));
        a.eq("org ausente", null, UsageClient.lastActiveOrg("sessionKey=A"));
        a.eq("org vacia", null, UsageClient.lastActiveOrg("lastActiveOrg="));
    }

    private static String messageOf(int code, String ctype, String body) {
        try {
            UsageClient.check(code, ctype, body, null);
            return "";
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
