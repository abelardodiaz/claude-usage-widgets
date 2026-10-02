# claude-usage-widgets

Widgets siempre visibles con tu uso de Claude: sesion de 5 h, semana, limites por modelo,
cuanto llevas hoy contra lo que te toca, desglose y proyeccion de cuando llegarias al limite.

*Always-visible widgets showing your Claude plan usage (5-hour session, weekly, per-model
limits, today's budget, breakdown and projection) for Windows, Linux and Android.*

> **Estado:** en diseno. Ver [docs/superpowers/specs](docs/superpowers/specs/).
> `prototype/` es una prueba de concepto funcional (Windows, Python + pywebview).

| Plataforma | Estado |
|---|---|
| Windows | prototipo funcional |
| Linux / macOS | planeado |
| Android (widget) | planeado |
| Android (burbuja flotante) | planeado |

## Probar el prototipo (Windows, requiere Claude Code y uv)

```bash
cd prototype
uv run widget.py
```

**Proyecto no oficial, no afiliado a Anthropic.** Usa endpoints no documentados que pueden
cambiar sin aviso. Tu credencial nunca sale de tu equipo.

Licencia MIT.
