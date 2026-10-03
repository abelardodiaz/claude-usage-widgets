//! Historial de muestras: misma ventana (R2), reparto del consumo por dia local (R3) y "hoy" (R4).

use std::collections::BTreeMap;

use jiff::tz::TimeZone;
use jiff::{SignedDuration, Timestamp};

use crate::model::{Sample, TodayStats, Window};
use crate::timez::{
    DAY, day_key, epoch_millis, local_date, next_day_start, seconds_between, start_of_day,
};

/// Siete dias fijos (R0).
const WEEK: SignedDuration = SignedDuration::from_secs(7 * 86_400);

/// R2: misma ventana semanal si alguno de los dos es nulo o difieren menos de 1 h, comparando
/// a milisegundos (R0; igual que el nucleo Java).
pub fn same_window(a: Option<Timestamp>, b: Option<Timestamp>) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => (epoch_millis(a) - epoch_millis(b)).abs() < HOUR_MILLIS,
        _ => true,
    }
}

/// Una hora en milisegundos.
const HOUR_MILLIS: i64 = 3_600_000;

/// R3 paso 1: descarta `t > now`, ordena de forma estable por `t` y, si varias comparten `t`,
/// conserva la ultima en orden de entrada.
pub fn prepare_samples(samples: &[Sample], now: Timestamp) -> Vec<Sample> {
    let mut kept: Vec<Sample> = samples.iter().filter(|s| s.t <= now).cloned().collect();
    kept.sort_by_key(|s| s.t);
    let mut out: Vec<Sample> = Vec::with_capacity(kept.len());
    for s in kept {
        if out.last().is_some_and(|last| last.t == s.t) {
            out.pop();
        }
        out.push(s);
    }
    out
}

/// R3: puntos de la cuota semanal consumidos por dia local (solo dias con aporte > 0).
pub fn per_day(samples: &[Sample], now: Timestamp, tz: &TimeZone) -> BTreeMap<String, f64> {
    let prepared = prepare_samples(samples, now);
    let mut days = BTreeMap::new();
    for pair in prepared.windows(2) {
        let (a, b) = (&pair[0], &pair[1]);
        let (delta, start) = if same_window(a.resets_at, b.resets_at) {
            (b.percent - a.percent, a.t)
        } else {
            let window_start = b
                .resets_at
                .and_then(|r| r.checked_sub(WEEK).ok())
                .unwrap_or(a.t);
            (b.percent, a.t.max(window_start))
        };
        if delta <= 0.0 {
            continue;
        }
        spread(&mut days, start.min(b.t), b.t, delta, tz);
    }
    days.retain(|_, v| *v > 0.0);
    days
}

/// Reparte `delta` entre los dias locales del intervalo [start, end], proporcional al tiempo
/// real que cae en cada dia (los dias de 23 o 25 h pesan lo que duran).
fn spread(
    days: &mut BTreeMap<String, f64>,
    start: Timestamp,
    end: Timestamp,
    delta: f64,
    tz: &TimeZone,
) {
    let total = seconds_between(start, end);
    if total <= 0.0 {
        *days.entry(day_key(local_date(end, tz))).or_insert(0.0) += delta;
        return;
    }
    let mut cursor = start;
    while cursor < end {
        let day_end = next_day_start(cursor, tz).min(end);
        if day_end <= cursor {
            // Sin dia siguiente calculable: lo que falta va al dia del cursor.
            *days.entry(day_key(local_date(cursor, tz))).or_insert(0.0) +=
                delta * seconds_between(cursor, end) / total;
            break;
        }
        *days.entry(day_key(local_date(cursor, tz))).or_insert(0.0) +=
            delta * seconds_between(cursor, day_end) / total;
        cursor = day_end;
    }
}

/// R4: consumido hoy, cupo adaptativo de hoy y si el dia esta parcialmente registrado.
/// `quota_today` es `None` sin `resets_at` o con dato rancio (`now >= resets_at`); puede ser
/// negativo si `weekly.percent > 100` (la cuota semanal ya se agoto; R7 lo pinta rojo).
pub fn today_stats(
    now: Timestamp,
    weekly: &Window,
    samples: &[Sample],
    tz: &TimeZone,
) -> TodayStats {
    let days = per_day(samples, now, tz);
    let midnight = start_of_day(now, tz);
    let today_used = days
        .get(&day_key(local_date(now, tz)))
        .copied()
        .unwrap_or(0.0);
    let partial = !prepare_samples(samples, now).iter().any(|s| s.t < midnight);
    let quota_today = weekly
        .resets_at
        .filter(|resets_at| now < *resets_at)
        .map(|resets_at| {
            let base = (weekly.percent - today_used).max(0.0);
            let days_left = seconds_between(midnight, resets_at) / DAY.as_secs_f64();
            (100.0 - base) / days_left.max(1.0)
        });
    TodayStats {
        per_day: days,
        today_used,
        quota_today,
        partial,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    #[test]
    fn misma_ventana() {
        let a = Some(ts("2026-10-09T18:00:00-06:00"));
        let b = Some(ts("2026-10-09T18:59:59-06:00"));
        let c = Some(ts("2026-10-09T19:00:00-06:00"));
        assert!(same_window(a, b));
        assert!(!same_window(a, c));
        assert!(same_window(None, c));
        assert!(same_window(a, None));
    }

    #[test]
    fn misma_ventana_en_milisegundos() {
        // R0: se compara a milisegundos (piso, como `toEpochMilli` de Java). En nanosegundos
        // difieren 3599.9992 s (< 1 h); en milisegundos, 3 600 000 ms exactos (no < 1 h).
        let a = Some(ts("2026-10-09T18:00:00.0009-06:00"));
        let b = Some(ts("2026-10-09T19:00:00.0001-06:00"));
        assert!(!same_window(a, b));
        assert!(!same_window(b, a));
        let c = Some(ts("2026-10-09T18:59:59.9999-06:00"));
        assert!(same_window(Some(ts("2026-10-09T18:00:00-06:00")), c));
    }

    #[test]
    fn prepara_ordena_y_deduplica() {
        let now = ts("2026-10-06T12:00:00-06:00");
        let s = |t: &str, p: f64| Sample {
            t: ts(t),
            percent: p,
            resets_at: None,
        };
        let out = prepare_samples(
            &[
                s("2026-10-06T02:00:00-06:00", 30.0),
                s("2026-10-05T22:00:00-06:00", 25.0),
                s("2026-10-06T18:00:00-06:00", 90.0),
                s("2026-10-05T22:00:00-06:00", 26.0),
            ],
            now,
        );
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].percent, 26.0);
        assert_eq!(out[1].percent, 30.0);
    }

    #[test]
    fn reparto_a_traves_del_cambio_de_horario() {
        // Sao Paulo: la medianoche del 2015-10-18 no existe (salta a la 01:00). De las 12:00
        // del 17 (-03:00) a las 12:00 del 18 (-02:00) pasan 23 h reales: 12 h caen en el 17
        // (hasta la 01:00 -02:00, primer instante del 18) y 11 h en el 18.
        let tz = crate::timez::zone_from_spec("America/Sao_Paulo").unwrap();
        let now = ts("2015-10-18T12:00:00-02:00");
        let r = Some(ts("2015-10-20T00:00:00-02:00"));
        let days = per_day(
            &[
                Sample {
                    t: ts("2015-10-17T12:00:00-03:00"),
                    percent: 10.0,
                    resets_at: r,
                },
                Sample {
                    t: ts("2015-10-18T12:00:00-02:00"),
                    percent: 33.0,
                    resets_at: r,
                },
            ],
            now,
            &tz,
        );
        assert!((days["2015-10-17"] - 12.0).abs() < 1e-9);
        assert!((days["2015-10-18"] - 11.0).abs() < 1e-9);
    }

    #[test]
    fn reinicio_lejano_no_explota() {
        // "9999-12-31T23:59:59Z" no cabe en jiff (el parseo lo deja nulo); se prueba con el
        // instante mas lejano representable, en la ventana semanal y en las muestras.
        let tz = crate::timez::zone_from_spec("America/New_York").unwrap();
        let far = Some(Timestamp::MAX);
        let now = ts("2026-10-06T12:00:00-06:00");
        let weekly = Window {
            percent: 40.0,
            resets_at: far,
        };
        let samples = [
            Sample {
                t: ts("2026-10-05T12:00:00-06:00"),
                percent: 10.0,
                resets_at: far,
            },
            Sample {
                t: ts("2026-10-06T12:00:00-06:00"),
                percent: 40.0,
                resets_at: far,
            },
        ];
        let stats = today_stats(now, &weekly, &samples, &tz);
        let quota = stats.quota_today.unwrap();
        assert!(
            quota.is_finite() && quota > 0.0 && quota < 1e-3,
            "quota {quota}"
        );
        // Otra ventana con un reinicio lejano: inicio = max(a.t, b.resets_at - 7 dias).
        let mixed = [
            Sample {
                t: ts("2026-10-05T12:00:00-06:00"),
                percent: 10.0,
                resets_at: Some(ts("2026-10-06T00:00:00-06:00")),
            },
            Sample {
                t: ts("2026-10-06T12:00:00-06:00"),
                percent: 5.0,
                resets_at: far,
            },
        ];
        let days = per_day(&mixed, now, &tz);
        assert!((days.values().sum::<f64>() - 5.0).abs() < 1e-9);
        // Muestras pegadas al extremo del rango: el dia siguiente no es calculable y lo que
        // falta va al dia del cursor, sin panico.
        let end = Timestamp::MAX;
        let start = end.checked_sub(crate::timez::DAY * 2).unwrap();
        let edge = [
            Sample {
                t: start,
                percent: 1.0,
                resets_at: None,
            },
            Sample {
                t: end,
                percent: 2.0,
                resets_at: None,
            },
        ];
        let days = per_day(&edge, end, &tz);
        assert!((days.values().sum::<f64>() - 1.0).abs() < 1e-6);
    }
}
