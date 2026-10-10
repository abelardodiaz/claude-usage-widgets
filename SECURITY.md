# Seguridad

## Qué protege este proyecto

claude-usage-widgets maneja una credencial que da acceso **total** a tu cuenta de Claude:
el token OAuth de Claude Code (escritorio) o la sesión de claude.ai (Android y respaldo de
escritorio). Estas reglas no son negociables y cada fase se revisa contra ellas.

1. La credencial no sale de tu dispositivo. Nunca se escribe en logs, en el historial ni en
   mensajes de error.
2. Red: solo `api.anthropic.com` y `claude.ai`. Excepción única: `github.com` y el CDN al que
   GitHub redirige sus Releases (`*.githubusercontent.com`), **solo cuando el usuario pulsa
   "Buscar actualizaciones"**; nunca al arrancar ni de forma periódica. Cero telemetría, cero analíticas, cero servidores del proyecto.
3. Almacenamiento: solo en el almacén seguro del sistema (Credential Manager, Secret Service,
   Keychain, Android Keystore). El token de Claude Code no se copia: se lee en cada consulta
   y **nunca se refresca** (refrescarlo invalidaría el de Claude Code).
4. Escritorio (Tauri): CSP estricta, sin APIs de `shell` ni `fs` expuestas a la UI. La UI solo
   recibe el modelo normalizado de uso, nunca credenciales.
5. Android: el WebView se usa solo para iniciar sesión; sin `addJavascriptInterface`,
   `usesCleartextTraffic=false`, `allowBackup=false`, sin permisos de red local.
6. Releases firmados, con checksums SHA-256 publicados. Dependencias fijadas en lockfiles y
   vigiladas por Dependabot.
7. La única fuente oficial de instaladores es la página de Releases de este repositorio.

## Reportar una vulnerabilidad

Usa **Security → Report a vulnerability** (GitHub Security Advisories) en este repositorio.
No abras un issue público. Respuesta en menos de 7 días.

## Aviso

Proyecto no oficial, no afiliado a Anthropic. Usa endpoints no documentados que pueden cambiar
o dejar de funcionar sin aviso.
