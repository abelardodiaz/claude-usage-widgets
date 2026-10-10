//! Textos de la version lite: etiquetas, reinicio corto, estado del pie. Funciones puras (sin
//! Win32) para poder probarlas. Los mensajes de error son los mismos de `desktop/ui/i18n.js`.
//! Fuente en ASCII: los caracteres no ASCII van escapados (\u{..}).

use jiff::Timestamp;
use jiff::tz::TimeZone;

use crate::colors::Color;
use crate::view::{ErrorCode, UsageView};

/// Flecha de reinicio (U+21BB) seguida de un espacio.
pub const RESET_PREFIX: &str = "\u{21bb} ";

pub struct Texts {
    pub session: &'static str,
    pub week: &'static str,
    pub updated: &'static str,
    pub loading: &'static str,
    pub ago: &'static str,
    pub minutes: &'static str,
    pub hours: &'static str,
    pub days: [&'static str; 7],
    pub months: [&'static str; 12],
    pub no_credentials: &'static str,
    pub credential_expired: &'static str,
    pub credential_rejected: &'static str,
    pub rate_limited: &'static str,
    pub server_error: &'static str,
    pub offline: &'static str,
    pub unrecognized_format: &'static str,
    pub unexpected: &'static str,
    pub menu_interval: &'static str,
    pub menu_every: &'static str,
    pub menu_topmost: &'static str,
    pub notice_week: &'static str,
    pub notice_resets: &'static str,
}

pub static ES: Texts = Texts {
    session: "SESI\u{d3}N 5 H",
    week: "SEMANA",
    updated: "act. ",
    loading: "Cargando...",
    ago: "; dato de hace ",
    minutes: " min",
    hours: " h",
    days: ["dom", "lun", "mar", "mi\u{e9}", "jue", "vie", "s\u{e1}b"],
    months: [
        "ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic",
    ],
    no_credentials: "No encuentro las credenciales de Claude Code",
    credential_expired: "Token vencido: abre Claude Code para renovarlo",
    credential_rejected: "Credencial vencida: abre Claude Code para renovarla",
    rate_limited: "L\u{ed}mite de peticiones; reintento ",
    server_error: "Servicio no disponible; reintento ",
    offline: "Sin conexi\u{f3}n",
    unrecognized_format: "Formato de respuesta no reconocido",
    unexpected: "Respuesta inesperada del servicio",
    menu_interval: "Intervalo",
    menu_every: "Cada {} min",
    menu_topmost: "Siempre encima",
    notice_week: "Semana al {}%",
    notice_resets: "Reinicia ",
};

pub static EN: Texts = Texts {
    session: "5 H SESSION",
    week: "WEEK",
    updated: "upd. ",
    loading: "Loading...",
    ago: "; data from ",
    minutes: " min ago",
    hours: " h ago",
    days: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
    months: [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ],
    no_credentials: "Claude Code credentials not found",
    credential_expired: "Token expired: open Claude Code to renew it",
    credential_rejected: "Credential expired: open Claude Code to renew it",
    rate_limited: "Rate limited; retry ",
    server_error: "Service unavailable; retry ",
    offline: "No connection",
    unrecognized_format: "Unrecognized response format",
    unexpected: "Unexpected response from the service",
    menu_interval: "Interval",
    menu_every: "Every {} min",
    menu_topmost: "Always on top",
    notice_week: "Week at {}%",
    notice_resets: "Resets ",
};

/// "es" -> espanol; cualquier otro -> ingles (igual que la app normal).
pub fn texts(lang: &str) -> &'static Texts {
    if lang == "es" { &ES } else { &EN }
}

/// "es" si la etiqueta BCP 47 del sistema empieza por "es"; "en" en cualquier otro caso.
/// Copia de `language_from_locale` de src-tauri/src/lib.rs (ese modulo depende de tauri).
pub fn language_from_locale(locale: &str) -> &'static str {
    if locale.to_ascii_lowercase().starts_with("es") {
        "es"
    } else {
        "en"
    }
}

/// Instante en forma corta: "04:50" hoy, "vie 18:00" dentro de la semana, "3 nov 18:00" si no.
pub fn when_short(t: Timestamp, now: Timestamp, tz: &TimeZone, x: &Texts) -> String {
    let zt = t.to_zoned(tz.clone());
    let today = now.to_zoned(tz.clone()).date();
    let hm = format!("{:02}:{:02}", zt.hour(), zt.minute());
    let days = today.until(zt.date()).map(|s| s.get_days()).unwrap_or(0);
    match days {
        0 => hm,
        1..=6 => {
            let wd = zt.weekday().to_sunday_zero_offset() as usize;
            format!("{} {hm}", x.days[wd])
        }
        _ => {
            let month = x.months[(zt.month() as usize).saturating_sub(1) % 12];
            format!("{} {month} {hm}", zt.day())
        }
    }
}

/// Texto de reinicio de una barra: "\u{21bb} 04:50"; vacio sin `resets_at`.
pub fn reset_text(
    resets_at: Option<Timestamp>,
    now: Timestamp,
    tz: &TimeZone,
    x: &Texts,
) -> String {
    resets_at
        .map(|t| format!("{RESET_PREFIX}{}", when_short(t, now, tz, x)))
        .unwrap_or_default()
}

/// Porcentaje redondeado, como `Math.round` de la UI normal.
pub fn percent_text(percent: f64) -> String {
    format!("{}%", percent.round() as i64)
}

/// Fraccion de la barra acotada a [0, 1].
pub fn bar_fraction(percent: f64) -> f64 {
    if percent.is_nan() {
        return 0.0;
    }
    (percent / 100.0).clamp(0.0, 1.0)
}

fn ago(seconds: i64, x: &Texts) -> String {
    if seconds < 3600 {
        let min = ((seconds as f64) / 60.0).round().max(1.0) as i64;
        format!("{min}{}", x.minutes)
    } else {
        let h = ((seconds as f64) / 3600.0).round() as i64;
        format!("{h}{}", x.hours)
    }
}

fn error_text(code: ErrorCode, x: &Texts) -> &'static str {
    match code {
        ErrorCode::NoCredentials => x.no_credentials,
        ErrorCode::CredentialExpired => x.credential_expired,
        ErrorCode::CredentialRejected => x.credential_rejected,
        ErrorCode::RateLimited => x.rate_limited,
        ErrorCode::ServerError => x.server_error,
        ErrorCode::Offline => x.offline,
        ErrorCode::UnrecognizedFormat => x.unrecognized_format,
        ErrorCode::Unexpected => x.unexpected,
    }
}

/// Pie: `(texto, es_error)`. Sin vista aun: "Cargando...". Con error: el mensaje de la UI normal
/// (+ hora de reintento si aplica, + antiguedad del ultimo dato). Sin error: "act. 14:05".
pub fn footer(
    view: Option<&UsageView>,
    now: Timestamp,
    tz: &TimeZone,
    x: &Texts,
) -> (String, bool) {
    let Some(v) = view else {
        return (x.loading.to_string(), false);
    };
    match v.error {
        Some(code) => {
            let mut text = error_text(code, x).to_string();
            if matches!(code, ErrorCode::RateLimited | ErrorCode::ServerError)
                && let Some(retry) = v.retry_at
            {
                text.push_str(&when_short(retry, now, tz, x));
            }
            if let Some(age) = v.age_seconds {
                text.push_str(x.ago);
                text.push_str(&ago(age, x));
            }
            (text, true)
        }
        None => match v.updated_at {
            Some(u) => (format!("{}{}", x.updated, when_short(u, now, tz, x)), false),
            None => (String::new(), false),
        },
    }
}

/// "Cada 3 min" / "Every 3 min".
pub fn every_text(minutes: u32, x: &Texts) -> String {
    x.menu_every.replace("{}", &minutes.to_string())
}

/// Aviso semanal: ("Semana al 80%", "Reinicia vie 18:00"); sin `resets_at`, cuerpo vacio.
pub fn week_notice(
    threshold: u8,
    resets_at: Option<Timestamp>,
    now: Timestamp,
    tz: &TimeZone,
    x: &Texts,
) -> (String, String) {
    let title = x.notice_week.replace("{}", &threshold.to_string());
    let body = resets_at
        .map(|t| format!("{}{}", x.notice_resets, when_short(t, now, tz, x)))
        .unwrap_or_default();
    (title, body)
}

/// Paleta de `desktop/ui/style.css` en 0xRRGGBB (gris = `--dim`).
pub fn rgb(color: Color) -> u32 {
    match color {
        Color::Green => 0x3fbf6f,
        Color::Amber => 0xe8b03f,
        Color::Red => 0xe5534b,
        Color::Gray => 0x5f6570,
    }
}

#[cfg(test)]
mod lite_tests {
    use super::*;
    use crate::timez::zone_from_spec;
    use crate::view::{Snapshot, build};

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    #[test]
    fn idioma_por_locale() {
        assert_eq!(language_from_locale("es-MX"), "es");
        assert_eq!(language_from_locale("ES"), "es");
        assert_eq!(language_from_locale("en-US"), "en");
        assert_eq!(language_from_locale(""), "en");
    }

    #[test]
    fn reinicio_corto_hoy_semana_y_lejos() {
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-07T10:00:00-06:00"); // miercoles
        assert_eq!(
            reset_text(Some(ts("2026-10-07T16:50:00-06:00")), now, &tz, &ES),
            "\u{21bb} 16:50"
        );
        assert_eq!(
            reset_text(Some(ts("2026-10-09T18:00:00-06:00")), now, &tz, &ES),
            "\u{21bb} vie 18:00"
        );
        assert_eq!(
            reset_text(Some(ts("2026-10-09T18:00:00-06:00")), now, &tz, &EN),
            "\u{21bb} Fri 18:00"
        );
        assert_eq!(
            reset_text(Some(ts("2026-11-03T08:05:00-06:00")), now, &tz, &ES),
            "\u{21bb} 3 nov 08:05"
        );
        assert_eq!(reset_text(None, now, &tz, &ES), "");
    }

    #[test]
    fn porcentaje_y_barra() {
        assert_eq!(percent_text(41.5), "42%");
        assert_eq!(percent_text(0.0), "0%");
        assert_eq!(bar_fraction(130.0), 1.0);
        assert_eq!(bar_fraction(-5.0), 0.0);
        assert_eq!(bar_fraction(f64::NAN), 0.0);
        assert!((bar_fraction(25.0) - 0.25).abs() < 1e-12);
    }

    #[test]
    fn pie_cargando_actualizado_y_errores() {
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-07T10:00:00-06:00");
        assert_eq!(
            footer(None, now, &tz, &ES),
            ("Cargando...".to_string(), false)
        );

        let mut snap = Snapshot {
            updated_at: Some(ts("2026-10-07T09:55:00-06:00")),
            ..Snapshot::default()
        };
        let v = build(&snap, &[], now, &tz, "es");
        assert_eq!(
            footer(Some(&v), now, &tz, &ES),
            ("act. 09:55".to_string(), false)
        );

        snap.error = Some(ErrorCode::Offline);
        let v = build(&snap, &[], now, &tz, "es");
        assert_eq!(
            footer(Some(&v), now, &tz, &ES),
            ("Sin conexi\u{f3}n; dato de hace 5 min".to_string(), true)
        );

        snap.error = Some(ErrorCode::RateLimited);
        snap.retry_at = Some(ts("2026-10-07T10:04:00-06:00"));
        snap.updated_at = None;
        let v = build(&snap, &[], now, &tz, "en");
        assert_eq!(
            footer(Some(&v), now, &tz, &EN),
            ("Rate limited; retry 10:04".to_string(), true)
        );
    }

    #[test]
    fn textos_de_menu_y_aviso() {
        let tz = zone_from_spec("-06:00").unwrap();
        let now = ts("2026-10-07T10:00:00-06:00");
        assert_eq!(every_text(3, &ES), "Cada 3 min");
        assert_eq!(every_text(5, &EN), "Every 5 min");
        assert_eq!(
            week_notice(80, Some(ts("2026-10-09T18:00:00-06:00")), now, &tz, &ES),
            (
                "Semana al 80%".to_string(),
                "Reinicia vie 18:00".to_string()
            )
        );
        assert_eq!(
            week_notice(95, None, now, &tz, &EN),
            ("Week at 95%".to_string(), String::new())
        );
    }

    #[test]
    fn colores_de_la_paleta() {
        assert_eq!(rgb(Color::Green), 0x3fbf6f);
        assert_eq!(rgb(Color::Amber), 0xe8b03f);
        assert_eq!(rgb(Color::Red), 0xe5534b);
    }
}
