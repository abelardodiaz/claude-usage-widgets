"""Valida la estructura del contrato: esquema y forma de cada fixture.

No calcula nada: los valores esperados se calcularon a mano y los verifican las
implementaciones (Rust y Java). Esto solo evita fixtures mal formados.
Uso: python spec/validate.py   (codigo de salida != 0 si hay errores)
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).parent
errors = []


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError as e:
        errors.append(f"{path}: JSON invalido: {e}")
        return None


def need(path, obj, keys):
    for k in keys:
        if k not in obj:
            errors.append(f"{path}: falta la clave '{k}'")


schema = load(ROOT / "usage-model.schema.json")
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)

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
    need(p, fx.get("input", {}), ["tz", "now", "weekly", "samples"])
    need(p, fx.get("expected", {}), ["per_day", "today_used", "quota_today", "partial"])

for p in sorted((ROOT / "fixtures" / "projection").glob("*.json")):
    fx = load(p)
    if fx is None:
        continue
    need(p, fx, ["description", "input", "expected"])
    inp = fx.get("input", {})
    need(p, inp, ["kind", "now", "percent", "resets_at", "samples"])
    if inp.get("kind") not in ("session", "weekly"):
        errors.append(f"{p}: kind debe ser session o weekly")
    need(p, fx.get("expected", {}), ["hits_at", "before_reset", "basis"])

count = sum(1 for _ in (ROOT / "fixtures").rglob("*.json"))
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"OK: {count} fixtures validos")
