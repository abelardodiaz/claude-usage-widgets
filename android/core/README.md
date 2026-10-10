# `android/core` — núcleo del contrato en Java

Java puro, **sin una sola API de Android y sin dependencias**: los mismos `.class` corren en una
JVM y bajo ART. Implementa R0–R7 de `spec/rules.md` y se contrasta contra las cuatro familias de
`spec/fixtures/`.

## Correr las pruebas

```bash
bash android/core/build.sh
```

Correr en **Linux, WSL o Termux**. Bajo Git Bash de Windows no funciona por cómo resuelve las
rutas; no es un entorno objetivo.

`src/` se compila con `--release 8` a propósito: Android trae `java.time` desde API 26 con la
superficie de Java 8, y el CI usa un JDK 17 que si no dejaría colar APIs de Java 9+ que revientan
en un teléfono real. `test/` se compila aparte, sin esa restricción, porque no corre en Android.

Todo con `-Werror`. Sale con código distinto de 0 si algo falla. No necesita Gradle, JUnit ni red.

## Qué hay

| Archivo | Regla |
|---|---|
| `Json` | lector JSON mínimo; límites de profundidad y tamaño, escapes completos, rechaza NaN e infinitos |
| `Parser` | R1 y R1b: respuesta → `UsageModel`, defensivo salvo en lo esencial |
| `History` | R2, R3 y R4: reparto por día local y cuota de hoy |
| `Projection` | R5 y R6: cuándo se llega al 100 % |
| `Colors` | R7 |

`UsageModel`, `Bar`, `ScopedLimit`, `BreakdownRow`, `Sample`, `DayUsage`, `Forecast`, `Color`,
`TodayState`, `TodayFill` y `Source` son el modelo; no llevan lógica.

## Por qué el corredor es propio

JUnit es una dependencia y el núcleo no tiene ninguna, para que compilar en Termux y en el CI sea
lo mismo. `test/` trae un `Assert` de treinta líneas, las pruebas del lector JSON y el corredor de
fixtures con las tolerancias de R0 (1 s en instantes, 0.001 en números).

## Comprobar que las pruebas tienen dientes

```bash
bash android/core/mutantes.sh
```

Rompe la implementación a propósito —veintinueve mutaciones, una por llamada a `probar`:
quince sobre el "hoy" de R4 (tres de ellas, las de resolución en milisegundos de R0), ocho sobre
los colores y el relleno de hoy de R7, y seis sobre el resto (dos de R0, una del reparto por día de
R3, y una de R1, R5 y R6)— y comprueba que el corredor las caza. Un corredor que nunca falla no prueba nada. No forma parte del build.

## Notas de portabilidad

- `instant.atZone(tz).toLocalDate()` y no `LocalDate.ofInstant(...)`, que es de Java 9: Android
  trae `java.time` desde API 26 con la superficie de Java 8, y el `minSdk` es 31.
- Zonas IANA de verdad (`ZoneId`), nunca desplazamiento fijo. El `tz` de los fixtures es entrada;
  en producción sale de `ZoneId.systemDefault()`.
- `test/AndroidSmoke` existe para correr el núcleo bajo ART y comprobar justo eso.
