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
