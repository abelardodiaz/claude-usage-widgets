//! `UsageView`: lo UNICO que recibe la UI. Modelo normalizado + calculos del contrato +
//! estado de la ultima consulta. Nunca credenciales, nunca cuerpos crudos.

use jiff::tz::TimeZone;
use jiff::{Span, Timestamp};
use serde::Serialize;

use crate::colors::{Color, bar_color, pace_mark, today_color};
use crate::credentials::CredentialError;
use crate::history::{prepare_samples, today_stats};
use crate::model::{Projection, Sample, Usage};
use crate::projection::{project_session, project_weekly};
use crate::source_claude_code::FetchError;
use crate::timez::{day_key, local_date, seconds_between};

/// Codigo de error para la UI (ella lo traduce). Nunca lleva detalle del servidor.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    NoCredentials,
    CredentialExpired,
    CredentialRejected,
    RateLimited,
    ServerError,
    Offline,
    UnrecognizedFormat,
    Unexpected,
}

impl From<&FetchError> for ErrorCode {
    fn from(e: &FetchError) -> Self {
        match e {
            FetchError::Credential(CredentialError::Expired) => ErrorCode::CredentialExpired,
            FetchError::Credential(_) => ErrorCode::NoCredentials,
            FetchError::Rejected(_) => ErrorCode::CredentialRejected,
            FetchError::Transient(429) => ErrorCode::RateLimited,
            FetchError::Transient(_) => ErrorCode::ServerError,
            FetchError::Network => ErrorCode::Offline,
            FetchError::Unexpected(_) => ErrorCode::Unexpected,
            FetchError::UnrecognizedFormat => ErrorCode::UnrecognizedFormat,
        }
    }
}

/// Estado de la ultima consulta. `usage` y `updated_at` sobreviven a los errores: sin red se
/// sigue mostrando el ultimo dato con su antiguedad, nunca un 0 % falso.
#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    pub usage: Option<Usage>,
    pub updated_at: Option<Timestamp>,
    pub error: Option<ErrorCode>,
    pub retry_at: Option<Timestamp>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DayUsed {
    pub date: String,
    pub used: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct TodayView {
    pub used: f64,
    pub quota: Option<f64>,
    pub partial: bool,
    pub tracking_since: Option<Timestamp>,
    pub color: Color,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ProjectionPair {
    pub session: Projection,
    pub weekly: Projection,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct UsageView {
    pub lang: String,
    pub error: Option<ErrorCode>,
    pub retry_at: Option<Timestamp>,
    pub updated_at: Option<Timestamp>,
    pub age_seconds: Option<i64>,
    pub usage: Option<Usage>,
    pub session_color: Option<Color>,
    pub weekly_color: Option<Color>,
    pub scoped_colors: Vec<Color>,
    pub today: Option<TodayView>,
    pub history: Vec<DayUsed>,
    pub projection: Option<ProjectionPair>,
    pub pace_mark: Option<f64>,
}

/// Arma la vista a partir del estado y las muestras guardadas.
pub fn build(
    snapshot: &Snapshot,
    samples: &[Sample],
    now: Timestamp,
    tz: &TimeZone,
    lang: &str,
) -> UsageView {
    let mut view = UsageView {
        lang: lang.to_string(),
        error: snapshot.error,
        retry_at: snapshot.retry_at,
        updated_at: snapshot.updated_at,
        age_seconds: snapshot
            .updated_at
            .map(|u| seconds_between(u, now).round() as i64),
        usage: None,
        session_color: None,
        weekly_color: None,
        scoped_colors: Vec::new(),
        today: None,
        history: Vec::new(),
        projection: None,
        pace_mark: None,
    };
    let Some(usage) = &snapshot.usage else {
        return view;
    };

    let stats = today_stats(now, &usage.weekly, samples, tz);
    let prepared = prepare_samples(samples, now);
    let today = local_date(now, tz);
    view.history = (0..7_i64)
        .rev()
        .filter_map(|back| today.checked_sub(Span::new().days(back)).ok())
        .map(|date| {
            let key = day_key(date);
            let used = stats.per_day.get(&key).copied().unwrap_or(0.0);
            DayUsed { date: key, used }
        })
        .collect();
    view.today = Some(TodayView {
        used: stats.today_used,
        quota: stats.quota_today,
        partial: stats.partial,
        tracking_since: prepared.first().map(|s| s.t),
        color: today_color(stats.today_used, stats.quota_today),
    });
    view.session_color = Some(bar_color(usage.session.percent));
    view.weekly_color = Some(bar_color(usage.weekly.percent));
    view.scoped_colors = usage.scoped.iter().map(|s| bar_color(s.percent)).collect();
    view.projection = Some(ProjectionPair {
        session: project_session(now, &usage.session),
        weekly: project_weekly(now, &usage.weekly, samples),
    });
    view.pace_mark = pace_mark(now, usage.weekly.resets_at);
    view.usage = Some(usage.clone());
    view
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{BreakdownRow, ScopedLimit, Source, Window};
    use crate::timez::zone_from_spec;

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    fn usage() -> Usage {
        Usage {
            source: Source::ClaudeCode,
            session: Window {
                percent: 50.0,
                resets_at: Some(ts("2026-10-06T15:00:00-06:00")),
            },
            weekly: Window {
                percent: 35.0,
                resets_at: Some(ts("2026-10-09T18:00:00-06:00")),
            },
            scoped: vec![ScopedLimit {
                label: "Fable".into(),
                percent: 90.0,
                resets_at: None,
            }],
            breakdown: vec![BreakdownRow {
                key: "chat".into(),
                label: "Chats".into(),
                percent: 100.0,
            }],
        }
    }

    #[test]
    fn solo_error_sin_datos() {
        let tz = zone_from_spec("-06:00").unwrap();
        let snapshot = Snapshot {
            error: Some(ErrorCode::NoCredentials),
            ..Snapshot::default()
        };
        let v = build(&snapshot, &[], ts("2026-10-06T12:00:00-06:00"), &tz, "en");
        assert_eq!(v.lang, "en");
        assert_eq!(v.error, Some(ErrorCode::NoCredentials));
        assert!(v.usage.is_none() && v.today.is_none() && v.projection.is_none());
        assert!(v.history.is_empty());
        assert_eq!(v.age_seconds, None);
    }

    #[test]
    fn con_datos_calcula_todo() {
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-06T12:00:00-06:00");
        let r = Some(ts("2026-10-09T18:00:00-06:00"));
        let samples = vec![
            Sample {
                t: ts("2026-10-05T10:00:00-06:00"),
                percent: 20.0,
                resets_at: r,
            },
            Sample {
                t: ts("2026-10-05T22:00:00-06:00"),
                percent: 26.0,
                resets_at: r,
            },
            Sample {
                t: ts("2026-10-06T02:00:00-06:00"),
                percent: 30.0,
                resets_at: r,
            },
            Sample {
                t: now,
                percent: 35.0,
                resets_at: r,
            },
        ];
        let snapshot = Snapshot {
            usage: Some(usage()),
            updated_at: Some(ts("2026-10-06T11:58:00-06:00")),
            error: None,
            retry_at: None,
        };
        let v = build(&snapshot, &samples, now, &tz, "es");
        assert_eq!(v.age_seconds, Some(120));
        let today = v.today.unwrap();
        assert!((today.used - 7.0).abs() < 1e-9);
        assert!((today.quota.unwrap() - 19.2).abs() < 1e-9);
        assert!(!today.partial);
        assert_eq!(today.tracking_since, Some(ts("2026-10-05T10:00:00-06:00")));
        assert_eq!(v.history.len(), 7);
        assert_eq!(v.history[6].date, "2026-10-06");
        assert!((v.history[6].used - 7.0).abs() < 1e-9);
        assert!((v.history[5].used - 8.0).abs() < 1e-9);
        assert_eq!(v.history[0].date, "2026-09-30");
        assert_eq!(today.color, Color::Green);
        assert_eq!(v.session_color, Some(Color::Green));
        assert_eq!(v.weekly_color, Some(Color::Green));
        assert_eq!(v.scoped_colors, vec![Color::Red]);
        let p = v.projection.unwrap();
        assert_eq!(p.session.hits_at, Some(ts("2026-10-06T14:00:00-06:00")));
        assert_eq!(p.weekly.basis, Some(crate::model::Basis::Last24h));
        assert!(v.pace_mark.is_some());
        assert_eq!(v.usage.unwrap().breakdown[0].label, "Chats");
    }

    #[test]
    fn codigos_de_error() {
        use crate::credentials::CredentialError;
        use crate::source_claude_code::FetchError;
        assert_eq!(
            ErrorCode::from(&FetchError::Credential(CredentialError::Missing)),
            ErrorCode::NoCredentials
        );
        assert_eq!(
            ErrorCode::from(&FetchError::Credential(CredentialError::Expired)),
            ErrorCode::CredentialExpired
        );
        assert_eq!(
            ErrorCode::from(&FetchError::Rejected(401)),
            ErrorCode::CredentialRejected
        );
        assert_eq!(
            ErrorCode::from(&FetchError::Transient(429)),
            ErrorCode::RateLimited
        );
        assert_eq!(
            ErrorCode::from(&FetchError::Transient(502)),
            ErrorCode::ServerError
        );
        assert_eq!(ErrorCode::from(&FetchError::Network), ErrorCode::Offline);
        assert_eq!(
            ErrorCode::from(&FetchError::UnrecognizedFormat),
            ErrorCode::UnrecognizedFormat
        );
        assert_eq!(
            ErrorCode::from(&FetchError::Unexpected(404)),
            ErrorCode::Unexpected
        );
    }

    #[test]
    fn colores_de_hoy_vienen_del_nucleo() {
        // D12: cuota nula o cero -> gris; negativa (semana agotada) -> rojo. La UI no decide.
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-06T12:00:00-06:00");
        let mut u = usage();
        u.weekly = Window {
            percent: 100.0,
            resets_at: Some(ts("2026-10-09T18:00:00-06:00")),
        };
        let snapshot = Snapshot {
            usage: Some(u.clone()),
            ..Snapshot::default()
        };
        let samples = vec![
            Sample {
                t: ts("2026-10-05T10:00:00-06:00"),
                percent: 100.0,
                resets_at: u.weekly.resets_at,
            },
            Sample {
                t: now,
                percent: 100.0,
                resets_at: u.weekly.resets_at,
            },
        ];
        let today = build(&snapshot, &samples, now, &tz, "es").today.unwrap();
        assert_eq!(today.quota, Some(0.0));
        assert_eq!(today.color, Color::Gray);

        u.weekly.percent = 110.0;
        let snapshot = Snapshot {
            usage: Some(u.clone()),
            ..Snapshot::default()
        };
        let today = build(&snapshot, &[], now, &tz, "es").today.unwrap();
        assert!(today.quota.unwrap() < 0.0);
        assert_eq!(today.color, Color::Red);

        u.weekly.resets_at = None;
        let snapshot = Snapshot {
            usage: Some(u.clone()),
            ..Snapshot::default()
        };
        let today = build(&snapshot, &[], now, &tz, "es").today.unwrap();
        assert_eq!(today.quota, None);
        assert_eq!(today.color, Color::Gray);
        assert_eq!(serde_json::to_value(today.color).unwrap(), "gray");
    }

    /// Recorre el JSON y junta todas las claves y todas las cadenas.
    fn collect_strings(value: &serde_json::Value, out: &mut Vec<String>) {
        match value {
            serde_json::Value::String(s) => out.push(s.clone()),
            serde_json::Value::Array(items) => items.iter().for_each(|v| collect_strings(v, out)),
            serde_json::Value::Object(map) => {
                for (k, v) in map {
                    out.push(k.clone());
                    collect_strings(v, out);
                }
            }
            _ => {}
        }
    }

    #[test]
    fn la_vista_no_lleva_secretos() {
        // Con datos y con cada codigo de error: ninguna clave ni valor serializado puede
        // nombrar un token, un Bearer, un prefijo de llave ni la ruta de las credenciales.
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-06T12:00:00-06:00");
        let samples = vec![Sample {
            t: now,
            percent: 35.0,
            resets_at: usage().weekly.resets_at,
        }];
        let errors = [
            None,
            Some(ErrorCode::NoCredentials),
            Some(ErrorCode::CredentialExpired),
            Some(ErrorCode::CredentialRejected),
            Some(ErrorCode::RateLimited),
            Some(ErrorCode::ServerError),
            Some(ErrorCode::Offline),
            Some(ErrorCode::UnrecognizedFormat),
            Some(ErrorCode::Unexpected),
        ];
        let mut forbidden = vec![
            "token".to_string(),
            "bearer".to_string(),
            "sk-ant".to_string(),
            ".credentials".to_string(),
            "credentials.json".to_string(),
        ];
        if let Some(path) = crate::credentials::default_path() {
            forbidden.push(path.to_string_lossy().to_lowercase());
        }
        for error in errors {
            let snapshot = Snapshot {
                usage: Some(usage()),
                updated_at: Some(now),
                error,
                retry_at: Some(now),
            };
            let v = build(&snapshot, &samples, now, &tz, "es");
            let json = serde_json::to_value(&v).unwrap();
            let mut strings = Vec::new();
            collect_strings(&json, &mut strings);
            // El mensaje solo nombra indices: el valor podria ser la ruta real de credenciales
            // y no debe acabar en los registros del CI.
            for (i, s) in strings.iter().enumerate() {
                let lower = s.to_lowercase();
                for (j, f) in forbidden.iter().enumerate() {
                    assert!(
                        !lower.contains(f.as_str()),
                        "la vista expone el patron prohibido #{j} en la cadena #{i} (error {error:?})"
                    );
                }
            }
        }
    }
}
