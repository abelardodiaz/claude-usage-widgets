//! R5 (sesion de 5 h) y R6 (semana: ritmo de las ultimas 24 h o promedio de la ventana).

use jiff::{SignedDuration, Timestamp};

use crate::history::{prepare_samples, same_window};
use crate::model::{Basis, Projection, Sample, Window};
use crate::timez::{DAY, seconds_between};

/// Duracion de la ventana de sesion (5 h).
pub const SESSION_LEN: SignedDuration = SignedDuration::from_secs(5 * 3_600);
/// Duracion de la ventana semanal (7 dias).
pub const WEEK_LEN: SignedDuration = SignedDuration::from_secs(7 * 86_400);

/// Ritmo promedio desde que abrio la ventana (%/s), o `None` si no procede (pasos 3 de R5 y 5 de R6).
/// El dato rancio (`now >= resets_at`) ya lo descarto el paso 1 de cada regla.
fn window_rate(
    now: Timestamp,
    percent: f64,
    resets_at: Timestamp,
    len: SignedDuration,
) -> Option<f64> {
    let start = resets_at.checked_sub(len).ok()?;
    let elapsed = seconds_between(start, now);
    if percent <= 0.0 || elapsed < 60.0 {
        return None;
    }
    Some(percent / elapsed)
}

/// Ya esta en 100 %: llega ahora mismo.
fn full(now: Timestamp, basis: Basis) -> Projection {
    Projection {
        hits_at: Some(now),
        before_reset: Some(true),
        basis: Some(basis),
    }
}

/// `now + secs` materializado a la resolucion del contrato (milisegundos, R0); `None` si no es
/// representable (NaN, infinito o fuera del rango de `Timestamp`). Aritmetica comprobada: nunca
/// entra en panico ni satura a una fecha falsa.
fn materialize(now: Timestamp, secs: f64) -> Option<Timestamp> {
    let millis = (secs * 1_000.0).round();
    // `i64::MAX as f64` redondea hacia arriba a 2^63: con `<` el `as i64` nunca satura.
    if !millis.is_finite() || millis.abs() >= i64::MAX as f64 {
        return None;
    }
    now.checked_add(SignedDuration::from_millis(millis as i64))
        .ok()
}

/// `hits_at = now + (100 - percent) / rate`, `before_reset = hits_at < resets_at` comparado
/// literalmente con el instante ya materializado (R0; `hits_at == resets_at` -> false).
/// Si `hits_at` no es representable como instante: `hits_at`, `before_reset` y `basis` nulos.
fn hits(now: Timestamp, percent: f64, rate: f64, resets_at: Timestamp, basis: Basis) -> Projection {
    match materialize(now, (100.0 - percent) / rate) {
        Some(hits_at) => Projection {
            hits_at: Some(hits_at),
            before_reset: Some(hits_at < resets_at),
            basis: Some(basis),
        },
        None => Projection::NONE,
    }
}

/// R5. Paso 1: sin `resets_at` o dato rancio -> nada; lo rancio gana a `percent >= 100`.
pub fn project_session(now: Timestamp, session: &Window) -> Projection {
    let Some(resets_at) = session.resets_at else {
        return Projection::NONE;
    };
    if now >= resets_at {
        return Projection::NONE;
    }
    if session.percent >= 100.0 {
        return full(now, Basis::Window);
    }
    match window_rate(now, session.percent, resets_at, SESSION_LEN) {
        Some(rate) => hits(now, session.percent, rate, resets_at, Basis::Window),
        None => Projection::NONE,
    }
}

/// R6.
pub fn project_weekly(now: Timestamp, weekly: &Window, samples: &[Sample]) -> Projection {
    let Some(resets_at) = weekly.resets_at else {
        return Projection::NONE;
    };
    if now >= resets_at {
        return Projection::NONE;
    }
    let Ok(lower) = now.checked_sub(DAY) else {
        return Projection::NONE;
    };
    let prepared = prepare_samples(samples, now);
    let reference = prepared
        .iter()
        .filter(|s| same_window(s.resets_at, weekly.resets_at) && s.t >= lower && s.t <= now)
        .min_by_key(|s| s.t);
    let rate_24h = reference.filter(|r| seconds_between(r.t, now) >= 3_600.0);

    if weekly.percent >= 100.0 {
        let basis = if rate_24h.is_some() {
            Basis::Last24h
        } else {
            Basis::Window
        };
        return full(now, basis);
    }
    if let Some(r) = rate_24h {
        let rate = (weekly.percent - r.percent) / seconds_between(r.t, now);
        if rate <= 0.0 {
            return Projection {
                hits_at: None,
                before_reset: None,
                basis: Some(Basis::Last24h),
            };
        }
        return hits(now, weekly.percent, rate, resets_at, Basis::Last24h);
    }
    match window_rate(now, weekly.percent, resets_at, WEEK_LEN) {
        Some(rate) => hits(now, weekly.percent, rate, resets_at, Basis::Window),
        None => Projection::NONE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    fn win(percent: f64, resets_at: Option<Timestamp>) -> Window {
        Window { percent, resets_at }
    }

    #[test]
    fn justo_al_reinicio_no_es_antes() {
        // projection/17: 40 % en 2 h -> el 100 % cae exactamente en resets_at.
        let now = ts("2026-10-02T12:00:00-06:00");
        let resets_at = ts("2026-10-02T15:00:00-06:00");
        let p = project_session(now, &win(40.0, Some(resets_at)));
        assert_eq!(p.hits_at, Some(resets_at));
        assert_eq!(p.before_reset, Some(false));
    }

    #[test]
    fn reinicio_lejano_no_explota() {
        // "9999-12-31T23:59:59Z" no cabe en jiff (el parseo lo deja nulo); se usa el instante
        // mas lejano representable. hits_at queda fuera de rango -> todo nulo.
        let now = ts("2026-10-06T12:00:00-06:00");
        let far = Some(Timestamp::MAX);
        assert_eq!(project_session(now, &win(40.0, far)), Projection::NONE);
        assert_eq!(project_weekly(now, &win(40.0, far), &[]), Projection::NONE);
        // Con ritmo 24h el hits_at si es representable y cae antes del reinicio.
        let samples = [
            Sample {
                t: ts("2026-10-05T12:00:00-06:00"),
                percent: 10.0,
                resets_at: far,
            },
            Sample {
                t: now,
                percent: 40.0,
                resets_at: far,
            },
        ];
        let p = project_weekly(now, &win(40.0, far), &samples);
        assert_eq!(p.hits_at, Some(ts("2026-10-08T12:00:00-06:00")));
        assert_eq!(p.before_reset, Some(true));
        assert_eq!(p.basis, Some(Basis::Last24h));
    }

    #[test]
    fn ritmo_minusculo_da_nulo() {
        let now = ts("2026-10-06T12:00:00-06:00");
        let soon = Some(ts("2026-10-06T15:00:00-06:00"));
        assert_eq!(project_session(now, &win(1e-300, soon)), Projection::NONE);
        let week = Some(ts("2026-10-09T18:00:00-06:00"));
        assert_eq!(
            project_weekly(now, &win(1e-300, week), &[]),
            Projection::NONE
        );
        // Con ritmo 24h minusculo tambien: basis nulo, no "24h".
        let samples = [
            Sample {
                t: ts("2026-10-05T12:00:00-06:00"),
                percent: 0.0,
                resets_at: week,
            },
            Sample {
                t: now,
                percent: 1e-300,
                resets_at: week,
            },
        ];
        assert_eq!(
            project_weekly(now, &win(1e-300, week), &samples),
            Projection::NONE
        );
    }

    #[test]
    fn materializa_en_milisegundos() {
        let now = ts("2026-10-06T12:00:00-06:00");
        assert_eq!(materialize(now, 0.0004), Some(now));
        assert_eq!(
            materialize(now, 1.0006),
            Some(ts("2026-10-06T12:00:01.001-06:00"))
        );
        assert_eq!(materialize(now, f64::NAN), None);
        assert_eq!(materialize(now, f64::INFINITY), None);
        assert_eq!(materialize(now, 1e300), None);
        assert_eq!(materialize(Timestamp::MAX, 1.0), None);
    }
}
