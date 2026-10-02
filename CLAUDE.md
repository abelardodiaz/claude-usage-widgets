# claude-usage-widgets — Guía para Claude Code

Antes de tocar código lee, en este orden:
1. `docs/superpowers/specs/2026-10-02-claude-usage-widgets-design.md` (diseño)
2. `docs/superpowers/plans/2026-10-02-roadmap.md` (oleadas, convenciones, cierre de fase)
3. El plan detallado de la oleada en curso en `docs/superpowers/plans/`
4. `SECURITY.md` (reglas no negociables)

Reglas:
- Idioma: español en docs, commits y comentarios; identificadores de código en inglés.
- Repo PÚBLICO: nunca commitear tokens, cookies, IPs, hosts privados ni rutas personales.
  `docs/incoming/`, `docs/procesados/` y `CLAUDE.local.md` están fuera de git a propósito.
- Una rama por fase, PR a `main`. No push directo a `main`.
- Los fixtures de `spec/fixtures/` son la verdad: si una implementación no los pasa, se corrige
  la implementación. Cambiar un fixture requiere justificarlo en el PR.
- Scripts (bash, PowerShell, Python) solo en ASCII: sin emojis ni acentos, se ejecutan también
  en consolas de Windows.
- TDD: primero el test que falla, luego el código.
