#!/usr/bin/env bash
# mutantes.sh - rompe la implementacion a proposito y comprueba que el corredor lo caza.
#
# Un corredor que nunca falla no prueba nada. Esto lo somete: aplica una mutacion, compila,
# comprueba que las pruebas fallan, y restaura. Es una herramienta de verificacion puntual,
# no parte del build: `build.sh` no la llama.
#
# Solo ASCII. Uso: bash android/core/mutantes.sh
set -uo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"
SRC=src/com/claudewidgets/core

probar() {
  local nombre="$1" archivo="$2" de="$3" a="$4"
  cp "$archivo" "$archivo.orig"
  python3 - "$archivo" "$de" "$a" <<'PY'
import sys
p,de,a = sys.argv[1], sys.argv[2], sys.argv[3]
s=open(p,encoding="utf-8").read()
assert de in s, "patron no encontrado: "+de
open(p,"w",encoding="utf-8").write(s.replace(de,a,1))
PY
  local salida; salida=$(bash build.sh 2>&1)
  local code=$?
  mv "$archivo.orig" "$archivo"
  local fallos; fallos=$(echo "$salida" | grep -c '^  - ' || true)
  if [ $code -ne 0 ]; then
    echo "  CAZADO    $nombre  ($fallos comprobaciones fallan)"
    echo "$salida" | grep '^  - ' | head -2 | sed 's/^/              /'
  else
    echo "  NO CAZADO $nombre   <-- el corredor no lo detecta"
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
            return new Bar(0, null);
        }'
echo "== fin; comprobando que el arbol quedo intacto =="
bash build.sh 2>&1 | tail -2
