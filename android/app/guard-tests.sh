#!/usr/bin/env bash
# guard-tests.sh - guarda ESTATICA de las pruebas de la cascara (N2).
# Solo ASCII: tambien se lee desde consolas de Windows.
#
# Las pruebas corren en el proceso de la app, con los almacenes REALES del dueno al alcance. La
# guarda de ejecucion (AppTestRunner.withPrefsGuard) compara el antes y el despues de las
# preferencias, pero una escritura que no cambia nada neto (borrar una clave que no existia,
# escribir y restaurar) no la ve. Esta guarda mira el CODIGO: prohibe en las fuentes de prueba
# las vias de produccion que llegan a los almacenes reales.
#
# Uso: android/app/guard-tests.sh [directorio-de-pruebas]   (por omision android/app/test)
# Sale con 0 si no hay aciertos fuera de la lista de excepciones; con 1 y un renglon
# "archivo:linea: por que" por cada acierto prohibido. La llaman build.sh (TEST=1) y,
# en su dia, recorrido.sh (tarea 5.1).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="${1:-$HERE/test}"
[ -d "$DIR" ] || { echo "ERROR: no existe el directorio de pruebas $DIR"; exit 2; }

# patron => por que esta prohibido
RULES=(
  'clearBackoff\((ctx|this)\) => borra la espera (backoff_*) de las preferencias REALES; usar clearBackoff(prefs) con preferencias propias'
  'new SnapshotStore\([A-Za-z]+\) => el SnapshotStore con contexto escribe en cuw (datos del widget y organizaciones); usar el constructor con preferencias propias'
  'new UsageRefresher\(ctx\) => el UsageRefresher de produccion apunta a cuw, a la sesion y al unificador reales'
  'Session\.logout\(ctx\) => cierra la sesion REAL del dueno y borra sus almacenes'
  'getSharedPreferences\(SettingsActivity\.PREFS => abre las preferencias reales (cuw): una prueba no escribe ahi'
  'new SessionStore\([A-Za-z]+\) => el SessionStore con contexto es el de la sesion real; usar el que lleva archivo/llave propios'
  'getSystemService\(JobScheduler => el JobScheduler real: solo la sonda 4299 puede usarlo, nunca el 4201/4202 del dueno'
  '\.cancel\((4201|4202|WidgetUpdateJob\.JOB_ID) => cancela el job REAL del dueno (reinicia su ciclo de 15 min)'
  'WidgetUpdateJob\.(schedule|runSoon|cancel|runNow)\((ctx|this) => programa/cancela/lanza trabajos REALES'
)

# archivo|texto que debe contener la linea|por que es inocua. Cada excepcion es una linea concreta.
ALLOW=(
  'AppTestRunner.java|getSharedPreferences(SettingsActivity.PREFS|es la guarda misma: LEE las preferencias reales (getAll), nunca escribe'
  'UsageRefresherTest.java|UsageRefresher prod = new UsageRefresher(ctx);|solo lee meta()/prefsName() para comprobar que apunta a cuw; no refresca ni escribe'
  'WidgetUpdateJobTest.java|JobScheduler js = ctx.getSystemService(JobScheduler.class);|realSchedule: solo registra y cancela la sonda 4299, nunca 4201/4202'
)

bad=0
for rule in "${RULES[@]}"; do
  pat="${rule%% => *}"; why="${rule#* => }"
  rc=0; hits="$(grep -rnE -e "$pat" "$DIR" --include='*.java')" || rc=$?
  [ "$rc" -le 1 ] || { echo "ERROR: patron invalido: $pat"; exit 2; }
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    file="${hit%%:*}"; rest="${hit#*:}"; line="${rest%%:*}"; text="${rest#*:}"
    base="$(basename "$file")"
    ok=0
    for al in "${ALLOW[@]}"; do
      afile="${al%%|*}"; r2="${al#*|}"; atext="${r2%%|*}"
      if [ "$base" = "$afile" ] && [[ "$text" == *"$atext"* ]]; then ok=1; break; fi
    done
    if [ "$ok" = 0 ]; then
      echo "$file:$line: PROHIBIDO ($why)"
      bad=1
    fi
  done <<< "$hits"
done

if [ "$bad" != 0 ]; then
  echo "guard-tests: las pruebas usan vias de produccion (N2). Corregir, o justificar la excepcion en ALLOW."
  exit 1
fi
echo "guard-tests: OK (sin vias de produccion en $DIR)"
