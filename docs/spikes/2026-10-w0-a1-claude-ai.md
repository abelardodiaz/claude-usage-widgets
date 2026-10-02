# Spike A1 — Endpoint de uso en claude.ai

- **Fecha:** 2026-10-02
- **Método:** Claude in Chrome sobre una sesión real de claude.ai (plan Max), página
  `https://claude.ai/settings/usage`. Se observaron las peticiones de red y se reprodujeron con
  `fetch(..., {credentials: 'include'})` desde la propia página. Ninguna cookie se leyó ni se imprimió.
- **Resultado:** **PASA.** Existe un endpoint usable y su respuesta tiene la misma forma que la de OAuth.

## Endpoint

```
GET https://claude.ai/api/organizations/{org_uuid}/usage
```

- **Autenticación:** basta la cookie de sesión del navegador. La reproducción sin cabeceras extra
  (sin `anthropic-client-*`) respondió `200 application/json`.
- **`org_uuid`:** se obtiene de `GET https://claude.ai/api/organizations` (arreglo; `[i].uuid`).
- **Variante que usa la página:** `?cedar_ember=1&skip_spend=1`. Agrega datos de la cuenta (plan,
  antigüedad, ruta de facturación) que el widget no necesita, y omite el gasto. **No usarla:**
  la variante simple trae todo lo necesario y expone menos.

## Forma de la respuesta

Las mismas claves de primer nivel que `api.anthropic.com/api/oauth/usage`: `five_hour`,
`seven_day`, `seven_day_*`, claves con nombre clave (`tangelo`, `iguana_necktie`, ...),
`extra_usage`, `limits[]`, `spend`, `member_dashboard_available`, `seven_day_breakdown`.
Los valores coincidieron con los que reporta Claude Code en la misma cuenta y momento: **ambas
fuentes miden la misma cuota.**

Diferencias con OAuth:

| | OAuth (Claude Code) | claude.ai |
|---|---|---|
| Zona de los instantes | local del usuario (`-06:00`) | UTC (`+00:00`) |
| `display_name` del desglose | inglés (`Other`) | idioma de la cuenta (`Otros`) |

Ambas quedan cubiertas por R0 (instantes se comparan como instantes) y por la nueva R1b.
Fixture: `spec/fixtures/parse/05-claude-ai.json` (anonimizada: sin UUID de organización).

## Riesgos que pasan al spike A2 (teléfono)

1. **Anti-bots fuera del navegador.** La prueba se hizo *dentro* de Chrome. Una petición nativa
   (`HttpURLConnection`) con la cookie puede recibir un reto de Cloudflare. El spike A2 prueba
   las dos vías: nativa (A2.3) y `evaluateJavascript` + `fetch` dentro del WebView (A2.4).
2. **Varias organizaciones.** Si la cuenta pertenece a más de una (personal + equipo), hay que
   elegir cuál consultar. Propuesta para W3: la primera cuyo uso responda `200` con
   `five_hour`/`seven_day` válidos, con selector manual en ajustes si hay más de una.
3. **Duración de la sesión.** Se mide en A2.5.

## Decisión

GO para continuar W0 (F4 y F5). La parte Android sigue como está en la spec, pendiente de A2.
