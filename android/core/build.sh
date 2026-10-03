#!/usr/bin/env bash
# build.sh - compila el nucleo Java y corre sus pruebas. Sin Gradle y sin dependencias.
# Solo ASCII: tambien se lee desde consolas de Windows.
#
# Uso:  bash android/core/build.sh
# Sale con codigo != 0 si algo no compila o alguna prueba falla.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/build"

command -v javac >/dev/null 2>&1 || { echo "ERROR: falta javac"; exit 1; }
command -v java  >/dev/null 2>&1 || { echo "ERROR: falta java"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes"

# El codigo de produccion se compila con --release 8 a proposito: Android trae java.time
# desde API 26 con la superficie de Java 8, y el CI usa un JDK 17 que si no dejaria colar
# APIs de Java 9+ que revientan en un telefono real. Las pruebas no corren en Android, asi
# que se compilan aparte y pueden usar lo que quieran.
echo "== javac (produccion, --release 8) =="
find "$HERE/src" -name '*.java' | sort > "$OUT/src.txt"
javac -encoding UTF-8 --release 8 -Xlint:all,-options -Werror -d "$OUT/classes" "@$OUT/src.txt"

echo "== javac (pruebas) =="
find "$HERE/test" -name '*.java' | sort > "$OUT/test.txt"
javac -encoding UTF-8 -Xlint:all -Werror -cp "$OUT/classes" -d "$OUT/classes" "@$OUT/test.txt"

echo "== pruebas =="
# El corredor necesita saber donde estan los fixtures del contrato.
java -cp "$OUT/classes" com.claudewidgets.core.TestRunner "$ROOT/spec/fixtures"
