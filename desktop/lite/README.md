# claude-usage-widgets lite (Windows)

Versión mínima y nativa del widget para Windows: una ventana Win32 dibujada con GDI, sin WebView
y sin Tauri. Muestra solo lo esencial del uso de tu plan de Claude:

- Dos filas, **Sesión** (5 h) y **Semana**, cada una con su porcentaje, su barra y cuándo se
  reinicia. En la barra de la semana, una marca de ritmo indica cuánto de la semana ha
  transcurrido: si la barra pasa de la marca, vas más rápido que un ritmo parejo.
- Un pie con la hora de la última actualización o el error del momento (token vencido, sin red,
  límite de consultas).
- Icono en la bandeja. Clic izquierdo muestra u oculta la ventana; clic derecho abre el menú:
  mostrar/ocultar, actualizar, **Intervalo** (3 o 5 min), **Siempre encima**,
  **Iniciar con Windows** y salir.
- Avisos de la semana en la bandeja al llegar al **50, 80 y 95 %**: uno por umbral y por semana.
  Si de golpe se cruzan varios (por ejemplo al arrancar), solo avisa el más alto.
- Una sola instancia, recuerda la posición (vuelve arriba a la derecha si el monitor ya no
  está), DPI por monitor, español e inglés según el idioma de Windows.

## En qué se diferencia del widget Tauri

| | Widget (Tauri) | Lite |
|---|---|---|
| Interfaz | WebView2 + HTML | Win32 + GDI |
| Procesos | 7 (app + WebView2) | 1 |
| Memoria (medida el 2026-10-10) | ~29 MB | ~3 MB |
| Contenido | hoy, sesión, semana, modelos, desglose, proyección | sesión y semana |
| Instalador y actualizaciones | `.msi` / `-setup.exe`, actualización firmada | `.exe` portable, sin updater |
| Plataformas | Windows (y Linux en CI) | solo Windows |

La lite no tiene "Buscar actualizaciones": para cambiar de versión se descarga el `.exe` nuevo
del Release.

## Descargar

Cada Release de Windows (tag `vX.Y.Z`) incluye `claude-usage-widgets-lite_<versión>_x64.exe`,
con su línea en `SHA256SUMS.txt`. La versión de la lite es la misma que la de la app: el
workflow de release exige que `desktop/lite/Cargo.toml` coincida con el tag.

No está firmado (igual que los instaladores, la firma con SignPath Foundation está en
trámite), así que SmartScreen puede avisar y algún antivirus por aprendizaje automático puede
marcarlo. Compara el SHA-256 antes de ejecutarlo:
`certutil -hashfile claude-usage-widgets-lite_<versión>_x64.exe SHA256`.

## Compilar

Requiere Rust estable (1.90 o más reciente) en Windows.

```powershell
cd desktop\lite
cargo build --release
# el exe queda en target\release\claude-usage-widgets-lite.exe
cargo fmt --all --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked
```

`cargo test` corre las pruebas del núcleo compartido y las propias de la lite.

### Instalar desde el código

```powershell
powershell -ExecutionPolicy Bypass -File desktop\lite\instalar.ps1
```

El script compila en release (en `CARGO_TARGET_DIR` o, si no está definido, en
`%TEMP%\cuw-lite-target`), cierra la lite que esté corriendo, copia el exe a
`%LOCALAPPDATA%\claude-usage-widgets-lite\claude-usage-widgets-lite.exe` y la arranca desde ahí.
"Iniciar con Windows" apunta a esa ruta fija.

## Datos y preferencias

- Muestras del servicio y preferencias (`settings.json`: intervalo, siempre encima, posición y
  avisos ya mostrados) en `%LOCALAPPDATA%\io.github.abelardodiaz.claude-usage-widgets-lite\`.
  Solo porcentajes, fechas y ajustes; ninguna credencial.
- "Iniciar con Windows" es un valor en
  `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`; el menú lo lee de ahí cada vez.
- Para desinstalar: desmarcar "Iniciar con Windows", salir y borrar las dos carpetas.

## Qué reutiliza del widget Tauri

El núcleo no se reescribe. `src/main.rs` compila tal cual, con `#[path]`, estos módulos de
`desktop/src-tauri/src`: `backoff`, `colors`, `credentials`, `history`, `i18n`, `model`, `parse`,
`projection`, `service`, `source_claude_code`, `store`, `timez` y `view`. Así la lite lee el
token igual que la app, respeta el mismo intervalo mínimo y backoff, y calcula los mismos
colores y porcentajes.

Esos módulos **tienen que seguir libres de tauri**: nada de `use tauri` ni dependencias que este
crate no declare. La integración con Tauri va en `lib.rs` de src-tauri. El job
`lite (windows-latest)` del CI compila la lite en cada PR, así que un cambio en el núcleo que la
rompa se ve antes de fusionar.

## Seguridad

Las mismas reglas de [SECURITY.md](../../SECURITY.md): solo habla con `api.anthropic.com`, lee
el token de Claude Code en cada consulta sin copiarlo, refrescarlo ni escribirlo en logs, y no
tiene telemetría. Al no tener updater, ni siquiera contacta `github.com`.
