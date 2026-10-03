# claude-usage-widgets — Diseño

- **Fecha:** 2026-10-02
- **Estado:** borrador para revisión del dueño
- **Prototipo de referencia:** `prototype/` (Python + pywebview, Windows, validado con datos reales)

## 1. Qué es, para quién y por qué

**Qué:** widgets siempre visibles que muestran cuánto de tu cuota de Claude llevas gastado:
sesión de 5 h, semana, límites por modelo, cuánto llevas hoy contra lo que te toca,
desglose por superficie (Claude Code / Chats / Cowork) y proyección de cuándo llegarías al límite.

**Para quién:** usuarios de planes Claude Pro/Max — de entrada la comunidad hispanohablante
(grupos como "Claude Code en Español"), pero con README en español e inglés para cualquiera.

**Beneficio:** hoy el uso se consulta a mano (`/usage` o la página de ajustes). Quien vive cerca
del límite semanal necesita verlo de reojo, igual que la batería, para repartir la cuota en la
semana y no quedarse sin servicio un martes.

**Plataformas, en orden:** Windows → Linux (Mac "best effort") → Android widget de pantalla
de inicio → Android burbuja flotante.

**Fuera de alcance:** cuentas de API con facturación por tokens (Console), planes Team/Enterprise
administrados (puede funcionar, no se promete), iOS, servidores o nubes del proyecto (no hay
backend: todo corre en el dispositivo del usuario).

## 2. Decisiones cerradas

| Tema | Decisión | Por qué |
|---|---|---|
| Público | Comunidad abierta | Instalación de 1 paso, seguridad estricta del token |
| Escritorio | Tauri 2 (Rust + UI HTML) | Instaladores chicos, bandeja, autoarranque y updater oficiales; reutiliza la UI del prototipo |
| Android | Java puro, APIs del framework (sin androidx, sin Gradle obligatorio) | Compila y se prueba en el propio teléfono (Termux: `javac`, `d8`, `aapt2`); un widget y una burbuja no necesitan más |
| Datos en Android | Login en la página real de claude.ai dentro de un WebView | Autónomo, sin PC; no suplanta al cliente OAuth de Claude Code |
| Distribución Android | APK firmado en GitHub Releases, actualizable con Obtainium | Sin revisión de Play para el permiso de superposición; costo cero |
| Lógica compartida | Contrato + fixtures en `spec/`, implementado en Rust y en Java | Sin FFI; las fixtures avisan si Anthropic cambia el endpoint |
| Licencia | MIT | Igual que los demás repos públicos del autor |

## 3. Arquitectura

```
spec/         contrato: modelo normalizado (JSON Schema), reglas de cálculo, fixtures + esperados
desktop/      Tauri 2: src-tauri/ (Rust: fuentes, cálculo, historial, bandeja) + ui/ (HTML/CSS/JS)
android/      Java: core/ (cliente + cálculo, Java puro, también corre en JVM de escritorio)
              app/  (login WebView, widget AppWidget, burbuja overlay, ajustes) + build.sh
prototype/    pywebview; referencia hasta paridad con desktop/, luego se borra
docs/         specs/, plans/, SECURITY en la raíz; incoming/ y procesados/ fuera de git
```

### 3.1 Fuentes de datos

Una interfaz `UsageSource` con dos implementaciones; ambas entregan el mismo modelo normalizado.

1. **`ClaudeCodeSource`** (escritorio). Lee el token OAuth que Claude Code ya guarda:
   `~/.claude/.credentials.json` en Windows y Linux, llavero del sistema en macOS. Llama
   `GET https://api.anthropic.com/api/oauth/usage` con `anthropic-beta: oauth-2025-04-20`.
   **Solo lectura: nunca refresca el token.** Refrescar rota el refresh token y dejaría a
   Claude Code con uno inválido. Si el token venció, el widget lo dice ("abre Claude Code")
   y muestra el último dato con su hora.
2. **`ClaudeAiSessionSource`** (Android siempre; escritorio como respaldo para quien usa Claude
   sin Claude Code). El usuario inicia sesión en `https://claude.ai` real dentro de un WebView;
   la app toma la cookie de sesión y consulta `GET https://claude.ai/api/organizations/{org_uuid}/usage`
   (misma forma que OAuth, regla R1b). **Observado en W0** (spikes A1 y A2): basta la cookie, y en
   las peticiones nativas probadas (`HttpURLConnection`) no hubo reto anti-bots. Vía principal:
   nativa; **plan B documentado:** consulta dentro del WebView. Toda respuesta 403, con cabecera
   `cf-mitigated` o con HTML en vez de JSON se trata como bloqueo: backoff y aviso de re-login.
   Una cuenta puede tener varias organizaciones (`GET /api/organizations`): la regla de selección
   automática se define en W3 (candidatos: cookie `lastActiveOrg`, `capabilities`,
   `rate_limit_tier`) y hay **selector manual obligatorio** en ajustes.

Ambos endpoints son **no documentados**: el cliente parsea a la defensiva (campos opcionales,
claves desconocidas ignoradas, `null` tolerado) y un cambio de forma se reporta como
"formato de respuesta no reconocido" en vez de mostrar números falsos.

### 3.2 Modelo normalizado (`spec/usage-model.schema.json`)

```
Usage {
  fetched_at: datetime
  session:  Window { percent: number (normalmente 0..100), resets_at: datetime|null }
  weekly:   Window
  scoped:   [ { label: string, percent, resets_at } ]   // p.ej. límite semanal por modelo
  breakdown:[ { key: string, label: string, percent } ] // reparto de lo usado en la semana
  source:   "claude_code" | "claude_ai"
}
```

### 3.3 Reglas de cálculo (`spec/rules.md`, idénticas en Rust y Java)

El prototipo hace algunas simplificaciones; la versión definitiva las corrige:

- **Historial:** cada consulta exitosa guarda una muestra `(t, weekly.percent, weekly.resets_at)`
  localmente, 15 días máximo. Muestras con `resets_at` que difiere más de 1 h = ventanas distintas
  (hubo reinicio): el consumo de la segunda cuenta desde 0. *Corrección:* si el hueco entre dos
  muestras cruza la medianoche, el consumo se reparte proporcional al tiempo entre los días
  (el prototipo se lo cargaba todo al segundo día).
- **Hoy:** consumido = suma de deltas del día local. Cupo de hoy =
  `(100 − % al iniciar el día) / max(días que faltan para el reinicio, 1)`. Adaptativo: si vas
  adelantado el cupo baja, si ahorras sube. Si no hay muestra previa a hoy, se marca "parcial,
  registrando desde HH:MM".
- **Proyección:** sesión = ritmo promedio desde que abrió la ventana de 5 h. Semana = ritmo de
  las **últimas 24 h** (o desde el inicio de la ventana si lleva menos), porque el promedio de
  toda la semana esconde un día intenso. *Corrección respecto al prototipo,* que usaba el
  promedio de toda la ventana. Salida: hora estimada de 100 % y si cae antes del reinicio.
- **Colores (R7, con fixtures propios en `spec/fixtures/colors/`):** verde < 60 %, ámbar < 85 %,
  rojo ≥ 85 %. La barra "hoy" usa el cociente consumido/cupo (< 0.7 verde, < 1 ámbar, ≥ 1 rojo);
  gris si no hay cupo (nulo o 0) y **rojo si el cupo es negativo** (cuota semanal ya rebasada).
  En la barra semanal, una marca indica el ritmo parejo (fracción transcurrida de la ventana).

### 3.4 Sondeo y errores

- Escritorio: cada 2 min; Android widget: cada 15 min (JobScheduler, mínimo del sistema) y al
  tocarlo; burbuja: cada 2 min **solo mientras está visible**.
- HTTP 429 o 5xx: backoff exponencial hasta 30 min. 401/403: credencial vencida, se pide
  renovarla. Sin red: se muestra el último dato con su antigüedad, nunca un 0 % falso.

## 4. Escritorio (Tauri 2)

- Ventana sin marco, siempre encima, arrastrable, recuerda posición (`window-state`), una sola
  instancia (`single-instance`), ícono en bandeja con mostrar/ocultar/actualizar/salir,
  autoarranque opcional (`autostart`), actualizaciones firmadas (`updater`).
- UI: la del prototipo (barras, 3 paneles: últimos 7 días, desglose, proyección), en español e
  inglés según el idioma del sistema.
- Instaladores: `.msi`/`.exe` (Windows), `.AppImage` y `.deb` (Linux), `.dmg` (Mac sin firmar,
  best effort). Generados en GitHub Actions al crear un tag.
- Credencial de claude.ai (modo respaldo) en el almacén del sistema (crate `keyring`:
  Credential Manager / Secret Service / Keychain).

## 5. Android (Java)

- **Login:** Activity con WebView en `https://claude.ai/login`; al detectar sesión, lee la cookie
  con `CookieManager`, la cifra con una llave AES-GCM del **Android Keystore** (no exportable) y
  borra el WebView. Botón "cerrar sesión" que borra la llave y los datos. Hallazgos de W0 (spike A2):
  - **Solo acceso por correo.** "Continuar con Google" dentro de un WebView provoca un bloqueo de
    Google de 48 h; la app no controla esa página, así que una pantalla previa explica que solo
    funciona el correo.
  - `setAcceptThirdPartyCookies(false)`; a `claude.ai` se envía el mínimo de cookies
    (`sessionKey` y, si aplica, `lastActiveOrg`). Nunca se registran cuerpos de respuesta: solo
    código, `content-type` y longitud.
  - El enlace del correo vence en **10 minutos** y claude.ai limita los reenvíos: la pantalla lo
    dice, deja el campo del código a la vista y no invita a reenviar en bucle.
  - Las consultas periódicas van por `HttpURLConnection` desde `JobScheduler`. El WebView queda
    para el login y como plan B en primer plano (en segundo plano Samsung congela el proceso y
    `evaluateJavascript` con `postDelayed` no corrió; no se probó bajo `JobScheduler`).
  - La cookie `lastActiveOrg` (un UUID) puede ahorrar la llamada a `/api/organizations`; se trata
    como credencial.
  - Un `401` en cualquier consulta lleva a la pantalla de login (la duración de la sesión no es
    medible desde la app).
- **Widget:** `AppWidgetProvider` con `RemoteViews` (tamaños 4×1 compacto y 4×2 con barras), con
  `android:exported="false"` (verificado en One UI: el sistema entrega las actualizaciones y el toque; otros lanzadores sin probar) y
  `previewLayout` obligatorio. El contenido llena la celda. Tocar = actualizar; mantener = abrir la app.
- **Burbuja:** servicio en primer plano (`foregroundServiceType="specialUse"`) con una vista en
  `TYPE_APPLICATION_OVERLAY` que muestra el % de sesión en un círculo de color; al tocarla se
  expande al panel completo. Requiere permiso de superposición, pedido con explicación previa.
- **Build:** `android/build.sh` con `aapt2` + `javac` + `d8` + `apksigner`, el mismo script en el
  teléfono (Termux) y en Ubuntu (CI). `minSdk 29`, `targetSdk 34`. Sin `zipalign` (no existe en
  Termux y no hace falta: verificado en W0). Referencia (no base): `android/spikes/w0/`, desechable; W3 no lo hereda (ver "Lo que W3 NO debe
  copiar" en el reporte del spike A2).
- **Firma:** builds de prueba con llave de debug en el teléfono; el APK de release se firma
  solo en GitHub Actions con la llave en un secret del repositorio.

## 6. Seguridad (`SECURITY.md`)

Modelo de amenaza: la app maneja una credencial que da acceso **total** a la cuenta de Claude
del usuario. Reglas no negociables, revisadas en cada oleada:

1. La credencial no sale del dispositivo, nunca se escribe en logs, ni en el historial, ni en
   mensajes de error.
2. Red: lista blanca de dominios (`api.anthropic.com`, `claude.ai`). Cero telemetría, cero
   analíticas, cero servidores propios.
3. Almacenamiento solo en el almacén seguro del sistema; el token de Claude Code no se copia,
   se lee en cada consulta.
4. Tauri: CSP estricta, sin `shell`/`fs` expuestos a la UI; la UI no ve credenciales, solo el
   modelo normalizado.
5. Android: WebView solo para el login (sin JavaScript bridge), `usesCleartextTraffic=false`,
   sin permisos de red local.
6. Releases firmados con checksums SHA-256 publicados; dependencias fijadas (lockfiles) y
   revisadas por Dependabot.
7. Divulgación de vulnerabilidades por GitHub Security Advisories.
8. Aviso claro en el README: proyecto no oficial, no afiliado a Anthropic, usa endpoints no
   documentados que pueden cambiar.

## 7. Pruebas

- **Contrato:** `spec/fixtures/{parse,history,projection,colors}/*.json`, cada uno con su `input`
  (respuestas reales anonimizadas o series de muestras) y su `expected` calculado a mano.
  Rust (`cargo test`) y Java (`android/core`, en JVM de escritorio) deben producir exactamente
  los esperados. Corre en CI en cada PR.
- **Escritorio:** pruebas unitarias en Rust de fuentes y cálculo; prueba manual guiada en
  Windows y Linux por release.
- **Android:** el Claude Code del teléfono instala y recorre la app con su método de navegación
  automatizada (login, widget, burbuja, rotación, sin red, token vencido).

## 8. Forma de trabajo

- **Plan:** oleadas → fases → tareas, en `docs/superpowers/plans/`. Cada fase cierra con una
  **revisión de un subagente Fable** contra este diseño y `SECURITY.md`; los hallazgos se
  corrigen antes de pasar a la siguiente fase.
- **Git:** `main` protegida; una rama por fase (`w1/f2-bandeja`), PR a `main`, revisión antes de
  merge. La parte Android se trabaja en ramas `android/*`.
- **Orquestación:** una sesión de Claude Code en la PC orquesta el repo, escribe planes y revisa
  PRs. Otra sesión de Claude Code **en el teléfono Android** (Termux) ejecuta las fases de Android:
  clona el repo, compila, instala y prueba en el dispositivo, y abre PRs. Las dos se coordinan con
  memos en `docs/incoming/` (asignaciones, avisos de terminado, dudas); **el código solo viaja por
  GitHub**. `docs/incoming/` y `docs/procesados/` están fuera de git (repo público).

### Oleadas

| Oleada | Contenido | Sale cuando |
|---|---|---|
| **W0 Cimientos** | Repo + CI esqueleto + SECURITY.md + `spec/` con fixtures reales; **spike A** en el teléfono: login WebView en claude.ai y descubrir el endpoint de uso; **spike B**: AppWidget "hola" compilado e instalado en el teléfono; verificar el canal de memos en ambos sentidos | Ambos spikes pasan o la parte Android se rediseña |
| **W1 Windows** | Tauri con paridad al prototipo + bandeja + autoarranque + instalador | Instalador en un Release, probado en Windows 11 |
| **W2 Linux (+Mac)** | AppImage/deb, Secret Service, modo respaldo claude.ai en escritorio | Probado en Ubuntu |
| **W3 Android widget** | `core` Java, login, widget 4×1 y 4×2, APK firmado en Releases | Instalable vía Obtainium |
| **W4 Android burbuja** | Servicio + overlay + panel expandido | Probado en el teléfono |
| **W5 Comunidad** | README bilingüe con capturas, guía de instalación, publicación en el grupo | Publicado |

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| Anthropic cambia o cierra el endpoint | Parseo defensivo, fixtures que lo detectan, aviso de "formato no reconocido"; los dos orígenes son independientes |
| claude.ai no expone el uso por cookie | **Cerrado en W0:** lo expone; sin reto anti-bots en las pruebas nativas |
| Cloudflare empieza a retar la vía nativa | Detección (403, `cf-mitigated`, HTML) + plan B por WebView en primer plano |
| La sesión de claude.ai vence sin aviso | `401` → pantalla de login; el widget muestra el último dato con su hora |
| Antivirus/SmartScreen marcan el instalador sin firma de código | Documentarlo; evaluar firma (p. ej. SignPath para OSS) en W5 |
| Robo de la cookie de claude.ai en Android | Keystore no exportable, sin bridge JS, sin backups (`allowBackup=false`) |
| Un fork malicioso distribuye una versión que roba credenciales | Releases firmados, checksums, README indica la única fuente oficial |
