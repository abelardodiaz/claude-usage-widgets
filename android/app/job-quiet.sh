#!/usr/bin/env bash
# job-quiet.sh - espera a que NO haya un refresco real a punto de ocurrir (N2).
# Solo ASCII: tambien se lee desde consolas de Windows.
#
# Las pruebas corren en el proceso de la app con las preferencias REALES del dueno al alcance, y
# el periodico real (job 4201) escribe en ellas cuando vence. Un reloj fijo tras instalar
# ("sleep 30") deja un fallo intermitente; esto es una CONDICION: antes de `am instrument` mira la
# ventana del 4201 en `dumpsys jobscheduler` ("Run time: earliest=+4m48s...") y no deja arrancar
# si `earliest` esta a menos de MIN segundos (o ya paso: el job puede correr ya). Espera hasta
# MAX segundos a que el periodico corra y su ventana quede lejos.
#
# Uso:    job-quiet.sh [SERIAL] [MIN_SEGUNDOS=120] [MAX_ESPERA_SEGUNDOS=1200]
#         source job-quiet.sh; job_quiet_wait SERIAL [MIN] [MAX]     (desde recorrido.sh, 5.1)
# Sale con 0 cuando es seguro arrancar (o no hay 4201 registrado), 1 si agoto la espera, 2 si
# dumpsys no responde.
# Imprime "job-quiet: ..." con el estado (solo tiempos, nada de datos de la cuenta).

# Segundos hasta el earliest del 4201 (negativo = ya vencido), leidos de stdin (dumpsys).
job_quiet_earliest() {
  python3 -c '
import re, sys
txt = sys.stdin.read()
m = re.search(r"JOB #u\d+a\d+/4201:[\s\S]*?Run time: earliest=([+-])(\S+?),", txt)
if not m:
    sys.exit(3 if "JOB #" in txt else 2)   # 3: dumpsys responde pero no hay 4201
sign, dur = m.group(1), m.group(2)
t = 0.0
for n, u in re.findall(r"(\d+)(d|h|ms|m|s)", dur):
    t += int(n) * {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": 0.001}[u]
print(int(t) if sign == "+" else -int(t))
'
}

job_quiet_wait() {
  local serial="${1:-}" min="${2:-120}" max="${3:-1200}"
  local adb_cmd=(adb); [ -n "$serial" ] && adb_cmd=(adb -s "$serial")
  local waited=0 e rc
  while :; do
    rc=0
    e="$("${adb_cmd[@]}" shell dumpsys jobscheduler | job_quiet_earliest)" || rc=$?
    if [ "$rc" = 3 ]; then
      echo "job-quiet: OK, no hay job 4201 registrado (sin sesion): no hay periodico real que esperar"
      return 0
    fi
    if [ "$rc" != 0 ] || [ -z "$e" ]; then
      echo "job-quiet: no encuentro el job 4201 en dumpsys (sin sesion o sin ADB); no se puede decidir"
      return 2
    fi
    if [ "$e" -gt "$min" ]; then
      echo "job-quiet: OK, earliest del 4201 dentro de ${e}s (minimo ${min}s)"
      return 0
    fi
    if [ "$waited" -ge "$max" ]; then
      echo "job-quiet: agotada la espera (${max}s); earliest del 4201 = ${e}s (minimo ${min}s)"
      return 1
    fi
    echo "job-quiet: earliest del 4201 = ${e}s (<= ${min}s): puede correr un refresco real; espero 15 s"
    sleep 15; waited=$((waited + 15))
  done
}

# Ejecutado (no con `source`): usa los argumentos.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  job_quiet_wait "$@"
fi
