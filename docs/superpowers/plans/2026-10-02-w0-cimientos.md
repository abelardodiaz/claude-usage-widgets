# W0 Cimientos — Plan detallado

> **Para agentes:** SUB-SKILL REQUERIDA: usar superpowers:subagent-driven-development (recomendado)
> o superpowers:executing-plans para ejecutar este plan tarea por tarea. Los pasos usan casillas
> (`- [ ]`) para seguimiento.

**Objetivo:** dejar el repo listo para construir (seguridad, CI, contrato compartido) y eliminar
los dos riesgos que pueden tumbar la parte Android antes de invertir en ella.

**Arquitectura:** el contrato en `spec/` (esquema + reglas + fixtures con valores calculados a mano)
es la fuente de verdad que Rust (W1) y Java (W3) deben reproducir. Los spikes viven en una rama
propia y solo producen conocimiento (un reporte), no código de producto.

**Stack:** GitHub Actions, Python 3.12 + `jsonschema` (solo para validar el contrato), Claude in
Chrome (spike A1), Java + aapt2/d8/apksigner en Termux (spike A2+B).

**Convenciones:** ver `2026-10-02-roadmap.md` (ramas, cierre de fase, memos).

---

## F1 — Repo base (PC) · rama `w0/f1-repo-base`

### Tarea 1.1: Crear la rama

- [ ] **Paso 1:**
```bash
cd claude-usage-widgets
git checkout -b w0/f1-repo-base
```

### Tarea 1.2: SECURITY.md

**Archivos:** Crear `SECURITY.md`

- [ ] **Paso 1: Escribir el archivo**

```markdown
# Seguridad

## Qué protege este proyecto

claude-usage-widgets maneja una credencial que da acceso **total** a tu cuenta de Claude:
el token OAuth de Claude Code (escritorio) o la sesión de claude.ai (Android y respaldo de
escritorio). Estas reglas no son negociables y cada fase se revisa contra ellas.

1. La credencial no sale de tu dispositivo. Nunca se escribe en logs, en el historial ni en
   mensajes de error.
2. Red: solo `api.anthropic.com` y `claude.ai`. Cero telemetría, cero analíticas, cero
   servidores del proyecto.
3. Almacenamiento: solo en el almacén seguro del sistema (Credential Manager, Secret Service,
   Keychain, Android Keystore). El token de Claude Code no se copia: se lee en cada consulta
   y **nunca se refresca** (refrescarlo invalidaría el de Claude Code).
4. Escritorio (Tauri): CSP estricta, sin APIs de `shell` ni `fs` expuestas a la UI. La UI solo
   recibe el modelo normalizado de uso, nunca credenciales.
5. Android: el WebView se usa solo para iniciar sesión; sin `addJavascriptInterface`,
   `usesCleartextTraffic=false`, `allowBackup=false`.
6. Releases firmados, con checksums SHA-256 publicados. Dependencias fijadas en lockfiles y
   vigiladas por Dependabot.
7. La única fuente oficial de instaladores es la página de Releases de este repositorio.

## Reportar una vulnerabilidad

Usa **Security → Report a vulnerability** (GitHub Security Advisories) en este repositorio.
No abras un issue público. Respuesta en menos de 7 días.

## Aviso

Proyecto no oficial, no afiliado a Anthropic. Usa endpoints no documentados que pueden cambiar
o dejar de funcionar sin aviso.
```

- [ ] **Paso 2: Commit**
```bash
git add SECURITY.md
git commit -m "docs: politica de seguridad"
```

### Tarea 1.3: CLAUDE.md del repo y .gitignore

**Archivos:** Crear `CLAUDE.md`; Modificar `.gitignore`

- [ ] **Paso 1: Escribir `CLAUDE.md`**

```markdown
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
```

- [ ] **Paso 2: Agregar a `.gitignore`** (al final)

```
# Rol local de cada sesion de Claude Code (no va al repo publico)
CLAUDE.local.md
docs/outbox/*
```

- [ ] **Paso 3: Commit**
```bash
git add CLAUDE.md .gitignore
git commit -m "docs: guia para Claude Code y exclusiones locales"
```

### Tarea 1.4: Plantilla de revisión Fable

**Archivos:** Crear `docs/superpowers/plans/revision-fable.md`

- [ ] **Paso 1: Escribir el archivo**

````markdown
# Revisión Fable de cierre de fase

Se ejecuta desde la sesión PC al terminar cada fase, antes del merge. Llamada al tool Agent:

- `subagent_type`: `general-purpose`
- `model`: `fable`
- `description`: `Revision Fable wN fM`
- `prompt`: el texto de abajo con `{RAMA}`, `{FASE}` y `{PLAN}` sustituidos.

```text
Eres revisor de cierre de fase del repo publico claude-usage-widgets (directorio actual).
Fase: {FASE}. Rama: {RAMA}. Plan: {PLAN}.

Lee: la spec (docs/superpowers/specs/2026-10-02-claude-usage-widgets-design.md), SECURITY.md,
el plan indicado y el diff completo: `git diff main...{RAMA}`.

Revisa, en este orden:
1. Seguridad: cada regla de SECURITY.md. Busca credenciales en logs, errores, historial,
   commits; dominios fuera de la lista blanca; datos personales o de infraestructura en el repo.
2. Conformidad: lo implementado cumple la spec y las tareas de la fase; nada de alcance extra.
3. Correccion: errores de logica, casos borde (sin red, 401, 429, reinicio semanal, medianoche,
   zona horaria), condiciones de carrera.
4. Pruebas: los tests prueban comportamiento real; ningun fixture de spec/ fue alterado para
   que un test pase; los tests corren en CI.

Formato de respuesta:
- Lista de hallazgos, cada uno con severidad (CRITICO | IMPORTANTE | MENOR), archivo:linea,
  el problema en una frase y la correccion sugerida.
- Ultima linea: VEREDICTO: APROBADO o VEREDICTO: CAMBIOS.
No modifiques archivos.
```

Regla: CRITICO e IMPORTANTE se corrigen en la misma rama y se vuelve a revisar solo lo
corregido. MENOR puede quedar como issue enlazado en el PR.
````

- [ ] **Paso 2: Commit**
```bash
git add docs/superpowers/plans/revision-fable.md
git commit -m "docs: plantilla de revision Fable de cierre de fase"
```

### Tarea 1.5: Dependabot

**Archivos:** Crear `.github/dependabot.yml`

- [ ] **Paso 1:**
```yaml
version: 2
updates:
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
```
(Los ecosistemas `cargo` y `npm` se agregan en W1 cuando existan sus manifiestos.)

- [ ] **Paso 2: Commit**
```bash
git add .github/dependabot.yml
git commit -m "ci: dependabot para actions"
```

### Tarea 1.6: Push, PR y cierre de F1

- [ ] **Paso 1:** `git push -u origin w0/f1-repo-base` y `gh pr create --fill --base main`
- [ ] **Paso 2:** Revisión Fable con la plantilla (`{FASE}`=W0 F1). Corregir CRITICO/IMPORTANTE.
- [ ] **Paso 3:** `gh pr merge --squash --delete-branch`
- [ ] **Paso 4:** marcar F1 en el roadmap (va en el PR de F2).

(La protección de `main` se activa en la Tarea 2.7, cuando ya exista el check de CI.)

---

## F2 — Contrato (PC) · rama `w0/f2-contrato`

Zona horaria de todos los fixtures: `-06:00` fija. "Día" = fecha local en esa zona.
Comparación numérica con tolerancia `0.001`; fechas como instantes con tolerancia de 1 s.

### Tarea 2.1: Esquema del modelo normalizado

**Archivos:** Crear `spec/usage-model.schema.json`

- [ ] **Paso 1:** `git checkout main && git pull && git checkout -b w0/f2-contrato`
- [ ] **Paso 2: Escribir el esquema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://github.com/abelardodiaz/claude-usage-widgets/spec/usage-model.schema.json",
  "title": "Usage",
  "type": "object",
  "required": ["session", "weekly", "scoped", "breakdown", "source"],
  "additionalProperties": false,
  "properties": {
    "fetched_at": { "type": "string", "format": "date-time" },
    "source": { "enum": ["claude_code", "claude_ai"] },
    "session": { "$ref": "#/$defs/window" },
    "weekly": { "$ref": "#/$defs/window" },
    "scoped": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["label", "percent", "resets_at"],
        "additionalProperties": false,
        "properties": {
          "label": { "type": "string", "minLength": 1 },
          "percent": { "type": "number" },
          "resets_at": { "type": ["string", "null"], "format": "date-time" }
        }
      }
    },
    "breakdown": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "label", "percent"],
        "additionalProperties": false,
        "properties": {
          "key": { "type": "string" },
          "label": { "type": "string" },
          "percent": { "type": "number" }
        }
      }
    }
  },
  "$defs": {
    "window": {
      "type": "object",
      "required": ["percent", "resets_at"],
      "additionalProperties": false,
      "properties": {
        "percent": { "type": "number" },
        "resets_at": { "type": ["string", "null"], "format": "date-time" }
      }
    }
  }
}
```

### Tarea 2.2: Reglas de cálculo

**Archivos:** Crear `spec/rules.md`

- [ ] **Paso 1: Escribir el archivo**

```markdown
# Reglas de cálculo (contrato)

Toda implementación (Rust en desktop/, Java en android/core/) debe pasar los fixtures de
`spec/fixtures/`. Si este documento y un fixture discrepan, gana el fixture y se corrige el doc.

## R1. Parseo de la respuesta OAuth (`source = claude_code`)

Entrada: JSON de `GET https://api.anthropic.com/api/oauth/usage`.

- `session` ← `five_hour`: `percent = utilization`, `resets_at = resets_at`.
- `weekly` ← `seven_day`: igual.
- Si `five_hour` o `seven_day` falta, no es objeto, o su `utilization` no es número →
  error `unrecognized_format` (nunca se inventa un 0 %).
- `scoped` ← cada elemento de `limits[]` cuyo `kind` NO sea `session` ni `weekly_all` y cuyo
  `percent` sea número. `label` = `scope.model.display_name`, si no `scope.surface.display_name`,
  si no `kind`. Si `limits` falta o no es arreglo → `[]`.
- `breakdown` ← `seven_day_breakdown.rows[]`: `key`, `label = display_name`, `percent`.
  Si falta → `[]`.
- Claves desconocidas se ignoran.

(El parseo de `claude_ai` se define en la Tarea 3.3, tras el spike A1.)

## R2. Misma ventana

Dos muestras están en la misma ventana semanal si alguno de sus `resets_at` es nulo o si
difieren menos de 3600 s.

## R3. Consumo entre muestras consecutivas

Muestras ordenadas por tiempo `a`, `b`:
- misma ventana: `delta = b.percent − a.percent`
- otra ventana (hubo reinicio): `delta = b.percent`, y el intervalo empieza en
  `max(a.t, b.resets_at − 7 días)`
- `delta ≤ 0` → no aporta nada.
- El `delta` se reparte entre los días locales **proporcional al tiempo** del intervalo
  `[inicio, b.t]` que cae en cada día. Si el intervalo tiene duración 0, todo va al día de `b.t`.

## R4. Hoy

- `per_day[d]` = suma de lo repartido al día `d`.
- `today_used` = `per_day[hoy]` (0 si no hay).
- `base` = `max(weekly.percent − today_used, 0)`.
- `days_left` = `(weekly.resets_at − medianoche local de hoy)` en días (fraccionario).
- `quota_today` = `(100 − base) / max(days_left, 1)`.
- `partial` = no existe ninguna muestra anterior a la medianoche local de hoy.

## R5. Proyección de la sesión (ventana de 5 h)

- `start = resets_at − 5 h`, `elapsed = now − start`.
- `percent ≤ 0` o `elapsed < 60 s` → `hits_at = null`, `before_reset = null`, `basis = null`.
- `percent ≥ 100` → `hits_at = now`, `before_reset = true`, `basis = "window"`.
- Si no: `rate = percent / elapsed`, `hits_at = now + (100 − percent) / rate`,
  `before_reset = hits_at < resets_at`, `basis = "window"`.

## R6. Proyección de la semana

- Muestras candidatas: las de la misma ventana que `weekly` (R2) con `t ≥ now − 24 h`.
- `ref` = la más antigua de las candidatas. Si existe y `now − ref.t ≥ 1 h`:
  `rate = (percent − ref.percent) / (now − ref.t)`, `basis = "24h"`.
  - `rate ≤ 0` → `hits_at = null`, `before_reset = null`, `basis = "24h"`.
- Si no: igual que R5 pero con ventana de 7 días (`start = resets_at − 7 d`), `basis = "window"`.
- `percent ≥ 100` → `hits_at = now`, `before_reset = true` (con la `basis` que corresponda).
- `hits_at = now + (100 − percent) / rate`, `before_reset = hits_at < resets_at`.

## R7. Colores

- Barras de sesión, semana y limitados: verde `< 60`, ámbar `< 85`, rojo `≥ 85`.
- Barra de hoy, por `today_used / quota_today`: verde `< 0.7`, ámbar `< 1`, rojo `≥ 1`.
- Marca de ritmo parejo en la barra semanal: `1 − (resets_at − now) / 7 días`, acotada a [0, 1].
```

### Tarea 2.3: Fixtures de parseo

**Archivos:** Crear `spec/fixtures/parse/01-oauth-max.json`, `02-oauth-minimo.json`, `03-oauth-roto.json`

- [ ] **Paso 1: `01-oauth-max.json`** (respuesta real del 2026-10-02, recortada; conserva claves
  desconocidas a propósito para probar que se ignoran)

```json
{
  "description": "Respuesta OAuth real (plan Max) con limite por modelo, desglose y claves desconocidas",
  "source": "claude_code",
  "input": {
    "five_hour": { "utilization": 6.0, "resets_at": "2026-10-02T13:19:59.614725-06:00", "limit_dollars": null },
    "seven_day": { "utilization": 74.0, "resets_at": "2026-10-02T17:59:59.614747-06:00", "limit_dollars": null },
    "seven_day_opus": null,
    "iguana_necktie": { "utilization": 0.0, "resets_at": "2026-11-05T01:59:00-06:00", "limit_dollars": 250 },
    "extra_usage": { "is_enabled": false },
    "limits": [
      { "kind": "session", "group": "session", "percent": 6, "resets_at": "2026-10-02T13:19:59.614725-06:00", "scope": null },
      { "kind": "weekly_all", "group": "weekly", "percent": 74, "resets_at": "2026-10-02T17:59:59.614747-06:00", "scope": null },
      { "kind": "weekly_scoped", "group": "weekly", "percent": 26, "resets_at": "2026-10-02T17:59:59.614945-06:00",
        "scope": { "model": { "id": null, "display_name": "Fable" }, "surface": null } }
    ],
    "seven_day_breakdown": {
      "as_of": "2026-10-02T12:01:22.647357-06:00",
      "rows": [
        { "key": "claude_code", "display_name": "Claude Code", "percent": 100 },
        { "key": "chat", "display_name": "Chats", "percent": 0 },
        { "key": "cowork", "display_name": "Cowork", "percent": 0 },
        { "key": "other", "display_name": "Other", "percent": 0 }
      ]
    }
  },
  "expected": {
    "source": "claude_code",
    "session": { "percent": 6.0, "resets_at": "2026-10-02T13:19:59.614725-06:00" },
    "weekly": { "percent": 74.0, "resets_at": "2026-10-02T17:59:59.614747-06:00" },
    "scoped": [ { "label": "Fable", "percent": 26, "resets_at": "2026-10-02T17:59:59.614945-06:00" } ],
    "breakdown": [
      { "key": "claude_code", "label": "Claude Code", "percent": 100 },
      { "key": "chat", "label": "Chats", "percent": 0 },
      { "key": "cowork", "label": "Cowork", "percent": 0 },
      { "key": "other", "label": "Other", "percent": 0 }
    ]
  }
}
```

- [ ] **Paso 2: `02-oauth-minimo.json`**

```json
{
  "description": "Solo sesion y semana: sin limits ni desglose; resets nulos permitidos",
  "source": "claude_code",
  "input": {
    "five_hour": { "utilization": 0.0, "resets_at": null },
    "seven_day": { "utilization": 12.5, "resets_at": "2026-10-09T18:00:00-06:00" }
  },
  "expected": {
    "source": "claude_code",
    "session": { "percent": 0.0, "resets_at": null },
    "weekly": { "percent": 12.5, "resets_at": "2026-10-09T18:00:00-06:00" },
    "scoped": [],
    "breakdown": []
  }
}
```

- [ ] **Paso 3: `03-oauth-roto.json`**

```json
{
  "description": "La forma cambio: seven_day sin utilization numerica. Debe fallar, no inventar 0%",
  "source": "claude_code",
  "input": {
    "five_hour": { "utilization": 10.0, "resets_at": "2026-10-02T13:00:00-06:00" },
    "seven_day": { "used": "74%" }
  },
  "expected": { "error": "unrecognized_format" }
}
```

### Tarea 2.4: Fixtures de historial (R2–R4)

**Archivos:** Crear `spec/fixtures/history/01-simple.json` a `04-ultimo-dia.json`

Todas las muestras tienen la forma `{ "t", "percent", "resets_at" }`.

- [ ] **Paso 1: `01-simple.json`** — cruza medianoche: 22:00→02:00 son 4 h, 2 h por día → 2 y 2.
  Hoy: 2 + 5 = 7; base 28; faltan 3.75 días; cupo 72 / 3.75 = 19.2.

```json
{
  "description": "Una ventana, un intervalo cruza medianoche y se reparte proporcional",
  "input": {
    "tz": "-06:00",
    "now": "2026-10-06T12:00:00-06:00",
    "weekly": { "percent": 35, "resets_at": "2026-10-09T18:00:00-06:00" },
    "samples": [
      { "t": "2026-10-05T10:00:00-06:00", "percent": 20, "resets_at": "2026-10-09T18:00:00-06:00" },
      { "t": "2026-10-05T22:00:00-06:00", "percent": 26, "resets_at": "2026-10-09T18:00:00-06:00" },
      { "t": "2026-10-06T02:00:00-06:00", "percent": 30, "resets_at": "2026-10-09T18:00:00-06:00" },
      { "t": "2026-10-06T12:00:00-06:00", "percent": 35, "resets_at": "2026-10-09T18:00:00-06:00" }
    ]
  },
  "expected": {
    "per_day": { "2026-10-05": 8, "2026-10-06": 7 },
    "today_used": 7,
    "quota_today": 19.2,
    "partial": false
  }
}
```

- [ ] **Paso 2: `02-reinicio.json`** — reinicio a las 18:00 del 10-02. 16:00→20:00 cambia de
  ventana: delta 3 en [18:00, 20:00] → día 10-02. 20:00→06:00 son 10 h, delta 6: 4 h (2.4) al
  10-02 y 6 h (3.6) al 10-03. Día 10-02 = 4 + 3 + 2.4 = 9.4. Hoy 3.6; base 5.4; faltan 6.75 días;
  cupo 94.6 / 6.75 = 14.014815.

```json
{
  "description": "Reinicio semanal entre dos muestras: la segunda cuenta desde 0 a partir del inicio de la nueva ventana",
  "input": {
    "tz": "-06:00",
    "now": "2026-10-03T06:00:00-06:00",
    "weekly": { "percent": 9, "resets_at": "2026-10-09T18:00:00-06:00" },
    "samples": [
      { "t": "2026-10-02T12:00:00-06:00", "percent": 70, "resets_at": "2026-10-02T18:00:00-06:00" },
      { "t": "2026-10-02T16:00:00-06:00", "percent": 74, "resets_at": "2026-10-02T18:00:00-06:00" },
      { "t": "2026-10-02T20:00:00-06:00", "percent": 3, "resets_at": "2026-10-09T18:00:00-06:00" },
      { "t": "2026-10-03T06:00:00-06:00", "percent": 9, "resets_at": "2026-10-09T18:00:00-06:00" }
    ]
  },
  "expected": {
    "per_day": { "2026-10-02": 9.4, "2026-10-03": 3.6 },
    "today_used": 3.6,
    "quota_today": 14.014815,
    "partial": false
  }
}
```

- [ ] **Paso 3: `03-parcial.json`** — una sola muestra hoy: nada consumido, cupo 50 / 3.75.

```json
{
  "description": "Primera muestra de la historia: hoy es parcial",
  "input": {
    "tz": "-06:00",
    "now": "2026-10-06T09:00:00-06:00",
    "weekly": { "percent": 50, "resets_at": "2026-10-09T18:00:00-06:00" },
    "samples": [
      { "t": "2026-10-06T09:00:00-06:00", "percent": 50, "resets_at": "2026-10-09T18:00:00-06:00" }
    ]
  },
  "expected": {
    "per_day": {},
    "today_used": 0,
    "quota_today": 13.333333,
    "partial": true
  }
}
```

- [ ] **Paso 4: `04-ultimo-dia.json`** — 20:00→08:00 son 12 h, delta 5: 4 h (1.666667) al 10-01
  y 8 h (3.333333) al 10-02; más 4 → hoy 7.333333. Faltan 0.75 días → se usa 1; base 66.666667;
  cupo 33.333333.

```json
{
  "description": "Ultimo dia de la ventana: days_left < 1 se acota a 1",
  "input": {
    "tz": "-06:00",
    "now": "2026-10-02T12:00:00-06:00",
    "weekly": { "percent": 74, "resets_at": "2026-10-02T18:00:00-06:00" },
    "samples": [
      { "t": "2026-10-01T20:00:00-06:00", "percent": 65, "resets_at": "2026-10-02T18:00:00-06:00" },
      { "t": "2026-10-02T08:00:00-06:00", "percent": 70, "resets_at": "2026-10-02T18:00:00-06:00" },
      { "t": "2026-10-02T12:00:00-06:00", "percent": 74, "resets_at": "2026-10-02T18:00:00-06:00" }
    ]
  },
  "expected": {
    "per_day": { "2026-10-01": 1.666667, "2026-10-02": 7.333333 },
    "today_used": 7.333333,
    "quota_today": 33.333333,
    "partial": false
  }
}
```

### Tarea 2.5: Fixtures de proyección (R5–R6)

**Archivos:** Crear `spec/fixtures/projection/01-sesion-alcanza.json` a `07-semana-sin-consumo.json`

- [ ] **Paso 1: `01-sesion-alcanza.json`** — inicio 10:00, 2 h para 50 % → 25 %/h; faltan 50 → 14:00.

```json
{
  "description": "Sesion: al ritmo actual llega al 100% antes del reinicio",
  "input": { "kind": "session", "now": "2026-10-02T12:00:00-06:00", "percent": 50,
             "resets_at": "2026-10-02T15:00:00-06:00", "samples": [] },
  "expected": { "hits_at": "2026-10-02T14:00:00-06:00", "before_reset": true, "basis": "window" }
}
```

- [ ] **Paso 2: `02-sesion-cero.json`**

```json
{
  "description": "Sesion sin uso: no se proyecta",
  "input": { "kind": "session", "now": "2026-10-02T12:00:00-06:00", "percent": 0,
             "resets_at": "2026-10-02T15:00:00-06:00", "samples": [] },
  "expected": { "hits_at": null, "before_reset": null, "basis": null }
}
```

- [ ] **Paso 3: `03-sesion-lenta.json`** — 5 %/h; faltan 90 → 18 h → 06:00 del día siguiente.

```json
{
  "description": "Sesion: el 100% caeria despues del reinicio",
  "input": { "kind": "session", "now": "2026-10-02T12:00:00-06:00", "percent": 10,
             "resets_at": "2026-10-02T15:00:00-06:00", "samples": [] },
  "expected": { "hits_at": "2026-10-03T06:00:00-06:00", "before_reset": false, "basis": "window" }
}
```

- [ ] **Paso 4: `04-sesion-llena.json`**

```json
{
  "description": "Sesion ya en 100%",
  "input": { "kind": "session", "now": "2026-10-02T12:00:00-06:00", "percent": 100,
             "resets_at": "2026-10-02T15:00:00-06:00", "samples": [] },
  "expected": { "hits_at": "2026-10-02T12:00:00-06:00", "before_reset": true, "basis": "window" }
}
```

- [ ] **Paso 5: `05-semana-24h.json`** — ref = muestra de hace exactamente 24 h (20 %);
  ritmo 24/24 = 1 %/h; faltan 56 → 56 h → 10-08 20:00, antes del reinicio del 10-09 18:00.

```json
{
  "description": "Semana: usa el ritmo de las ultimas 24 h",
  "input": { "kind": "weekly", "now": "2026-10-06T12:00:00-06:00", "percent": 44,
             "resets_at": "2026-10-09T18:00:00-06:00",
             "samples": [
               { "t": "2026-10-05T12:00:00-06:00", "percent": 20, "resets_at": "2026-10-09T18:00:00-06:00" },
               { "t": "2026-10-06T00:00:00-06:00", "percent": 30, "resets_at": "2026-10-09T18:00:00-06:00" },
               { "t": "2026-10-06T12:00:00-06:00", "percent": 44, "resets_at": "2026-10-09T18:00:00-06:00" }
             ] },
  "expected": { "hits_at": "2026-10-08T20:00:00-06:00", "before_reset": true, "basis": "24h" }
}
```

- [ ] **Paso 6: `06-semana-respaldo.json`** — sin historia suficiente: ventana desde 10-02 18:00,
  90 h para 45 % → 0.5 %/h; faltan 55 → 110 h → 10-11 02:00.

```json
{
  "description": "Semana sin 1 h de historia: cae al promedio de la ventana de 7 dias",
  "input": { "kind": "weekly", "now": "2026-10-06T12:00:00-06:00", "percent": 45,
             "resets_at": "2026-10-09T18:00:00-06:00",
             "samples": [
               { "t": "2026-10-06T12:00:00-06:00", "percent": 45, "resets_at": "2026-10-09T18:00:00-06:00" }
             ] },
  "expected": { "hits_at": "2026-10-11T02:00:00-06:00", "before_reset": false, "basis": "window" }
}
```

- [ ] **Paso 7: `07-semana-sin-consumo.json`**

```json
{
  "description": "Semana: sin consumo en 24 h, no se proyecta",
  "input": { "kind": "weekly", "now": "2026-10-06T12:00:00-06:00", "percent": 44,
             "resets_at": "2026-10-09T18:00:00-06:00",
             "samples": [
               { "t": "2026-10-05T12:00:00-06:00", "percent": 44, "resets_at": "2026-10-09T18:00:00-06:00" },
               { "t": "2026-10-06T12:00:00-06:00", "percent": 44, "resets_at": "2026-10-09T18:00:00-06:00" }
             ] },
  "expected": { "hits_at": null, "before_reset": null, "basis": "24h" }
}
```

### Tarea 2.6: Validador del contrato

**Archivos:** Crear `spec/validate.py`, `spec/requirements.txt`, `.github/workflows/contrato.yml`

- [ ] **Paso 1: `spec/requirements.txt`**
```
jsonschema==4.23.0
```

- [ ] **Paso 2: `spec/validate.py`** (ASCII, sin emojis)

```python
"""Valida la estructura del contrato: esquema y forma de cada fixture.

No calcula nada: los valores esperados se calcularon a mano y los verifican las
implementaciones (Rust y Java). Esto solo evita fixtures mal formados.
Uso: python spec/validate.py   (codigo de salida != 0 si hay errores)
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).parent
errors = []


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError as e:
        errors.append(f"{path}: JSON invalido: {e}")
        return None


def need(path, obj, keys):
    for k in keys:
        if k not in obj:
            errors.append(f"{path}: falta la clave '{k}'")


schema = load(ROOT / "usage-model.schema.json")
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)

for p in sorted((ROOT / "fixtures" / "parse").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "source", "input", "expected"])
    exp = fx.get("expected", {})
    if "error" in exp:
        if exp["error"] != "unrecognized_format":
            errors.append(f"{p}: error esperado desconocido: {exp['error']}")
    else:
        for e in validator.iter_errors(exp):
            errors.append(f"{p}: expected no cumple el esquema: {e.message}")

for p in sorted((ROOT / "fixtures" / "history").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "input", "expected"])
    need(p, fx.get("input", {}), ["tz", "now", "weekly", "samples"])
    need(p, fx.get("expected", {}), ["per_day", "today_used", "quota_today", "partial"])

for p in sorted((ROOT / "fixtures" / "projection").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "input", "expected"])
    inp = fx.get("input", {})
    need(p, inp, ["kind", "now", "percent", "resets_at", "samples"])
    if inp.get("kind") not in ("session", "weekly"):
        errors.append(f"{p}: kind debe ser session o weekly")
    need(p, fx.get("expected", {}), ["hits_at", "before_reset", "basis"])

count = sum(1 for _ in (ROOT / "fixtures").rglob("*.json"))
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: {count} fixtures validos")
```

- [ ] **Paso 3: Correr local y verificar**

Run: `uv run --with jsonschema==4.23.0 python spec/validate.py`
Expected: `OK: 14 fixtures validos`

- [ ] **Paso 4: Romper a propósito y verificar que falla** — borrar temporalmente
  `"partial"` de `history/03-parcial.json`, correr, esperar
  `...03-parcial.json: falta la clave 'partial'` y código 1. Restaurar.

- [ ] **Paso 5: `.github/workflows/contrato.yml`**

```yaml
name: contrato
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: pip install -r spec/requirements.txt
      - run: python spec/validate.py
```

- [ ] **Paso 6: Commit**
```bash
git add spec .github/workflows/contrato.yml
git commit -m "feat(spec): contrato de uso, reglas R1-R7 y fixtures calculados a mano"
```

### Tarea 2.7: PR, protección de `main` y cierre de F2

- [ ] **Paso 1:** marcar F1 en el roadmap, commit `docs: roadmap F1 cerrada`, push y `gh pr create --fill`.
- [ ] **Paso 2:** esperar CI verde: `gh pr checks --watch`.
- [ ] **Paso 3:** Revisión Fable (`{FASE}`=W0 F2). Además del checklist, pedirle que **recalcule a
  mano cada fixture de historial y proyección** contra `rules.md` y reporte cualquier diferencia.
- [ ] **Paso 4:** merge squash.
- [ ] **Paso 5: Proteger `main`**

```bash
gh api -X PUT repos/abelardodiaz/claude-usage-widgets/branches/main/protection \
  --input - <<'EOF'
{
  "required_status_checks": { "strict": true, "contexts": ["validate"] },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
```
Verificar: `git push origin main` desde una copia con un commit local debe ser rechazado.

---

## F3 — Spike A1: endpoint de uso en claude.ai (PC, Claude in Chrome) · rama `w0/f3-spike-claude-ai`

Objetivo: saber **qué URL** de claude.ai devuelve el uso, **qué forma** tiene y **qué necesita**
(solo cookie, cabeceras extra, protección anti-bots). Sin esto no se diseña Android.

### Tarea 3.1: Descubrir el endpoint

- [ ] **Paso 1:** cargar la skill `claude-in-chrome`, abrir pestaña nueva en `https://claude.ai/settings/usage`
  (el usuario ya tiene sesión en su Chrome).
- [ ] **Paso 2:** con `read_network_requests` filtrar `/api/` y anotar la(s) petición(es) que traen
  porcentajes de uso (URL, método, cabeceras no estándar como `anthropic-client-*`).
- [ ] **Paso 3:** reproducir desde la página con `javascript_tool`, **sin imprimir cookies**:

```javascript
const orgs = await (await fetch('/api/organizations', {credentials: 'include'})).json();
const org = orgs[0].uuid;
const r = await fetch(`/api/organizations/${org}/usage`, {credentials: 'include'});
window.__usage = {status: r.status, body: await r.json()};
JSON.stringify({status: window.__usage.status, keys: Object.keys(window.__usage.body)});
```
(Si la URL observada en el Paso 2 es otra, usar esa.)

- [ ] **Paso 4:** comparar la forma con la de OAuth (¿mismo `five_hour`/`seven_day`/`limits`?).

### Tarea 3.2: Documentar y guardar fixture

**Archivos:** Crear `docs/spikes/2026-10-w0-a1-claude-ai.md`, `spec/fixtures/parse/04-claude-ai-*.json`

- [ ] **Paso 1:** reporte con: URL exacta, método, cabeceras requeridas, si basta la cookie, forma
  de la respuesta, diferencias con OAuth, y **riesgo anti-bots** (si claude.ai responde con reto de
  Cloudflare a clientes que no son navegador, el plan B es hacer la consulta *dentro* del WebView con
  `evaluateJavascript`, que no es un bridge JS y no contradice SECURITY.md regla 5).
- [ ] **Paso 2:** fixture de parseo con la respuesta real **anonimizada** (sin UUIDs de organización,
  correos ni nombres), `source: "claude_ai"`, y su `expected` normalizado.

### Tarea 3.3: Regla R1b

- [ ] **Paso 1:** agregar a `spec/rules.md` la sección `R1b. Parseo claude.ai` con el mapeo exacto
  observado (mismo formato que R1).
- [ ] **Paso 2:** `uv run --with jsonschema==4.23.0 python spec/validate.py` → OK.
- [ ] **Paso 3:** commit `docs(spike): endpoint de uso de claude.ai y fixture`, PR, Fable, merge.

**Si no existe un endpoint usable:** detener W0 aquí, memo al usuario con opciones (puente desde
la PC, que estaba en la lista original) y actualizar la spec antes de seguir.

---

## F4 — Puesto TEL (PC + usuario)

Los detalles de infraestructura (acceso al teléfono, rutas) viven en las notas privadas del
orquestador, **no** en este repo.

### Tarea 4.1: Clon y credenciales de GitHub en el teléfono

- [ ] **Paso 1:** clonar el repo en la carpeta de proyectos del teléfono.
- [ ] **Paso 2:** credencial de push **mínima**: token *fine-grained* de GitHub limitado a este
  repositorio (permisos: Contents read/write, Pull requests read/write; expira en 90 días). El
  usuario lo crea en GitHub y lo configura en el teléfono (`gh auth login --with-token`), nunca
  pasa por un chat ni por un memo.
- [ ] **Paso 3:** verificar: `gh auth status` y `git push --dry-run` desde una rama de prueba.

### Tarea 4.2: Rol de la sesión TEL

**Archivos (en el teléfono, fuera de git):** Crear `CLAUDE.local.md` en la raíz del clon

- [ ] **Paso 1: contenido**

```markdown
# Rol: sesion TEL (telefono Android)

Eres la sesion de Claude Code que corre EN el telefono. La sesion PC orquesta: escribe los
planes, revisa y mergea los PRs. Tu ejecutas las fases Android que te asigne por memo.

- Al iniciar: revisa docs/incoming/. Cada memo de PC es una asignacion o respuesta.
- Trabajas solo en ramas android/*. Abres PR a main; NO mergeas.
- Compilas, instalas y pruebas en este mismo telefono con el toolchain de Termux
  (javac, d8, aapt2, apksigner, platform-34). No uses Gradle.
- Al terminar o bloquearte: memo a PC (formato de nombre del roadmap) con: que se hizo,
  evidencia (comandos y salida), enlace al PR, dudas.
- Memo incorporado -> moverlo a docs/procesados/.
- Nunca pongas cookies, tokens ni datos de la cuenta en memos, commits, logs ni capturas.
```

- [ ] **Paso 2:** el usuario abre Claude Code en esa carpeta (primera vez: confiar la carpeta) y
  ejecuta `/memory` para confirmar que `CLAUDE.local.md` se cargó. Si no se carga, mover el
  contenido a `.claude/CLAUDE.md` y agregar esa ruta a `.gitignore`.

### Tarea 4.3: Canal de memos en ambos sentidos

- [ ] **Paso 1:** PC → TEL: el orquestador deja un memo de prueba en `docs/incoming/` del teléfono
  y la sesión TEL lo reporta.
- [ ] **Paso 2:** TEL → PC: probar escritura directa hacia la PC por la VPN. **Medir en el entorno
  real**, desde la sesión TEL, no desde la PC.
- [ ] **Paso 3:** si el Paso 2 falla, modo extracción: TEL escribe en su `docs/outbox/` (fuera de
  git) y la PC los recoge al inicio de cada revisión. Anotar el modo elegido en las notas privadas.

---

## F5 — Spike A2 + B (TEL) · rama `android/w0-f5-spikes`

Se asigna con un memo de PC que enlaza esta sección.

### Tarea 5.1: Mini APK de spike

**Archivos:** Crear `android/spikes/w0/` (manifiesto, 2 clases Java, layouts, `build.sh`)

Requisitos (los criterios de salida se reportan uno por uno):
- [ ] **A2.1** Activity con WebView que carga `https://claude.ai/login`; el usuario inicia sesión a mano.
- [ ] **A2.2** Botón "Probar": `CookieManager.getInstance().getCookie("https://claude.ai")` no es nulo
  (mostrar solo **cuántas** cookies y si existe una llamada `sessionKey`; jamás su valor).
- [ ] **A2.3** Consulta nativa (`HttpURLConnection`) al endpoint de F3 con esa cookie y el mismo
  User-Agent del WebView: mostrar código HTTP y las claves de primer nivel de la respuesta.
- [ ] **A2.4** Si A2.3 da 403 o un reto anti-bots: misma consulta con `evaluateJavascript` +
  `fetch(..., {credentials: 'include'})` dentro del WebView; mostrar código y claves.
- [ ] **A2.5** Cerrar la app, reabrir: ¿la sesión sigue viva? ¿cuánto dura (anotar fecha de
  expiración de la cookie si es visible sin exponer su valor)?
- [ ] **B.1** `AppWidgetProvider` "hola" que muestra la hora de la última actualización y se
  actualiza al tocarlo.
- [ ] **B.2** Todo construido con `build.sh` (aapt2 + javac + d8 + apksigner, llave de debug),
  instalado y probado en el teléfono con navegación automatizada, sin intervención salvo el login.

### Tarea 5.2: Reporte

- [ ] **Paso 1:** `docs/spikes/2026-10-w0-a2-b-android.md` con cada criterio: PASA/FALLA + evidencia
  (comandos, códigos HTTP, tamaño del APK, versión de Android). Sin capturas que muestren datos de
  la cuenta.
- [ ] **Paso 2:** PR a `main` y memo a PC "W0 F5 terminada".

---

## F6 — Cierre W0 (PC)

- [ ] **Paso 1:** revisión Fable del PR de F5 (`{FASE}`=W0 F5), con énfasis en SECURITY.md regla 1
  y 5 (ninguna cookie en código, logs, reporte ni historial de git).
- [ ] **Paso 2:** decisión go/no-go de Android:
  - A2.3 o A2.4 pasan y B pasa → GO. Si solo A2.4 pasó, actualizar spec §3.1 y §5: la consulta se
    hace dentro de un WebView oculto.
  - A2 falla → NO-GO de la fuente claude.ai; volver a brainstorming de Android (puente desde la PC).
- [ ] **Paso 3:** marcar W0 en el roadmap y escribir `docs/superpowers/plans/YYYY-MM-DD-w1-windows.md`
  con el mismo nivel de detalle que este plan.
- [ ] **Paso 4:** mover a `docs/procesados/` los memos de W0.
