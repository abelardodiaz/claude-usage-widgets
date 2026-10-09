package com.claulimitswidgets.android;

import android.net.Uri;

import com.claudewidgets.core.Assert;

/** isClaude es la defensa de host del WebView de login: aqui se prueba entera. */
public final class LoginActivityTest {

    public static void run(Assert a) {
        a.eq("claude.ai/login", true, ok("https://claude.ai/login"));
        a.eq("subdominio", true, ok("https://api.claude.ai/x"));
        a.eq("google", false, ok("https://accounts.google.com/"));
        a.eq("sin https", false, ok("http://claude.ai/"));
        a.eq("intent", false, ok("intent://algo"));
        a.eq("host en la ruta", false, ok("https://evil.com/claude.ai"));
        a.eq("claude.ai como subdominio ajeno", false, ok("https://claude.ai.evil.com/"));
        a.eq("notclaude", false, ok("https://notclaude.ai/"));
        a.eq("market", false, ok("market://details?id=x"));
        a.eq("javascript", false, ok("javascript:alert(1)"));
        a.eq("userinfo engana", false, ok("https://claude.ai@evil.com/"));
        a.eq("puerto 443 explicito", true, ok("https://claude.ai:443/login"));
        a.eq("puerto distinto", false, ok("https://claude.ai:8443/login"));
        a.eq("subdominio con puerto distinto", false, ok("https://api.claude.ai:4443/x"));
        a.eq("mayusculas", true, ok("https://CLAUDE.AI/login"));
        a.eq("mayusculas en subdominio", true, ok("https://Api.Claude.Ai/x"));
        a.eq("punto final", false, ok("https://claude.ai./login"));
        a.eq("IDN con homoglifo", false, ok("https://cl\u0430ude.ai/"));
        a.eq("IDN punycode", false, ok("https://xn--claude-9ve.ai/"));
        a.eq("nulo", false, LoginActivity.isClaude(null));
    }

    private static boolean ok(String url) {
        return LoginActivity.isClaude(Uri.parse(url));
    }
}
