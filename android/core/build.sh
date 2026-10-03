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

echo "== javac =="
find "$HERE/src" "$HERE/test" -name '*.java' | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT/classes" "@$OUT/sources.txt"

echo "== pruebas =="
# El corredor necesita saber donde estan los fixtures del contrato.
java -cp "$OUT/classes" com.claudewidgets.core.TestRunner "$ROOT/spec/fixtures"
