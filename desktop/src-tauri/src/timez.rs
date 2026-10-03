//! Instantes, zonas y dias locales (R0). Las fixtures usan desplazamientos fijos;
//! en produccion se usa la zona IANA del sistema con horario de verano.

use jiff::civil::Date;
use jiff::tz::{Offset, TimeZone};
use jiff::{SignedDuration, Span, Timestamp};

/// Una hora fija (R0).
pub const HOUR: SignedDuration = SignedDuration::from_secs(3_600);
/// Un dia fijo de 86 400 s (R0).
pub const DAY: SignedDuration = SignedDuration::from_secs(86_400);

/// Zona a partir de un desplazamiento fijo tipo "-06:00" o de un nombre IANA.
pub fn zone_from_spec(spec: &str) -> Result<TimeZone, String> {
    if let Some(offset) = parse_fixed_offset(spec) {
        return Ok(TimeZone::fixed(offset));
    }
    TimeZone::get(spec).map_err(|e| format!("zona desconocida {spec}: {e}"))
}

fn parse_fixed_offset(spec: &str) -> Option<Offset> {
    let (sign, rest) = match spec.as_bytes().first()? {
        b'+' => (1, &spec[1..]),
        b'-' => (-1, &spec[1..]),
        _ => return None,
    };
    let (hh, mm) = rest.split_once(':')?;
    if hh.len() != 2 || mm.len() != 2 {
        return None;
    }
    let hours: i32 = hh.parse().ok()?;
    let minutes: i32 = mm.parse().ok()?;
    if hours > 23 || minutes > 59 {
        return None;
    }
    Offset::from_seconds(sign * (hours * 3600 + minutes * 60)).ok()
}

/// Zona local del dispositivo (IANA, con horario de verano). UTC si no se puede determinar.
pub fn system_zone() -> TimeZone {
    TimeZone::try_system().unwrap_or(TimeZone::UTC)
}

/// Fecha local de un instante.
pub fn local_date(t: Timestamp, tz: &TimeZone) -> Date {
    t.to_zoned(tz.clone()).date()
}

/// Clave YYYY-MM-DD de `per_day`.
pub fn day_key(date: Date) -> String {
    date.to_string()
}

/// Primer instante del dia local de `t`: la medianoche, o el primer instante del dia si la
/// medianoche no existe por un cambio de horario.
pub fn start_of_day(t: Timestamp, tz: &TimeZone) -> Timestamp {
    t.to_zoned(tz.clone())
        .start_of_day()
        .map(|z| z.timestamp())
        .unwrap_or(t)
}

/// Primer instante del dia local siguiente al de `t`.
pub fn next_day_start(t: Timestamp, tz: &TimeZone) -> Timestamp {
    t.to_zoned(tz.clone())
        .checked_add(Span::new().days(1))
        .and_then(|z| z.start_of_day())
        .map(|z| z.timestamp())
        .unwrap_or(t)
}

/// Segundos (fraccionarios) de `from` a `to`; negativo si `to` es anterior.
pub fn seconds_between(from: Timestamp, to: Timestamp) -> f64 {
    to.duration_since(from).as_secs_f64()
}

/// `t + secs`; `None` si no es representable (NaN, infinito o fuera de rango).
pub fn add_seconds(t: Timestamp, secs: f64) -> Option<Timestamp> {
    let d = SignedDuration::try_from_secs_f64(secs).ok()?;
    t.checked_add(d).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    #[test]
    fn desplazamiento_fijo() {
        let tz = zone_from_spec("-06:00").unwrap();
        let d = local_date(ts("2026-10-06T05:30:00+00:00"), &tz);
        assert_eq!(day_key(d), "2026-10-05");
        assert_eq!(
            start_of_day(ts("2026-10-06T05:30:00+00:00"), &tz),
            ts("2026-10-05T06:00:00+00:00")
        );
    }

    #[test]
    fn zona_iana() {
        let tz = zone_from_spec("America/Sao_Paulo").unwrap();
        // El 2015-10-18 la medianoche no existio en Sao Paulo: el dia empieza a la 01:00.
        let t = ts("2015-10-18T12:00:00-02:00");
        assert_eq!(start_of_day(t, &tz), ts("2015-10-18T01:00:00-02:00"));
        assert_eq!(day_key(local_date(t, &tz)), "2015-10-18");
        let prev = ts("2015-10-17T12:00:00-03:00");
        assert_eq!(next_day_start(prev, &tz), ts("2015-10-18T01:00:00-02:00"));
        // El dia 18 (de la 01:00 a la medianoche siguiente) dura 23 h.
        assert_eq!(
            seconds_between(start_of_day(t, &tz), next_day_start(t, &tz)),
            23.0 * 3600.0
        );
    }

    #[test]
    fn dia_de_25_horas_en_nueva_york() {
        // 2026-11-01 termina el horario de verano en America/New_York (01:59 EDT -> 01:00 EST).
        let tz = zone_from_spec("America/New_York").unwrap();
        let t = ts("2026-11-01T12:00:00-05:00");
        assert_eq!(start_of_day(t, &tz), ts("2026-11-01T00:00:00-04:00"));
        assert_eq!(next_day_start(t, &tz), ts("2026-11-02T00:00:00-05:00"));
        assert_eq!(
            seconds_between(start_of_day(t, &tz), next_day_start(t, &tz)),
            25.0 * 3600.0
        );
    }

    #[test]
    fn medianoche_repetida_usa_la_primera() {
        // America/Havana atrasa de 01:00 a 00:00 el 2026-11-01: hay dos medianoches.
        // R0: gana la primera ocurrencia (la de -04:00).
        let tz = zone_from_spec("America/Havana").unwrap();
        let t = ts("2026-11-01T12:00:00-05:00");
        assert_eq!(start_of_day(t, &tz), ts("2026-11-01T00:00:00-04:00"));
        assert_eq!(
            day_key(local_date(ts("2026-11-01T00:30:00-05:00"), &tz)),
            "2026-11-01"
        );
    }

    #[test]
    fn zona_invalida() {
        assert!(zone_from_spec("Marte/Olympus").is_err());
        assert!(zone_from_spec("America/Nueva_York").is_err());
        assert!(zone_from_spec("-6").is_err());
    }

    #[test]
    fn aritmetica() {
        let a = ts("2026-10-02T12:00:00-06:00");
        assert_eq!(seconds_between(a, ts("2026-10-02T14:00:00-06:00")), 7200.0);
        assert_eq!(add_seconds(a, 90.0), Some(ts("2026-10-02T12:01:30-06:00")));
        assert_eq!(add_seconds(a, f64::NAN), None);
        // Fuera del rango de Timestamp: None, nunca panico ni fecha saturada.
        assert_eq!(add_seconds(a, 1e300), None);
        assert_eq!(add_seconds(Timestamp::MAX, 1.0), None);
    }

    #[test]
    fn zona_del_sistema_no_explota() {
        let _ = system_zone();
    }
}
