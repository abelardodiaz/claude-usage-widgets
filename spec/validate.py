"""Valida la estructura del contrato: esquema y forma de cada fixture.

No calcula nada: los valores esperados se calcularon a mano y los verifican las
implementaciones (Rust y Java). Esto solo evita fixtures mal formados.
Uso: python spec/validate.py   (codigo de salida != 0 si hay errores)
"""
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).parent
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# R0 exige zona IANA con horario de verano para "dia local". Se admite tambien el
# desplazamiento fijo que usan los fixtures sin cambio de horario.
TZ_OFFSET = re.compile(r"^[+-]\d{2}:\d{2}$")
TZ_IANA = re.compile(r"^[A-Za-z][A-Za-z0-9_+-]*(?:/[A-Za-z0-9_+-]+)*$")
errors = []


def is_tz(s):
    """Desplazamiento fijo, o zona IANA que exista de verdad.

    Resolver la zona caza erratas tipo 'America/Nueva_York', que si no pasarian
    silenciosas y dejarian la fixture con valores sin sentido.
    """
    if not isinstance(s, str):
        return False
    if TZ_OFFSET.match(s):
        return True
    if not TZ_IANA.match(s):
        return False
    try:
        ZoneInfo(s)
    except Exception:
        return False
    return True


def is_instant(s):
    """RFC 3339 con desplazamiento obligatorio."""
    if not isinstance(s, str):
        return False
    try:
        return datetime.fromisoformat(s).tzinfo is not None
    except ValueError:
        return False


def is_num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError as e:
        errors.append(f"{path}: JSON invalido: {e}")
        return None


def need(path, obj, keys):
    if not isinstance(obj, dict):
        errors.append(f"{path}: se esperaba un objeto")
        return
    for k in keys:
        if k not in obj:
            errors.append(f"{path}: falta la clave '{k}'")


def check_instant(path, name, value, nullable=False):
    if value is None and nullable:
        return
    if not is_instant(value):
        errors.append(f"{path}: '{name}' no es un instante RFC 3339 con zona: {value!r}")
    elif not 0 <= datetime.fromisoformat(value).year <= 9998:
        # R1: fuera de 0000-9998 el instante seria nulo; un fixture no debe depender de eso
        errors.append(f"{path}: '{name}' tiene anio fuera de 0000-9998: {value!r}")


def check_num(path, name, value, nullable=False):
    if value is None and nullable:
        return
    if not is_num(value):
        errors.append(f"{path}: '{name}' no es numero: {value!r}")


def check_samples(path, samples):
    if not isinstance(samples, list):
        errors.append(f"{path}: 'samples' no es arreglo")
        return
    for i, s in enumerate(samples):
        need(path, s, ["t", "percent", "resets_at"])
        if isinstance(s, dict):
            check_instant(path, f"samples[{i}].t", s.get("t"))
            check_num(path, f"samples[{i}].percent", s.get("percent"))
            check_instant(path, f"samples[{i}].resets_at", s.get("resets_at"), nullable=True)


formats = FormatChecker(formats=())
# En JSON Schema, format solo restringe cadenas: null (permitido por type) pasa.
formats.checks("date-time")(lambda s: not isinstance(s, str) or is_instant(s))

schema = load(ROOT / "usage-model.schema.json")
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=formats)

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
    inp, exp = fx.get("input", {}), fx.get("expected", {})
    need(p, inp, ["tz", "now", "weekly", "samples"])
    need(p, exp, ["per_day", "today_used", "quota_today", "partial"])
    if not is_tz(inp.get("tz")):
        errors.append(
            f"{p}: 'tz' debe ser un desplazamiento fijo (-06:00) "
            "o una zona IANA existente (America/New_York)"
        )
    check_instant(p, "now", inp.get("now"))
    need(p, inp.get("weekly", {}), ["percent", "resets_at"])
    check_num(p, "weekly.percent", inp.get("weekly", {}).get("percent"))
    check_instant(p, "weekly.resets_at", inp.get("weekly", {}).get("resets_at"), nullable=True)
    check_samples(p, inp.get("samples"))
    per_day = exp.get("per_day")
    if not isinstance(per_day, dict):
        errors.append(f"{p}: 'per_day' no es objeto")
    else:
        for k, v in per_day.items():
            if not DAY.match(k):
                errors.append(f"{p}: clave de per_day no es YYYY-MM-DD: {k!r}")
            check_num(p, f"per_day[{k}]", v)
    check_num(p, "today_used", exp.get("today_used"))
    check_num(p, "quota_today", exp.get("quota_today"), nullable=True)
    if not isinstance(exp.get("partial"), bool):
        errors.append(f"{p}: 'partial' no es booleano")

for p in sorted((ROOT / "fixtures" / "projection").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "input", "expected"])
    inp, exp = fx.get("input", {}), fx.get("expected", {})
    need(p, inp, ["kind", "now", "percent", "resets_at", "samples"])
    if inp.get("kind") not in ("session", "weekly"):
        errors.append(f"{p}: kind debe ser session o weekly")
    check_instant(p, "now", inp.get("now"))
    check_num(p, "percent", inp.get("percent"))
    check_instant(p, "resets_at", inp.get("resets_at"), nullable=True)
    check_samples(p, inp.get("samples"))
    need(p, exp, ["hits_at", "before_reset", "basis"])
    check_instant(p, "hits_at", exp.get("hits_at"), nullable=True)
    if exp.get("before_reset") not in (True, False, None):
        errors.append(f"{p}: 'before_reset' debe ser booleano o null")
    if exp.get("basis") not in ("window", "24h", None):
        errors.append(f"{p}: 'basis' debe ser window, 24h o null")

for p in sorted((ROOT / "fixtures" / "colors").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "input", "expected"])
    inp, exp = fx.get("input", {}), fx.get("expected", {})
    bar = inp.get("bar")
    if bar not in ("session", "weekly", "scoped", "today", "today_fill", "pace_mark"):
        errors.append(
            f"{p}: 'bar' debe ser session, weekly, scoped, today, today_fill o pace_mark"
        )
    elif bar == "today_fill":
        need(p, inp, ["bar", "today_used", "quota_today"])
        check_num(p, "today_used", inp.get("today_used"))
        check_num(p, "quota_today", inp.get("quota_today"), nullable=True)
        need(p, exp, ["state", "fraction"])
        state, fraction = exp.get("state"), exp.get("fraction")
        if state not in ("unknown", "exhausted", "ok"):
            errors.append(f"{p}: 'state' debe ser unknown, exhausted u ok")
        check_num(p, "fraction", fraction, nullable=True)
        # R7: fraccion nula si y solo si el estado es unknown; exhausted siempre llena (1).
        if (state == "unknown") != (fraction is None):
            errors.append(f"{p}: 'fraction' es null si y solo si 'state' es unknown")
        if state == "exhausted" and fraction != 1:
            errors.append(f"{p}: con 'state' exhausted la fraccion es 1")
    elif bar == "pace_mark":
        need(p, inp, ["bar", "now", "resets_at"])
        check_instant(p, "now", inp.get("now"))
        check_instant(p, "resets_at", inp.get("resets_at"), nullable=True)
        need(p, exp, ["mark"])
        mark = exp.get("mark")
        if mark is not None and (not isinstance(mark, (int, float)) or isinstance(mark, bool)):
            errors.append(f"{p}: 'mark' debe ser numero o null")
        elif isinstance(mark, (int, float)) and not 0 <= mark <= 1:
            errors.append(f"{p}: 'mark' debe estar en [0, 1]")
    else:
        if bar == "today":
            need(p, inp, ["bar", "today_used", "quota_today"])
            check_num(p, "today_used", inp.get("today_used"))
            check_num(p, "quota_today", inp.get("quota_today"), nullable=True)
        else:
            need(p, inp, ["bar", "percent"])
            check_num(p, "percent", inp.get("percent"))
        need(p, exp, ["color"])
        if exp.get("color") not in ("green", "amber", "red", "gray"):
            errors.append(f"{p}: 'color' debe ser green, amber, red o gray")

count = sum(1 for _ in (ROOT / "fixtures").rglob("*.json"))
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: {count} fixtures validos")
