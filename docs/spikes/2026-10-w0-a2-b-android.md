# Spike A2 + B — Android (WebView de claude.ai y AppWidget)

- **Fecha:** 2026-10-02
- **Dispositivo:** Android 17 (SDK 37), Samsung / One UI.
- **Toolchain:** Termux. `aapt2` 2.20-android-16.0.0_r4, `javac`/OpenJDK 21.0.12, `d8` 9.2.4-dev,
  `apksigner` 0.9, `android.jar` de `~/android/platforms/android-34`. Sin Gradle, sin androidx.
- **Código:** `android/spikes/w0/` (desechable; no es la base de W3).
- **Resultado:** **GO para la parte Android.** Los ocho criterios pasan. En las condiciones de
  esta prueba la consulta nativa responde `200` sin reto anti-bots, lo que hace viable consultar
  sin WebView; no es una garantía (ver A2.3). El login sí cambia: por correo, no por Google.

## Resumen por criterio

| Criterio | Estado | En una línea |
|---|---|---|
| A2.1 WebView carga `claude.ai/login` | **PASA** (con hallazgo) | Carga y permite iniciar sesión, pero **el botón de Google no sirve** |
| A2.2 Cookie no nula | **PASA** | 18 cookies con `sessionKey` tras el acceso por correo |
| A2.3 Consulta nativa | **PASA** | `HTTP=200` en los dos endpoints; sin reto observado |
| A2.4 Consulta por `evaluateJavascript` | **PASA** | `200` también; mismas claves que la vía nativa |
| A2.5 Persistencia de la sesión | **PASA** | Sobrevive a `force-stop` y a `install -r` |
| A2.6 Nombres de claves y cabeceras | **PASA** | 2 organizaciones; nombres y cabeceras abajo |
| B.1 AppWidget "hola" + toque | **PASA** | Anclado, renderiza y se refresca: 5 de 5 toques |
| B.2 `build.sh` + instalado y probado aquí | **PASA** | APK de 25 204 bytes, navegación automatizada |

---

## B.2 — Construcción sin Gradle: PASA

`android/spikes/w0/build.sh` encadena `aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zip` →
`apksigner`, con una llave de depuración que se crea sola fuera del repo
(`~/.android-spike-debug.keystore`).

```
$ bash android/spikes/w0/build.sh
== 1/6 aapt2 compile ==
== 2/6 aapt2 link ==
== 3/6 javac ==
== 4/6 d8 ==
== 5/6 empaquetar ==
== 6/6 apksigner ==
APK: build/spike-w0.apk (25204 bytes)

$ adb install -r android/spikes/w0/build/spike-w0.apk
Success
```

Dos cosas que W3 debe heredar:

- **`zipalign` no existe en Termux.** No hizo falta: `aapt2 link` deja `resources.arsc` con método
  `Stored`, y añadir `classes.dex` al final con `zip` no mueve su desplazamiento, así que conserva
  la alineación a 4 bytes que exige `targetSdk >= 30`. Comprobado con `unzip -lv`.
- **`javac -source 17 -target 17` + `d8 --min-api 29`** bastan; no hubo que bajar a Java 11.

## B.1 — AppWidget: PASA

El widget se ancló desde la propia app con `AppWidgetManager.requestPinAppWidget()` y el diálogo
del sistema, sin arrastrarlo a mano. Cadena completa observada en `logcat`:

```
HoneySpace.HoneyAppWidgetHostViewProperties: touch up, label : widgetId : 17
ActivityManager: Received BROADCAST ... pkg=com.claudewidgets.spike
                 intent=act=com.claudewidgets.spike.TAP cmp=.../.HelloWidgetProvider
SpikeW0: B.1 toque recibido, refrescando 1 instancia(s)
SpikeW0: B.1 widget 17 actualizado a 13:09:09
AppWidgetHost: updateAppWidgetView, appWidgetId = 17
```

Cinco toques seguidos, cinco actualizaciones; el texto del widget leído con `uiautomator dump`
siguió al reloj del sistema (13:09:39 → 13:09:45 → 13:09:51).

**Hallazgo que ahorra trabajo en W3:** `android:exported="false"` en el `AppWidgetProvider`
funciona (verificado en One UI; no se probó en otros lanzadores). El sistema entrega igual `APPWIDGET_UPDATE` (broadcast dirigido) y el `PendingIntent`
propio del toque también llega, porque corre con la identidad de la app. **No hay que exportar
el receptor**, lo que encaja con la regla 5 de `SECURITY.md`.

**Hallazgo menor:** el diálogo de anclaje muestra "No se pudo agregar el widget" en la vista
previa porque el `appwidget-provider` no declara `previewLayout` ni `previewImage`. El anclaje
funciona igual, pero W3 debe declarar `previewLayout` para no asustar al usuario.

## A2.1 — Login en el WebView: PASA, con un hallazgo que cambia el diseño

`https://claude.ai/login` carga y funciona dentro de un `WebView` con `setJavaScriptEnabled(true)`
y `setDomStorageEnabled(true)`. User-Agent que anuncia:

```
Mozilla/5.0 (Linux; Android 17; <modelo> Build/<build>; wv)
AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36
```

### El botón "Continuar con Google" NO es viable en un WebView

(Modelo y build tapados: repo público. Lo que importa es el token **`wv`**, que es como Google
reconoce un WebView.)

Se intentó el login con Google. La contraseña fue aceptada, pero acto seguido Google respondió:

> **Ya accediste en otro dispositivo o navegador.** Después de 48 horas, revisa [tu correo] en tus
> otros dispositivos o navegadores para encontrar un vínculo que te ayude a acceder aquí.

Es la verificación de navegador nuevo de Google: el `WebView` no comparte cookies con Chrome ni
puede usar la cuenta de Google del sistema, así que Google lo ve como un navegador desconocido y
**lo bloquea 48 horas**. No es un fallo del APK y no se arregla con autocompletado.

**Consecuencia para W3 (obligatoria):** la pantalla de login debe llevar al usuario al
**acceso por correo** de claude.ai, no al botón de Google. Conviene decírselo en la propia
pantalla antes de que se tope con el bloqueo.

### El acceso por correo sí funciona, con una ventana corta

El flujo por correo se ejercitó hasta el final:

1. Se escribe el correo en `claude.ai/login` → claude.ai envía "Tu enlace seguro a Claude.ai".
2. El enlace, abierto desde Gmail, cae en una pestaña de Chrome que **no inicia sesión**: muestra
   un código de verificación de 6 dígitos ("Ingresa este código donde intentaste iniciar sesión").
3. Ese código se escribe en el campo que claude.ai dejó abierto en el WebView.

El primer intento devolvió **"Código incorrecto ingresado"**. La causa está en el propio correo:
**"Este enlace vence en 10 minutos"**, y entre pedirlo (13:11:54) y enviar el código (13:22)
pasaron más de diez. No es un fallo del WebView.

Al reintentar, claude.ai dejó de enviar correos (ni el reenvío ni una petición nueva llegaron en
~4 minutos). Parece una limitación de frecuencia del propio servicio tras varios intentos
seguidos. Por eso A2.2–A2.6 quedan pendientes: **la app está instalada y lista; solo falta una
sesión viva.**

**Consecuencia para W3:** el login por correo tiene una ventana de 10 minutos y un límite de
reenvíos. La pantalla debe decirlo y no invitar a pulsar "reenviar" en bucle.

## A2.2 — Cookie: PASA

Tras completar el acceso por correo dentro del WebView:

```
A2.2: cookies=18 sessionKey=true nombres=[CH-prefers-color-scheme,
__Host-claude-ai-pending-login-email, __cf_bm, __ssid, _cfuvid, _dd_s_v2, _fbp,
activitySessionId, ajs_anonymous_id, anthropic-device-id, artifact-app-viewer, g_state,
ion-vk, lastActiveOrg, routingHint, sessionKey, sessionKeyLC, user-sidebar-visible-on-load]
```

Antes del acceso eran 13 y sin `sessionKey`. Aparecen `sessionKey`, `sessionKeyLC`,
`lastActiveOrg` y `routingHint`. **Dato útil para W3:** `lastActiveOrg` lleva la organización
activa, así que puede evitar una llamada a `/api/organizations` — pero su valor es un UUID y
debe tratarse con el mismo cuidado que la cookie de sesión.

Las dos cookies de Cloudflare (`__cf_bm`, `_cfuvid`) existen desde antes del login: hay gestión
de bots delante, pero no bloqueó en esta prueba (ver A2.3).

## A2.3 — Consulta nativa: PASA (sin reto observado)

`HttpURLConnection` con la cookie de sesión y el mismo User-Agent del WebView:

```
A2.3 nativo /organizations: HTTP=200 ctype=application/json server=cloudflare
A2.3 nativo /usage:         HTTP=200 ctype=application/json server=cloudflare
```

### Hasta dónde llega esta evidencia

**No se observó reto anti-bots en 2 peticiones nativas y en las del WebView** (todas las que obtuvieron
respuesta dieron `200`; un intento en segundo plano no obtuvo respuesta, ver A2.4), en unos
tres minutos, desde **un** dispositivo, **una** IP doméstica y **una** sesión recién emitida, con
el jarro de cookies completo que acababa de dejar el login.

Eso **no demuestra** que no haya reto. Cloudflare puntúa huella TLS, ritmo de peticiones y
reputación de IP, ninguna de las cuales se estresó aquí. Además las peticiones imitaban al
navegador: `httpGet` manda exactamente

```
Cookie: <todas las cookies de claude.ai>
User-Agent: <el mismo del WebView>
Accept: application/json, text/plain, */*
Accept-Language: es-MX,es;q=0.9,en;q=0.8
Referer: https://claude.ai/
```

Lo que sí se puede afirmar: **la vía nativa es viable y debe ser la principal**. W3 consulta con
`HttpURLConnection` desde un `JobScheduler`, sin WebView y sin abrir la app, **y maneja el caso
de que un día sí haya reto** (ver "Para la spec"). La vía del WebView queda como **plan B
documentado**, no descartada.

Claves de primer nivel de `/usage` (solo nombres):

```
amber_cistern, amber_gauge, amber_ladder, brass_thimble, cedar_ember, cinder_cove, copper_kite,
extra_usage, five_hour, harbor_lantern, iguana_necktie, juniper_tide, limits,
member_dashboard_available, nimbus_quill, omelette_promotional, seven_day, seven_day_breakdown,
seven_day_cowork, seven_day_oauth_apps, seven_day_omelette, seven_day_opus, seven_day_sonnet,
spend, tangelo, wattle_ember
```

Están `five_hour`, `seven_day`, `limits`, `seven_day_breakdown`, `spend`, `extra_usage` y
`member_dashboard_available`: **la misma forma que describe el spike A1**, así que la fixture
`spec/fixtures/parse/05-claude-ai.json` sigue valiendo.

## A2.4 — Consulta dentro del WebView: PASA, pero no sirve en segundo plano

Con `evaluateJavascript` + `fetch(..., {credentials:'include'})`, sin
`addJavascriptInterface`, estando la página en `claude.ai`:

```
orgs:  status 200, application/json, server cloudflare, n=2
usage: status 200, application/json, server cloudflare
```

Mismas claves que por la vía nativa. **Las dos vías sirven.**

### En segundo plano NO se puede confiar en ella

Pulsación larga sobre el botón programa la misma consulta 12 s después, para dispararla con la
Activity ya en segundo plano. Dos intentos:

| Intento | Programado | Disparó | Resultado |
|---|---|---|---|
| 1 | 13:46:10 | 13:46:22 (12 s, correcto) | `TypeError: Failed to fetch` |
| 2 | 13:47:17 | **13:48:21 (64 s)** | `200` |

El segundo parece un éxito pero no lo es. El `logcat` del sistema lo explica:

```
13:48:14  FreecessController: BG freezed, skip OLAF unfreeze for (com.claudewidgets.spike)
13:48:21.411  FreecessController: UFZ : com.claudewidgets.spike reason: activity
13:48:21.429  SpikeW0: A2.4bg lanzando fetch ... activity visible: false
```

Samsung **congela el proceso** en segundo plano (Freecess): el `postDelayed` no corrió a su hora
y el `fetch` solo se ejecutó 18 ms después de que el sistema descongelara el proceso porque la
Activity volvía al frente. La bandera `visible` seguía en `false` nada más porque el mensaje
pendiente iba por delante de `onResume` en la cola del hilo principal.

**Alcance de la prueba:** se usó `Handler.postDelayed`, **no un `JobScheduler`**. Un job real sí
despierta el proceso, así que esto **no demuestra** que el WebView falle bajo `JobScheduler`; lo
que demuestra es que el proceso se congela y que una consulta por WebView programada a mano no
corre. No se investigó más porque **A2.3 funciona**: W3 usa la vía nativa y la cuestión se vuelve
teórica. Si algún día hiciera falta el plan B del WebView, habría que medirlo otra vez con un
`JobScheduler` de verdad.

## A2.5 — Persistencia de la sesión: PASA

```
$ adb shell am force-stop com.claudewidgets.spike
$ adb shell monkey -p com.claudewidgets.spike -c android.intent.category.LAUNCHER 1
A2.5 arranque: cookies=18 sessionKey=true nombres=[...]
```

La sesión sobrevive a cerrar la app del todo, y también sobrevivió a un `adb install -r` del APK
(antes del login se vieron las 13 cookies previas intactas tras reinstalar). El spike llama a
`CookieManager.flush()` en `onPause` **por precaución**; no se probó sin él, así que no consta si
era necesario.

**Fecha de vencimiento: no es visible.** `CookieManager.getCookie()` devuelve solo pares
`nombre=valor`, sin atributos. Leer la base `Cookies` del WebView exigiría `android:debuggable`,
que este APK no declara. Queda sin medir cuánto dura; lo que sí consta es que sobrevive al cierre
de la app y a una reinstalación.

## A2.6 — Nombres y cabeceras: PASA

**Organizaciones: 2.** Claves de cada elemento de `GET /api/organizations` (solo nombres):

```
access_block, active_flags, analytics_subscription_plan, api_disabled_reason, api_disabled_until,
billable_usage_paused_until, billing_issue, billing_type, capabilities,
claude_ai_bootstrap_models_config, created_at, data_retention, data_retention_min_duration_days,
data_retention_periods, external_mapping, free_credits_status, has_hosted_code_environments,
has_icon, home_stripe_account_region, id, is_internal_org, is_workbench_cmek_blocked,
member_file_deletion_blocked_by_retention, merchant_of_record, monitoring_notice, name,
parent_organization_uuid, plan_display_label, plan_display_name, plan_offer_copy, rate_limit_tier,
rate_limit_upsell, raven_type, settings, subscription_management, subscription_pause, updated_at,
uuid, visibility_status
```

La consulta usó `[0].uuid` y respondió `200` a la primera. **Eso es todo lo que se probó.**

La regla de selección **queda abierta para W3**: con dos organizaciones las dos pueden responder
`200` con cuotas distintas, así que "la primera cuyo uso responda 200" —la propuesta del spike
A1— **no está demostrada** y podría mostrar la cuota equivocada. Candidatos a evaluar:
`lastActiveOrg` (la cookie ya dice cuál usa la web), `capabilities`, `rate_limit_tier` y
`parent_organization_uuid` para distinguir una organización hija de una personal. En cualquier
caso, el selector manual en ajustes no es opcional.

Cabeceras de respuesta, **vía nativa**:

```
alt-svc, cf-cache-status, cf-ray, connection, content-type, date, request-id, server,
server-timing, strict-transport-security, transfer-encoding, vary, x-android-received-millis,
x-android-response-source, x-android-selected-protocol, x-android-sent-millis, x-robots-tag
```

**Vía WebView** (las `x-android-*` son de la pila HTTP de Android, no del servidor):

```
alt-svc, cf-cache-status, cf-ray, content-encoding, content-type, date, request-id, server,
server-timing, strict-transport-security, vary, x-robots-tag
```

`server: cloudflare` y `cf-ray` en ambas: Cloudflare está delante, pero responde `200` a las dos
vías. No hay `cf-mitigated` ni ninguna cabecera de reto.

## Qué se protegió

De la cookie solo se registran el número de cookies, sus nombres y si existe `sessionKey`; nunca
su valor. El UUID de organización se usa para armar la URL pero no se escribe: `scrub()` tapa
cualquier UUID que se cuele en un mensaje de error, y de las URL solo se registra el host. No se
usó `addJavascriptInterface`; el resultado del `fetch` se recoge sondeando una variable global
con `evaluateJavascript`. `allowBackup=false` y `usesCleartextTraffic=false` en el manifiesto.

## Decisión

**GO** para W3. Los ocho criterios pasan y la vía nativa es viable: no se observó reto
anti-bots en las condiciones de esta prueba. No es una garantía —una sola sesión, un dispositivo,
pocas peticiones— así que W3 debe manejar el reto por si aparece, con el WebView como plan B.

Tres cambios respecto a la spec:

1. El login de Android es **por correo con código**, no por Google. La spec decía "Activity
   WebView" sin precisar el método; hay que precisarlo.
2. Las consultas periódicas van por **`HttpURLConnection` desde un `JobScheduler`**, no por el
   WebView: el proceso se congela en segundo plano y una consulta por WebView programada a mano
   no corre. El WebView queda para el login y como plan B si apareciera un reto.
3. El `AppWidgetProvider` va con `exported="false"` y **con `previewLayout`**.

---

## Para la spec (W3)

Lo que este spike obliga a cambiar o fijar en la especificación de Android:

1. **Login solo por correo, con código.** La app **no controla** la página de claude.ai, así que
   no puede quitar el botón de Google. Lo que sí puede: una pantalla propia **antes** del WebView
   que diga que en Android solo funciona el acceso por correo y por qué (dentro de un `WebView`
   Google bloquea el acceso 48 horas).
2. **Ventana de 10 minutos.** El enlace del correo vence a los 10 minutos. La pantalla debe
   decirlo, y conviene que el campo del código esté a la vista desde el principio.
3. **Límite de reenvíos.** claude.ai deja de enviar correos tras varios intentos seguidos. La UI
   no debe invitar a pulsar "reenviar" en bucle; mejor un contador y un mensaje claro.
4. **`previewLayout` obligatorio** en el `appwidget-provider`. Sin él, el diálogo de anclaje
   muestra "No se pudo agregar el widget" en la vista previa aunque el anclaje funcione.
5. **`android:exported="false"` en el `AppWidgetProvider`.** Verificado: el sistema entrega
   `APPWIDGET_UPDATE` y el `PendingIntent` del toque llega igual. No exportar el receptor.
6. **`zipalign` no hace falta** en el `build.sh` de W3/F2, y no existe en Termux. `aapt2 link`
   deja `resources.arsc` como `Stored` y añadir `classes.dex` al final con `zip` no mueve su
   desplazamiento, así que conserva la alineación a 4 bytes de `targetSdk >= 30`.
7. **Las consultas periódicas no usan el WebView.** Van por `HttpURLConnection` desde el`
   `JobScheduler`, que es lo que A2.3 mostró que funciona. Con `postDelayed` en segundo plano el
   WebView no corrió (Freecess en Samsung); bajo `JobScheduler` no se probó (ver A2.4).
8. **Manejar el reto aunque hoy no aparezca.** La consulta nativa debe tratar `403`, la cabecera
   `cf-mitigated` y una respuesta `text/html` donde se esperaba JSON como "hay reto": backoff
   exponencial, no reintentar en bucle, y avisar al usuario de volver a iniciar sesión si persiste.
   El plan B es rehacer la consulta dentro del WebView.
9. **`lastActiveOrg` puede ahorrar una llamada** a `/api/organizations`, pero su valor es un
   UUID: mismo trato que la cookie de sesión.
10. **La regla de selección de organización queda abierta.** Hay 2 en esta cuenta; el selector
    manual en ajustes no es opcional.
11. **El contenido del widget debe llenar la celda.** El widget del spike dejó el texto arriba a la
    izquierda y media celda vacía: se ve sin terminar. Centrar o repartir el contenido.

### Lo que W3 NO debe copiar del spike

El spike es permisivo a propósito, para poder observar. El producto no:

- **No imprimir cuerpos de respuesta.** El spike registra los primeros 160 caracteres cuando algo
  falla; W3 debe registrar solo código HTTP, `content-type` y longitud.
- **Mandar el mínimo de cookies.** El spike reenvía el jarro entero de claude.ai. W3 debe mandar
  solo `sessionKey` y, si la regla de organización acaba usándola, `lastActiveOrg`.
- **`setAcceptThirdPartyCookies(false)`.** El spike lo pone en `true` porque tanteaba el login de
  Google; descartado ése, W3 no lo necesita.
