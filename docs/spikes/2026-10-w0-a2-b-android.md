# Spike A2 + B — Android (WebView de claude.ai y AppWidget)

- **Fecha:** 2026-10-02
- **Dispositivo:** Samsung SM-S948B, Android 17 (SDK 37), parche 2026-09-05.
- **Toolchain:** Termux. `aapt2` 2.20-android-16.0.0_r4, `javac`/OpenJDK 21.0.12, `d8` 9.2.4-dev,
  `apksigner` 0.9, `android.jar` de `~/android/platforms/android-34`. Sin Gradle, sin androidx.
- **Código:** `android/spikes/w0/` (desechable; no es la base de W3).
- **Resultado:** **GO para la parte Android**, con un cambio obligatorio en el login (ver A2.1).
  B queda cerrado. De A2 falta la mitad que depende de una sesión viva (A2.2–A2.6).

## Resumen por criterio

| Criterio | Estado | En una línea |
|---|---|---|
| A2.1 WebView carga `claude.ai/login` | **PASA** (con hallazgo) | Carga y permite iniciar sesión, pero **el botón de Google no sirve** |
| A2.2 Cookie no nula | **PENDIENTE** | Falta sesión; la sonda está escrita y probada en negativo |
| A2.3 Consulta nativa | **PENDIENTE** | Idem |
| A2.4 Consulta por `evaluateJavascript` | **PENDIENTE** | Idem |
| A2.5 Persistencia de la sesión | **PENDIENTE** | Idem |
| A2.6 Nombres de claves y cabeceras | **PENDIENTE** | Idem |
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
funciona. El sistema entrega igual `APPWIDGET_UPDATE` (broadcast dirigido) y el `PendingIntent`
propio del toque también llega, porque corre con la identidad de la app. **No hay que exportar
el receptor**, lo que encaja con la regla 5 de `SECURITY.md`.

**Hallazgo menor:** el diálogo de anclaje muestra "No se pudo agregar el widget" en la vista
previa porque el `appwidget-provider` no declara `previewLayout` ni `previewImage`. El anclaje
funciona igual, pero W3 debe declarar `previewLayout` para no asustar al usuario.

## A2.1 — Login en el WebView: PASA, con un hallazgo que cambia el diseño

`https://claude.ai/login` carga y funciona dentro de un `WebView` con `setJavaScriptEnabled(true)`
y `setDomStorageEnabled(true)`. User-Agent que anuncia:

```
Mozilla/5.0 (Linux; Android 17; SM-S948B Build/CP2A.260605.016; wv)
AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36
```

### El botón "Continuar con Google" NO es viable en un WebView

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

## A2.2 – A2.6 — PENDIENTE

Las cuatro sondas están escritas y **verificadas en negativo** en el dispositivo: antes de
cualquier login, con el APK recién instalado, dieron exactamente lo que debían dar:

```
A2.5 arranque: cookie=null (sin sesion)
A2.2: cookie=null (sin sesion)
A2.3: cookie=null, inicia sesion primero
A2.4 webview: {"error":"TypeError: Failed to fetch"}
```

Eso prueba que miden algo real, pero **no sustituye al resultado positivo**: sigue sin saberse
si una petición nativa con la cookie recibe un reto anti-bots (riesgo 1 del spike A1), ni cuánto
dura la sesión (riesgo 3), ni los nombres de clave de `/api/organizations` (riesgo 4).

**Para cerrarlos** (unos 2 minutos, sin tocar código): abrir Spike W0, botón **Login**, acceder
con el correo **y meter el código antes de que pasen 10 minutos**; luego **Cookie**, **Nativo**,
**WebView**; después cerrar y reabrir la app para A2.5. Todo queda en `logcat -s SpikeW0:I`.

## Qué se protegió

De la cookie solo se registran el número de cookies, sus nombres y si existe `sessionKey`; nunca
su valor. El UUID de organización se usa para armar la URL pero no se escribe: `scrub()` tapa
cualquier UUID que se cuele en un mensaje de error, y de las URL solo se registra el host. No se
usó `addJavascriptInterface`; el resultado del `fetch` se recoge sondeando una variable global
con `evaluateJavascript`. `allowBackup=false` y `usesCleartextTraffic=false` en el manifiesto.

## Decisión

**GO** para W3 con dos cambios respecto a la spec:

1. El login de Android es **por correo**, no por Google. La spec decía "Activity WebView" sin
   precisar el método; hay que precisarlo.
2. El `AppWidgetProvider` va con `exported="false"` y **con `previewLayout`**.

Queda abierto, para cerrar en cuanto haya sesión: el riesgo anti-bots de la vía nativa (A2.3),
que es lo único que podría obligar a enrutar todas las consultas por el WebView.

---

## Para la spec (W3)

Lo que este spike obliga a cambiar o fijar en la especificación de Android:

1. **Login solo por correo, con código.** El botón "Continuar con Google" se retira de la pantalla
   de Android: dentro de un `WebView` Google bloquea el acceso 48 horas. La pantalla debe ofrecer
   únicamente el acceso por correo y explicarlo antes de que el usuario se tope con el muro.
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
7. **El contenido del widget debe llenar la celda.** El widget del spike dejó el texto arriba a la
   izquierda y media celda vacía: se ve sin terminar. Centrar o repartir el contenido.
