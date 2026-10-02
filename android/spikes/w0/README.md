# Spike W0 — A2 (login claude.ai en WebView) + B (AppWidget)

Código **desechable**: responde preguntas, no es la base de W3. Lo que se aprende aquí vive en
`docs/spikes/2026-10-w0-a2-b-android.md`; esto es solo la prueba que lo produjo.

## Construir

```bash
bash android/spikes/w0/build.sh
adb install -r android/spikes/w0/build/spike-w0.apk
```

Requiere en el PATH: `aapt2`, `javac` (JDK 17+), `d8`, `apksigner`, `keytool`, `zip`, y
`~/android/platforms/android-34/android.jar` (o `ANDROID_JAR=...`). Sin Gradle y sin androidx.
La llave de depuración se crea sola en `~/.android-spike-debug.keystore`, fuera del repo.

## Qué hace

Cinco botones y una bitácora en pantalla (también en `logcat -s SpikeW0:I`):

| Botón | Criterio | Qué prueba |
|---|---|---|
| Login | A2.1 | carga `https://claude.ai/login` en el WebView |
| Cookie | A2.2 / A2.5 | cuántas cookies hay y si existe `sessionKey` |
| Nativo | A2.3 / A2.6 | `HttpURLConnection` a `/api/organizations` y a `/usage` |
| WebView | A2.4 / A2.6 | el mismo par de consultas con `fetch(..., {credentials:'include'})` |
| Widget | B.1 | pide al lanzador que ancle el widget "hola" |

De la cookie y del UUID de organización solo se registran metadatos (cuántas, cómo se llaman).
Sus valores no se escriben nunca: `scrub()` tapa cualquier UUID que se cuele en un mensaje de
error, y de las URL solo se registra el host.
