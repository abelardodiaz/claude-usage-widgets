#!/usr/bin/env bash
# mutantes.sh - rompe la implementacion a proposito y comprueba que el corredor lo caza.
#
# Un corredor que nunca falla no prueba nada. Esto lo somete: aplica una mutacion, compila,
# comprueba que las pruebas fallan, y restaura. Es una herramienta de verificacion puntual,
# no parte del build: `build.sh` no la llama.
#
# No basta con que `build.sh` salga != 0: si la mutacion rompe la compilacion, tambien sale != 0
# y no se habria probado nada. Solo cuenta como CAZADO si el corredor reporta fallos.
#
# Solo ASCII. Uso: bash android/core/mutantes.sh   (sale != 0 si algo no fue cazado)
set -uo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"
SRC=src/com/claudewidgets/core

ACTUAL=""
restaurar() { [ -n "$ACTUAL" ] && [ -f "$ACTUAL.orig" ] && mv "$ACTUAL.orig" "$ACTUAL"; ACTUAL=""; }
trap 'echo; echo "interrumpido: restaurando"; restaurar; exit 130' INT TERM

fallidas=0

probar() {
  local nombre="$1" archivo="$2" de="$3" a="$4"
  cp "$archivo" "$archivo.orig"
  ACTUAL="$archivo"
  python3 - "$archivo" "$de" "$a" <<'PY'
import sys
p, de, a = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding="utf-8").read()
assert de in s, "patron no encontrado: " + de
open(p, "w", encoding="utf-8").write(s.replace(de, a, 1))
PY
  local salida code fallos
  salida=$(bash build.sh 2>&1); code=$?
  restaurar
  fallos=$(printf '%s\n' "$salida" | grep -c '^  - ' || true)

  if [ "$fallos" -gt 0 ]; then
    echo "  CAZADO          $nombre  ($fallos comprobaciones fallan)"
    printf '%s\n' "$salida" | grep '^  - ' | head -2 | sed 's/^/                    /'
  elif [ $code -ne 0 ]; then
    # Salio != 0 pero sin fallos del corredor: no compilo, asi que no se probo nada.
    echo "  NO CONCLUYENTE  $nombre  (la mutacion no compila)"
    fallidas=$((fallidas + 1))
  else
    echo "  NO CAZADO       $nombre  <-- el corredor no lo detecta"
    fallidas=$((fallidas + 1))
  fi
}

echo "== mutaciones =="
probar "R7: umbral verde 60 -> 61" $SRC/Colors.java \
  "if (percent < 60) return Color.GREEN;" "if (percent < 61) return Color.GREEN;"
probar "R7: cuota negativa -> gris en vez de rojo" $SRC/Colors.java \
  "if (q < 0) return Color.RED;" "if (q < 0) return Color.GRAY;"
probar "R5: el dato rancio deja de ganar" $SRC/Projection.java \
  "if (resetsAt == null || !now.isBefore(resetsAt)) return Forecast.NONE;
        // 2. Ya esta lleno." "if (resetsAt == null) return Forecast.NONE;
        // 2. Ya esta lleno."
probar "R4: quita la comprobacion de dato rancio" $SRC/History.java \
  "if (weekly.resetsAt != null && now.isBefore(weekly.resetsAt)) {" \
  "if (weekly.resetsAt != null) {"
probar "R3/R4: desplazamiento fijo en vez de zona IANA" $SRC/History.java \
  "Instant now, ZoneId tz) {" \
  "Instant now, ZoneId tzIgnorada) { ZoneId tz = java.time.ZoneOffset.ofHours(-5);"
probar "R6: sin el umbral de 1 h para el ritmo 24h" $SRC/Projection.java \
  "boolean rhythm24h = ref != null && seconds(ref.t, now) >= 3600.0;" \
  "boolean rhythm24h = ref != null;"
probar "R1: inventa 0% cuando falta utilization" $SRC/Parser.java \
  'if (!(util instanceof Double)) {
            throw new UnrecognizedFormatException("'"'"'" + key + ".utilization'"'"' no es un numero");
        }' 'if (!(util instanceof Double)) {
            return new Window(0, null);
        }'

probar "R0: sin la puerta de formato estricto" $SRC/Parser.java \
  "if (!RFC3339.matcher((String) v).matches()) return null;" \
  "if (false) return null;"
probar "R0: before_reset en nanos en vez de ms" $SRC/Projection.java \
  "return new Forecast(hitsAt, hitsAt.toEpochMilli() < resetsAt.toEpochMilli(), basis);" \
  "return new Forecast(hitsAt, hitsAt.isBefore(resetsAt), basis);"

echo "== arbol restaurado =="
bash build.sh 2>&1 | tail -2
if [ "$fallidas" -gt 0 ]; then
  echo
  echo "ERROR: $fallidas mutacion(es) sin cazar o no concluyentes"
  exit 1
fi
