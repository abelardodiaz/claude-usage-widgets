# Changelog

Cambios notables por version. Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.0.0/).
El detalle de cada cambio esta en su PR; los binarios, en
[Releases](https://github.com/abelardodiaz/claude-usage-widgets/releases).

## [Sin publicar]

- Backlog con los cuatro detalles menores de la version lite que dejo la revision del PR #43 (#45).

## [0.1.4] - 2026-10-10

### Agregado
- Version lite nativa para Windows: Win32 + GDI, sin WebView (#43).
- Politica de firma de codigo con SignPath, y la pagina de descarga la menciona (#39, #40).

### Documentado
- Plan de W3 con la entrada de F5 y la regla N2 con guarda estatica (#41).

## [0.1.3] - 2026-10-10

### Agregado
- Widget de Android funcionando en el aparato (W3 F4, #36).
- Pagina del proyecto en GitHub Pages (#37).

### Cambiado
- Mas contraste en el texto secundario (#35).

## [0.1.2] - 2026-10-09

### Cambiado
- Contrato R7: el relleno de hoy se calcula en el nucleo, cuota 0 en rojo y grafica con `today_used` (#33).

## [0.1.1] - 2026-10-09

### Agregado
- Login en Android con sesion cifrada (W3 F3, #26).
- Leyenda del cupo de hoy en el encabezado del historial (#28).

### Cambiado
- Contrato R4: "hoy" empieza en el reinicio semanal si ocurrio hoy (#30).
- La barra de hoy se parte tras el reinicio semanal; textos a 11 px y categoria "Otros" (#31).

## [0.1.0] - 2026-10-09

Primera version publicada para Windows.

### Agregado
- **Contrato** (`spec/`): esquema JSON, reglas R0-R7, fixtures (incluidos horario de verano,
  dato rancio, cuota agotada y anio 9999) y validacion en CI (#2, #5, #6, #12, #17, #18).
- **Windows** (W1): esqueleto Tauri, nucleo Rust que pasa los fixtures, fuente `ClaudeCodeSource`,
  UI del widget, bandeja y ventana, release y actualizacion manual (#13, #16, #19, #22, #25, #27).
- **Android** (W3): nucleo del contrato en Java y build del APK sin Gradle con lint NewApi (#11, #21).
- Spikes de W0: endpoint de uso de claude.ai y via nativa en Android (#3, #4).
- Base del repo: seguridad, guia para Claude, dependabot, roadmap por oleadas (#1, #10).
