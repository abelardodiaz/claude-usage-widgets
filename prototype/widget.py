# /// script
# requires-python = ">=3.11"
# dependencies = ["pywebview>=5"]
# ///
"""Widget de escritorio con el uso de Claude (sesion 5 h, semana, hoy, desglose, proyeccion).

Fuente: GET https://api.anthropic.com/api/oauth/usage con el token OAuth que Claude Code
guarda en ~/.claude/.credentials.json. Endpoint NO documentado: se lee a la defensiva.

Solo LEE el token. No lo refresca: refrescar rota el refresh token y dejaria a Claude Code
con uno invalido. Si el token vence, el widget lo avisa y espera a que Claude Code lo renueve.

Correr:  uv run widget.py      (sin consola: uvw run widget.py)
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

import webview

API_URL = "https://api.anthropic.com/api/oauth/usage"
CRED_FILE = Path.home() / ".claude" / ".credentials.json"
DATA_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "widget-uso-claude"
SNAP_FILE = DATA_DIR / "snapshots.jsonl"
STATE_FILE = DATA_DIR / "estado.json"
MIN_FETCH_GAP = 60  # segundos; el JS pide cada 120 s, esto evita martillar el endpoint
WIDTH = 380

DATA_DIR.mkdir(parents=True, exist_ok=True)


def _parse(ts: str | None) -> datetime | None:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts).astimezone()
    except ValueError:
        return None


def _read_token() -> tuple[str | None, str | None]:
    """Devuelve (token, error)."""
    try:
        oauth = json.loads(CRED_FILE.read_text(encoding="utf-8"))["claudeAiOauth"]
    except (OSError, KeyError, ValueError):
        return None, f"No pude leer {CRED_FILE}"
    if oauth.get("expiresAt", 0) / 1000 < time.time():
        return None, "Token vencido: abre Claude Code para renovarlo"
    return oauth.get("accessToken"), None


def _load_snapshots() -> list[dict]:
    if not SNAP_FILE.exists():
        return []
    out = []
    for line in SNAP_FILE.read_text(encoding="utf-8").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def _append_snapshot(raw: dict) -> None:
    week = raw.get("seven_day") or {}
    if week.get("utilization") is None:
        return
    snap = {
        "ts": datetime.now().astimezone().isoformat(timespec="seconds"),
        "week": week["utilization"],
        "week_reset": week.get("resets_at"),
        "session": (raw.get("five_hour") or {}).get("utilization"),
    }
    with SNAP_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps(snap) + "\n")
    # Recorta a ~15 dias para que el archivo no crezca sin fin
    snaps = _load_snapshots()
    cutoff = datetime.now().astimezone() - timedelta(days=15)
    keep = [s for s in snaps if (_parse(s["ts"]) or cutoff) >= cutoff]
    if len(keep) < len(snaps):
        SNAP_FILE.write_text("".join(json.dumps(s) + "\n" for s in keep), encoding="utf-8")


def _same_window(a: dict, b: dict) -> bool:
    ra, rb = _parse(a.get("week_reset")), _parse(b.get("week_reset"))
    if not ra or not rb:
        return True
    return abs((ra - rb).total_seconds()) < 3600  # el endpoint mueve microsegundos


def _daily_usage(snaps: list[dict]) -> dict[str, float]:
    """Puntos de % semanal consumidos por dia local. Si hubo reinicio entre dos
    muestras, la segunda cuenta desde 0. Un hueco largo (widget cerrado) se le
    atribuye al dia de la muestra posterior."""
    per_day: dict[str, float] = {}
    for a, b in zip(snaps, snaps[1:]):
        delta = b["week"] - a["week"] if _same_window(a, b) else b["week"]
        if delta > 0:
            day = _parse(b["ts"]).date().isoformat()
            per_day[day] = per_day.get(day, 0) + delta
    return per_day


def _projection(util: float, resets: datetime | None, length: timedelta, now: datetime) -> dict:
    """Ritmo promedio desde que abrio la ventana -> hora estimada de llegar a 100%."""
    if resets is None:
        return {"hits": None}
    start = resets - length
    elapsed = (now - start).total_seconds()
    if util <= 0 or elapsed <= 60:
        return {"hits": None, "before_reset": False}
    if util >= 100:
        return {"hits": now.isoformat(), "before_reset": True}
    rate = util / elapsed  # % por segundo
    hits = now + timedelta(seconds=(100 - util) / rate)
    return {"hits": hits.isoformat(), "before_reset": hits < resets}


class Api:
    def __init__(self) -> None:
        self._last_fetch = 0.0
        self._last_raw: dict | None = None
        self._last_ok: str | None = None
        self._lock = threading.Lock()
        self._window: webview.Window | None = None

    # --- llamadas desde JS ---------------------------------------------------
    def get_data(self, force: bool = False) -> dict:
        with self._lock:
            error = None
            if force or time.time() - self._last_fetch >= MIN_FETCH_GAP:
                error = self._fetch()
            return self._build(error)

    def resize(self, height: int) -> None:
        if self._window:
            self._window.resize(WIDTH, int(height))

    def close(self) -> None:
        if self._window:
            self._save_position()
            self._window.destroy()

    # --- internos -------------------------------------------------------------
    def _fetch(self) -> str | None:
        self._last_fetch = time.time()
        token, err = _read_token()
        if err:
            return err
        req = urllib.request.Request(API_URL, headers={
            "Authorization": f"Bearer {token}",
            "anthropic-beta": "oauth-2025-04-20",
            "User-Agent": "widget-uso-claude/1.0",
        })
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = json.load(r)
        except urllib.error.HTTPError as e:
            return f"HTTP {e.code} del endpoint de uso"
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            return f"Sin conexion: {e}"
        self._last_raw = raw
        self._last_ok = datetime.now().astimezone().isoformat(timespec="seconds")
        _append_snapshot(raw)
        return None

    def _build(self, error: str | None) -> dict:
        raw = self._last_raw
        if raw is None:
            return {"error": error or "Cargando..."}
        now = datetime.now().astimezone()
        five = raw.get("five_hour") or {}
        week = raw.get("seven_day") or {}
        s_util, w_util = five.get("utilization") or 0, week.get("utilization") or 0
        s_reset, w_reset = _parse(five.get("resets_at")), _parse(week.get("resets_at"))

        # Hoy: consumido vs cupo adaptativo = lo que quedaba al iniciar el dia / dias restantes
        snaps = _load_snapshots()
        per_day = _daily_usage(snaps)
        today = now.date().isoformat()
        used_today = per_day.get(today, 0.0)
        midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
        start_val = w_util - used_today
        days_left = max(((w_reset - midnight).total_seconds() / 86400) if w_reset else 7, 1)
        quota_today = max(100 - start_val, 0) / days_left

        history = []
        for i in range(6, -1, -1):
            d = (now - timedelta(days=i)).date()
            history.append({"date": d.isoformat(), "used": round(per_day.get(d.isoformat(), 0), 1)})
        first_snap = _parse(snaps[0]["ts"]) if snaps else None

        # Limites extra (por modelo/superficie) que no son la sesion ni la semana general
        extra = []
        for lim in raw.get("limits") or []:
            if lim.get("kind") in ("session", "weekly_all"):
                continue
            scope = lim.get("scope") or {}
            name = ((scope.get("model") or {}).get("display_name")
                    or (scope.get("surface") or {}).get("display_name")
                    or lim.get("kind", "limite"))
            extra.append({"name": name, "percent": lim.get("percent", 0),
                          "resets_at": lim.get("resets_at")})

        bd = raw.get("seven_day_breakdown") or {}
        return {
            "error": error,
            "updated": self._last_ok,
            "session": {"util": s_util, "resets_at": five.get("resets_at"),
                        **_projection(s_util, s_reset, timedelta(hours=5), now)},
            "week": {"util": w_util, "resets_at": week.get("resets_at"),
                     **_projection(w_util, w_reset, timedelta(days=7), now)},
            "today": {"used": round(used_today, 1), "quota": round(quota_today, 1),
                      "tracking_since": first_snap.isoformat() if first_snap else None},
            "history": history,
            "extra": extra,
            "breakdown": bd.get("rows") or [],
        }

    def _save_position(self) -> None:
        try:
            STATE_FILE.write_text(json.dumps({"x": self._window.x, "y": self._window.y}), encoding="utf-8")
        except Exception:
            pass


def _initial_position() -> tuple[int | None, int | None]:
    try:
        st = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        return int(st["x"]), int(st["y"])
    except Exception:
        pass
    try:
        scr = webview.screens[0]
        return scr.width - WIDTH - 24, 40
    except Exception:
        return None, None


def main() -> None:
    api = Api()
    x, y = _initial_position()
    win = webview.create_window(
        "Uso de Claude",
        html=(Path(__file__).with_name("widget.html")).read_text(encoding="utf-8"),
        js_api=api, width=WIDTH, height=170, x=x, y=y,
        frameless=True, easy_drag=True, on_top=True, resizable=False,
        background_color="#16181d",
    )
    api._window = win
    win.events.closing += api._save_position
    webview.start()


if __name__ == "__main__":
    main()
