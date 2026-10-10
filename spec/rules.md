# Reglas de cálculo (contrato)

Toda implementación (Rust en desktop/, Java en android/core/) debe pasar los fixtures de
`spec/fixtures/`. Si este documento y un fixture discrepan, gana el fixture y se corrige el doc.
Las reglas con pasos numerados se evalúan **en ese orden**: la primera que aplica decide.

## R0. Convenciones

- Instantes: RFC 3339 con desplazamiento. Se comparan como instantes (misma hora UTC = iguales),
  no como texto, **a resolución de milisegundos**, y de una forma concreta porque cualquier otra
  hace divergir a las implementaciones:
  - Los dos operandos se llevan a **milisegundos desde la época con piso** (`toEpochMilli` en
    Java, `div_euclid` en Rust) antes de comparar. Vale para `same_window` (R2), para
    `before_reset` (R5, R6) y para las comparaciones de R4 con `day_start`:
    `medianoche < inicio_ventana ≤ now`, `t < day_start` (`partial`) y `b.t ≥ day_start`
    (intervalo de duración 0). Lo fija `history/31`.
  - `hits_at` se materializa como **piso en ms de `now`** más el **redondeo al milisegundo más
    cercano** de la duración en segundos.
  - `before_reset` compara esos dos enteros de milisegundos.

  No es un detalle: con `resets_at = ...T14:46:40.0005Z` y `hits_at = ...T14:46:40.000Z`, comparar
  en nanosegundos da `true` y en milisegundos con piso da `false`. Lo fija
  `projection/23-before-reset-al-milisegundo`. Tolerancia en fixtures: 1 s.
- **Forma aceptada de un instante leído de la respuesta** (R1): exactamente

  ```
  YYYY-MM-DDTHH:MM:SS(.fracción)?(Z|+HH:MM|-HH:MM)
  ```

  Año de **exactamente cuatro dígitos sin signo**, `T` como separador **en mayúscula**, segundos
  **obligatorios**,
  fracción de **1 a 9 dígitos** si lleva punto (nanosegundos, lo máximo que representan Java y
  Rust; con más dígitos las bibliotecas difieren en qué hacen), desplazamiento con minutos y
  **sin segundos**. Todo lo
  demás → `null`. Se rechazan, entre otras: `+002026-10-02T12:00:00Z` (año con signo),
  `2026-10-02T12:00Z` (sin segundos), `+00` y `+00:00:30` (desplazamiento mal formado),
  `2026-10-02T12:00:00.Z` (fracción vacía),
  `2026-10-02T12:00:00.1234567890Z` (diez dígitos), `2026-10-02 12:00:00Z` (espacio en vez de `T`),
  `2026-10-02T12:00:00Z[UTC]` (anotación de zona) y `2026-06-30T23:59:60Z` (segundo 60).

  No es quisquillosidad: las bibliotecas de fecha difieren justo en estos casos, y una que acepte
  `+00:00:30` desplaza el instante treinta segundos respecto de otra que lo rechace. Esta regla
  vale **solo** para instantes leídos de la respuesta; `t` y `now` de los fixtures no pasan por
  ella.

  `T` y `Z` van **en mayúscula**. RFC 3339 permite minúsculas pero no las exige, y las bibliotecas
  difieren: `2026-10-02t12:00:00z` lo acepta el parseo por omisión de Java y lo rechaza el de Rust.
  Se fija la mayúscula para que no haya duda.

  El **desplazamiento está acotado a ±18:00**: ninguna zona real pasa de +14:00. `+18:00` es
  válido, `+19:00` no.

  Además, la cadena debe ser una **fecha de calendario válida**: `2026-13-01`, `2026-02-30`,
  `2026-10-02T24:00:00Z` y `12:60:00` → `null`, aunque encajen en la forma.
- Números: sin redondeo interno; los fixtures muestran hasta 6 decimales. Tolerancia: 0.001.
- "h" y "días" en restas de instantes (5 h, 7 días, 24 h, 1 h) son duraciones fijas de
  3600 s y 86 400 s. `days_left` = segundos / 86 400.
- "Día local" y "medianoche local" se calculan en la zona del dispositivo (IANA, con horario de
  verano). En un fixture, en la zona `tz` de la entrada. Un día con cambio de horario dura 23 o
  25 h y el reparto de R3 sigue siendo proporcional al tiempo real. Si la medianoche no existe en
  esa zona (la zona adelanta 23:00 → 00:00), se usa el primer instante del día. Si ocurre
  **dos veces** (la zona atrasa 01:00 → 00:00, como `America/Havana`), se usa la **primera**
  ocurrencia: el instante más temprano cuya fecha local es `d`. Las claves de `per_day` son `YYYY-MM-DD` locales.
- "Hoy" = fecha local de `now`.
- Las comparaciones `<`, `≤`, `≥` son literales: `t ≥ now − 24 h` incluye la muestra tomada
  exactamente 24 h antes (lo fija `projection/05`).
- `before_reset` se evalúa materializando `hits_at` como instante a la resolución de la
  implementación (milisegundos) y comparándolo literalmente con `resets_at`; la tolerancia de
  1 s de los fixtures aplica a `hits_at`, no al booleano (lo fijan `projection/17` y `20`).
- Muestras con `t > now` se ignoran. `samples` ya incluye la muestra actual; no se agrega
  `(now, weekly.percent)` implícitamente.
- `percent` se conserva tal cual viene (puede ser > 100 o < 0; no es error). R3 usa el valor
  crudo; R5 y R6 cubren `≥ 100` y `≤ 0`; R7 y las barras acotan a [0, 100] solo para dibujar.

## R1. Parseo de la respuesta OAuth (`source = claude_code`)

Entrada: JSON de `GET https://api.anthropic.com/api/oauth/usage`.

- `session` ← `five_hour`: `percent = utilization`, `resets_at = resets_at`.
- `weekly` ← `seven_day`: igual.
- Si `five_hour` o `seven_day` falta, no es objeto, o su `utilization` no es número →
  error `unrecognized_format` (nunca se inventa un 0 %).
- `resets_at` ausente, nulo, no cadena, **no conforme a la forma de R0** o no válida como fecha de calendario, o cuyo **año en la propia
  cadena** (los cuatro dígitos tal como vienen, con su desplazamiento; **no** el año del instante
  en UTC) esté fuera de 0000-9998 → `null`. Así `9998-12-31T23:00:00-05:00` es válido (aunque en
  UTC cae en 9999) y `9999-01-01T00:00:00+14:00` es nulo (aunque en UTC cae en 9998). El año 9999
  es el centinela habitual de "sin límite" (`9999-12-31T23:59:59Z`) y no todas las bibliotecas lo
  representan completo (jiff termina en `9999-12-30T22:00:00Z`); con el año del texto acotado a
  9998, el instante resultante nunca pasa de `9999-01-01T23:59:59Z`. Lo fija `parse/07`.
  La acotación aplica a todo instante leído de la respuesta (`five_hour`, `seven_day`,
  `limits[].resets_at`); `t` y `now` no se leen de la API.
- `scoped` ← cada elemento de `limits[]` cuyo `kind` sea cadena no vacía distinta de `session` y `weekly_all` y cuyo
  `percent` sea número. `label` = `scope.model.display_name`, si no `scope.surface.display_name`,
  si no `kind`; una cadena vacía cuenta como ausente. Si `limits` falta o no es arreglo → `[]`.
- `breakdown` ← `seven_day_breakdown.rows[]`: `key`, `label = display_name` (si no, `key`),
  `percent`. Se omite la fila cuyo `key` no sea cadena o cuyo `percent` no sea número.
  Si `rows` falta o no es arreglo → `[]`.
- Claves desconocidas se ignoran.

## R1b. Parseo de claude.ai (`source = claude_ai`)

Entrada: JSON de `GET https://claude.ai/api/organizations/{org_uuid}/usage`, autenticado solo con
la cookie de sesión de claude.ai (ver `docs/spikes/2026-10-w0-a1-claude-ai.md`).

La respuesta tiene **la misma forma** que la de OAuth: se aplica R1 completa, con
`source = "claude_ai"`. Diferencias observadas que R0 y R1 ya cubren:
- Los instantes vienen en UTC (`+00:00`); se comparan como instantes, nunca como texto.
- `display_name` del desglose viene en el idioma de la cuenta (p. ej. `Otros`). La UI usa `key`
  para colores e íconos y `label` solo para mostrar.

## R2. Misma ventana

Dos muestras `a` y `b` están en la misma ventana semanal si alguno de sus `resets_at` es nulo
o si `|a.resets_at − b.resets_at| < 3600 s`.

## R3. Consumo entre muestras consecutivas

1. Se descartan las muestras con `t > now`. Las restantes se ordenan por `t` ascendente (orden
   estable). Si varias comparten el mismo `t`, se conserva solo la última en el orden de entrada.
2. Para cada par consecutivo `a`, `b`:
   - misma ventana (R2): `delta = b.percent − a.percent`, `inicio = a.t`.
   - otra ventana (hubo reinicio): `delta = b.percent`, `inicio = max(a.t, b.resets_at − 7 días)`.
     Si `inicio > b.t`, se toma `inicio = b.t`.
   - `delta ≤ 0` → el par no aporta nada.
3. El `delta` se reparte entre los días locales **proporcional al tiempo** del intervalo
   `[inicio, b.t]` que cae en cada día. Si el intervalo dura 0, todo va al día de `b.t`.

## R4. Hoy

- `per_day[d]` = suma de lo repartido al día `d` por R3. Solo contiene días con aporte > 0; al
  comparar, una entrada con 0 equivale a ausente. **`per_day` no mira ventanas**: el día de un
  reinicio suma todo su consumo, el de la ventana anterior (la mañana) y el de la nueva. Es el
  historial de 7 días; "hoy" se calcula aparte y puede ser menor que `per_day[hoy]`
  (lo fija `history/22`).
- **`day_start`**, el instante en que empieza "hoy" para la cuota. Sea `medianoche` la medianoche
  local de hoy (R0). En este orden:
  1. `weekly.resets_at` nulo, o `now ≥ weekly.resets_at` (dato rancio) → `day_start = medianoche`.
  2. Sea `inicio_ventana = weekly.resets_at − 7 días`. Si `medianoche < inicio_ventana ≤ now`
     (la ventana semanal actual empezó **hoy**, después de las 00:00; `inicio_ventana = now`
     cuenta, `history/30`) →
     `day_start = inicio_ventana`. Lo de antes del reinicio es de la semana anterior y descuenta
     de un cupo que ya no existe.
  3. Si no → `day_start = medianoche`. Cubre todos los demás días (`inicio_ventana ≤ medianoche`,
     incluido el reinicio exacto a las 00:00: la desigualdad es estricta y lo fija `history/29`
     con una muestra incoherente de la ventana vieja posterior a medianoche; con datos
     coherentes las dos ramas dan lo mismo, `history/20`) y el dato incoherente
     `inicio_ventana > now` (`resets_at` a más de 7 días), que no se usa para recortar
     (`history/24`).

  Equivale a `max(medianoche, inicio_ventana)` siempre que `inicio_ventana ≤ now`.
- `today_used`:
  - Si `day_start = medianoche` (pasos 1 y 3): `per_day[hoy]` (0 si no hay), como siempre.
  - Si `day_start = inicio_ventana` (paso 2): consumo de la **ventana actual** desde
    `day_start`. Se suman los pares `(a, b)` de R3 con `delta > 0` **cuyo `b` está en la misma
    ventana que `weekly`** (R2: `|b.resets_at − weekly.resets_at| < 3600 s`, o
    `b.resets_at` nulo), y de cada uno solo la parte de su intervalo `[inicio, b.t]` de R3 (en
    un par cruzado, ya recortado a `max(a.t, b.resets_at − 7 días)`) que cae en
    `[day_start, now]`:

    ```
    aporte = delta × (b.t − max(inicio, day_start)) / (b.t − inicio)   acotado a ≥ 0
    ```

    Si el intervalo dura 0 (`inicio = b.t`), el par aporta `delta` entero si `b.t ≥ day_start`
    y nada si no. Un par cuyo `b` es de otra ventana no aporta aunque caiga después de
    `day_start` (`history/23`, `32`); los `resets_at` que oscilan menos de 3600 s cuentan como
    la misma ventana (`history/23`, `27`). Un par que empieza antes de `day_start` aporta solo su
    parte posterior (`history/27`, `33`).
- `partial` = no existe ninguna muestra (depurada por R3 paso 1) con `t < day_start` (estricto:
  una muestra exactamente en `day_start` no cuenta como anterior; `history/07` y `21`). El día
  del reinicio basta una muestra de la mañana para que no sea parcial (`history/22`).
- Si `weekly.resets_at` es nulo, o `now ≥ weekly.resets_at` (dato rancio) → `quota_today = null`.
  Lo segundo iguala a R5 y R6: un dato vencido no sostiene ninguna afirmación. Sin esta condición,
  `days_left` saldría negativo y el `max(days_left, 1)` de abajo fingiría que queda justo un día.
- Si no: `base = max(weekly.percent − today_used, 0)`,
  `days_left = (weekly.resets_at − day_start)` en días (fraccionario),
  `quota_today = (100 − base) / max(days_left, 1)`.
  El día del reinicio `days_left = 7` exactos, aunque el día tenga 23 o 25 h (`history/25`):
  `quota_today = (100 − base) / 7`.
  Excluido el caso rancio, `days_left` es siempre > 0 (`day_start ≤ now < resets_at`),
  así que `max(days_left, 1)` solo redondea hacia arriba el último día de la ventana, nunca tapa
  un negativo.
- `quota_today` puede salir **negativo** si `weekly.percent > 100` (R0 lo permite) y
  `today_used < weekly.percent − 100`: significa que la cuota semanal ya se agotó. R7 lo pinta rojo.

## R5. Proyección de la sesión (ventana de 5 h)

1. `resets_at` nulo, o `now ≥ resets_at` (dato rancio) → `hits_at = null`, `before_reset = null`,
   `basis = null`. El orden importa y es el mismo que en R6: lo rancio se comprueba **antes** que
   `percent ≥ 100`. Si no, con la barra llena y el dato vencido el paso 2 afirmaría
   `before_reset = true` siendo `hits_at` (= `now`) **posterior** a `resets_at`.
2. `percent ≥ 100` → `hits_at = now`, `before_reset = true`, `basis = "window"`.
   Aquí `before_reset = true` es siempre cierto, porque el paso 1 ya descartó `now ≥ resets_at`.
3. `start = resets_at − 5 h`, `elapsed = now − start`. Si `percent ≤ 0` o `elapsed < 60 s`
   → `hits_at = null`, `before_reset = null`, `basis = null`.
4. Si no: `rate = percent / elapsed`, `hits_at = now + (100 − percent) / rate`,
   `before_reset = hits_at < resets_at`, `basis = "window"`.
   Si `hits_at` no es representable como instante, `hits_at`, `before_reset` y `basis` son nulos.

## R6. Proyección de la semana

1. `weekly.resets_at` nulo, o `now ≥ resets_at` (dato rancio) → `hits_at = null`,
   `before_reset = null`, `basis = null`.
2. Se parte de las muestras ya depuradas por el paso 1 de R3 (sin `t > now`, sin `t` duplicado).
   Candidatas: muestras de la misma ventana que `weekly` (R2) con `now − 24 h ≤ t ≤ now`.
   `ref` = la de menor `t`. Hay *ritmo 24h* si existe `ref` y `now − ref.t ≥ 1 h`.
3. `percent ≥ 100` → `hits_at = now`, `before_reset = true`, `basis = "24h"` si hay ritmo 24h,
   si no `"window"`.
4. Con ritmo 24h: `rate = (percent − ref.percent) / (now − ref.t)`, `basis = "24h"`.
   Si `rate ≤ 0` → `hits_at = null`, `before_reset = null` (`basis` sigue siendo `"24h"`).
5. Sin ritmo 24h: `start = resets_at − 7 días`, `elapsed = now − start`. Si `percent ≤ 0` o
   `elapsed < 60 s` → `hits_at = null`, `before_reset = null`, `basis = null`.
   Si no: `rate = percent / elapsed`, `basis = "window"`.
6. `hits_at = now + (100 − percent) / rate`, `before_reset = hits_at < resets_at`.
   Si `hits_at` no es representable como instante, `hits_at`, `before_reset` y `basis` son nulos.

## R7. Colores

Los fixtures de `spec/fixtures/colors/` fijan esta regla. Los colores viajan como
`"green"`, `"amber"`, `"red"` y `"gray"`; el nombre que se muestre al usuario es cosa de la UI.

Cada fixture trae `input.bar`, que dice qué se está pintando y qué más lleva la entrada:

| `bar` | resto de `input` | `expected` |
|---|---|---|
| `session`, `weekly`, `scoped` | `percent` | `color` |
| `today` | `today_used`, `quota_today` (puede ser nulo) | `color` |
| `pace_mark` | `now`, `resets_at` (puede ser nulo) | `mark`: número en [0, 1] o nulo |

- Barras de sesión, semana y limitados, por `percent`: verde `< 60`, ámbar `< 85`, rojo `≥ 85`.
  `percent` se usa crudo (R0): uno negativo cae en verde y uno mayor que 100 en rojo; ninguno
  de los dos es error.
- Barra de hoy, **en este orden** (el nulo va primero a propósito: en Java `quota_today` es un
  `Double` y compararlo antes de descartar el nulo lanzaría `NullPointerException` al desenvolver):
  1. `quota_today` nulo → gris.
  2. `quota_today < 0` → **rojo**. La cuota semanal ya se agotó, así que hoy no queda nada.
     Sin este paso el cociente saldría negativo y caería en "verde", diciendo que todo va bien
     justo cuando no es así.
  3. `quota_today` igual a 0 → gris.
  4. Si no, por `today_used / quota_today`: verde `< 0.7`, ámbar `< 1`, rojo `≥ 1`.
     Los umbrales se comparan **sobre el cociente en doble precisión**, no con multiplicación
     cruzada: `today_used / quota_today < 0.7`, nunca `today_used < 0.7 * quota_today`. Con
     `quota_today` negativo las dos formas difieren, y el paso 2 ya cubre ese caso.
- Marca de ritmo parejo en la barra semanal: `1 − (resets_at − now) / 7 días`, acotada a [0, 1];
  sin marca (nula) solo si `resets_at` es nulo. **Con el reinicio ya pasado la marca es 1, no
  nula**: aquí el dato rancio no se descarta como en R4, R5 y R6, porque la marca solo dice cuánto
  de la ventana transcurrió y una ventana vencida transcurrió entera. Lo fija `colors/16`.
