//! R1/R1b: parseo defensivo de la respuesta de uso (OAuth y claude.ai tienen la misma forma).
//! Campos opcionales, claves desconocidas ignoradas, null tolerado; si falta lo esencial se
//! devuelve `unrecognized_format` en vez de inventar un 0 %.

use jiff::Timestamp;
use serde_json::Value;

use crate::model::{BreakdownRow, ScopedLimit, Source, Usage, Window};

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum ParseError {
    #[error("unrecognized_format")]
    UnrecognizedFormat,
}

/// Convierte la respuesta cruda en el modelo normalizado.
pub fn parse_usage(raw: &Value, source: Source) -> Result<Usage, ParseError> {
    let session = parse_window(raw.get("five_hour"))?;
    let weekly = parse_window(raw.get("seven_day"))?;
    Ok(Usage {
        source,
        session,
        weekly,
        scoped: parse_scoped(raw.get("limits")),
        breakdown: parse_breakdown(raw.get("seven_day_breakdown")),
    })
}

fn parse_window(v: Option<&Value>) -> Result<Window, ParseError> {
    let obj = v
        .and_then(Value::as_object)
        .ok_or(ParseError::UnrecognizedFormat)?;
    let percent = obj
        .get("utilization")
        .and_then(Value::as_f64)
        .ok_or(ParseError::UnrecognizedFormat)?;
    Ok(Window {
        percent,
        resets_at: parse_instant(obj.get("resets_at")),
    })
}

/// Ultimo anio admitido en el texto de un instante (R1). 9999 es el centinela de "sin limite"
/// y jiff no lo representa completo (`Timestamp::MAX` = 9999-12-30T22:00:00Z); con el anio
/// del texto acotado a 9998 el instante nunca pasa de 9999-01-01T23:59:59Z.
const MAX_TEXT_YEAR: u32 = 9998;

/// Instante de la respuesta (R1): ausente, nulo, no cadena, fuera del formato estricto o con
/// anio del texto fuera de 0000-9998 -> None.
pub fn parse_instant(v: Option<&Value>) -> Option<Timestamp> {
    let s = v?.as_str()?;
    if !strict_shape(s.as_bytes()) {
        return None;
    }
    // La forma ya garantiza cuatro digitos ASCII al principio.
    let year: u32 = s[..4].parse().ok()?;
    if year > MAX_TEXT_YEAR {
        return None;
    }
    // jiff valida el calendario (mes, dia del mes, hora) y convierte a instante.
    s.parse().ok()
}

/// Forma exacta `YYYY-MM-DDTHH:MM:SS(.fraccion)?(Z|+HH:MM|-HH:MM)` (perfil de RFC 3339):
/// anio de exactamente cuatro digitos sin signo, `T` y `Z` solo en mayuscula (RFC 3339 5.6
/// permite minusculas pero no las exige; el nucleo Java hace lo mismo), sin espacios, sin
/// anotacion `[zona]`, sin segundo 60 (jiff lo aceptaria como 59), fraccion de 1 a 9 digitos
/// y desplazamiento con horas 00-23 y minutos 00-59. Antes de jiff, que es mas permisivo.
fn strict_shape(b: &[u8]) -> bool {
    fn digits(b: &[u8], from: usize, n: usize) -> Option<u32> {
        let part = b.get(from..from + n)?;
        if !part.iter().all(u8::is_ascii_digit) {
            return None;
        }
        Some(part.iter().fold(0, |acc, d| acc * 10 + u32::from(d - b'0')))
    }
    let date_time_ok = digits(b, 0, 4).is_some()
        && b.get(4) == Some(&b'-')
        && digits(b, 5, 2).is_some()
        && b.get(7) == Some(&b'-')
        && digits(b, 8, 2).is_some()
        && b.get(10) == Some(&b'T')
        && digits(b, 11, 2).is_some()
        && b.get(13) == Some(&b':')
        && digits(b, 14, 2).is_some()
        && b.get(16) == Some(&b':')
        && digits(b, 17, 2).is_some_and(|sec| sec <= 59);
    if !date_time_ok {
        return false;
    }
    let mut i = 19;
    if b.get(i) == Some(&b'.') {
        let frac = b[i + 1..].iter().take_while(|c| c.is_ascii_digit()).count();
        if frac == 0 || frac > 9 {
            return false;
        }
        i += 1 + frac;
    }
    match b.get(i) {
        Some(b'Z') => b.len() == i + 1,
        Some(b'+' | b'-') => {
            b.len() == i + 6
                && digits(b, i + 1, 2).is_some_and(|h| h <= 23)
                && b[i + 3] == b':'
                && digits(b, i + 4, 2).is_some_and(|m| m <= 59)
        }
        _ => false,
    }
}

fn non_empty_str(v: Option<&Value>) -> Option<&str> {
    let s = v?.as_str()?;
    if s.is_empty() { None } else { Some(s) }
}

fn parse_scoped(v: Option<&Value>) -> Vec<ScopedLimit> {
    let Some(items) = v.and_then(Value::as_array) else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|item| {
            let kind = non_empty_str(item.get("kind"))?;
            if kind == "session" || kind == "weekly_all" {
                return None;
            }
            let percent = item.get("percent").and_then(Value::as_f64)?;
            let scope = item.get("scope");
            let label = non_empty_str(
                scope
                    .and_then(|s| s.get("model"))
                    .and_then(|m| m.get("display_name")),
            )
            .or_else(|| {
                non_empty_str(
                    scope
                        .and_then(|s| s.get("surface"))
                        .and_then(|m| m.get("display_name")),
                )
            })
            .unwrap_or(kind);
            Some(ScopedLimit {
                label: label.to_string(),
                percent,
                resets_at: parse_instant(item.get("resets_at")),
            })
        })
        .collect()
}

fn parse_breakdown(v: Option<&Value>) -> Vec<BreakdownRow> {
    let Some(rows) = v.and_then(|b| b.get("rows")).and_then(Value::as_array) else {
        return Vec::new();
    };
    rows.iter()
        .filter_map(|row| {
            let key = row.get("key")?.as_str()?;
            let percent = row.get("percent").and_then(Value::as_f64)?;
            let label = non_empty_str(row.get("display_name")).unwrap_or(key);
            Some(BreakdownRow {
                key: key.to_string(),
                label: label.to_string(),
                percent,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn instant(s: &str) -> Option<Timestamp> {
        parse_instant(Some(&json!(s)))
    }

    #[test]
    fn anio_extendido_con_signo_es_nulo() {
        assert_eq!(instant("+010000-01-01T00:00:00Z"), None);
        assert_eq!(instant("-000001-01-01T00:00:00Z"), None);
        assert_eq!(instant("+002026-10-02T12:00:00Z"), None);
        assert_eq!(
            instant("0000-01-01T00:00:00Z"),
            Some("0000-01-01T00:00:00Z".parse().unwrap())
        );
        assert_eq!(
            instant("2026-10-02T12:00:00-06:00"),
            Some("2026-10-02T18:00:00Z".parse().unwrap())
        );
    }

    #[test]
    fn reinicio_lejano_no_explota() {
        // 9999-12-31T23:59:59Z (centinela de "sin limite") tiene anio de texto > 9998: nulo (R1),
        // sin panico aunque jiff tampoco lo representaria.
        assert_eq!(instant("9999-12-31T23:59:59Z"), None);
        let raw = json!({
            "five_hour": { "utilization": 10, "resets_at": "9999-12-31T23:59:59Z" },
            "seven_day": { "utilization": 40, "resets_at": "9999-12-31T23:59:59Z" },
        });
        let usage = parse_usage(&raw, Source::ClaudeCode).unwrap();
        assert_eq!(usage.session.resets_at, None);
        assert_eq!(usage.weekly.resets_at, None);
    }

    fn some(s: &str) -> Option<Timestamp> {
        Some(s.parse().unwrap())
    }

    #[test]
    fn anio_del_texto_hasta_9998() {
        // R1: cuenta el anio escrito en la cadena, no el del instante en UTC (parse/07).
        assert_eq!(
            instant("9998-12-31T23:00:00-05:00"),
            some("9999-01-01T04:00:00Z")
        );
        assert_eq!(instant("9999-01-01T00:00:00+14:00"), None);
        assert_eq!(instant("9999-01-01T00:00:00Z"), None);
        assert_eq!(
            instant("9998-06-01T00:00:00Z"),
            some("9998-06-01T00:00:00Z")
        );
    }

    #[test]
    fn formato_estricto() {
        // Solo YYYY-MM-DDTHH:MM:SS(.fraccion)?(Z|+HH:MM|-HH:MM); todo lo demas es nulo.
        for bad in [
            "2026-10-02 12:00:00Z",
            "2026-10-02T12:00:00Z[UTC]",
            "2026-10-02T12:00:00-06:00[America/Mexico_City]",
            "2026-06-30T23:59:60Z",
            "+002026-10-02T12:00:00Z",
            "2026-10-02T12:00Z",
            "12:00:00.Z",
            "2026-10-02T12:00:00.Z",
            "2026-10-02t12:00:00Z",
            "2026-10-02T12:00:00z",
            "2026-10-02T12:00:00",
            "2026-10-02T12:00:00+0600",
            "2026-10-02T12:00:00+06",
            "2026-10-02T12:00:00+24:00",
            "2026-10-02T12:00:00+06:60",
            "2026-10-02T12:00:00 Z",
            " 2026-10-02T12:00:00Z",
            "2026-10-02T12:00:00Z ",
            "2026-1-02T12:00:00Z",
            "2026-10-02T1:00:00Z",
            "2026-13-02T12:00:00Z",
            "2026-02-30T12:00:00Z",
            "2026-10-02T24:00:00Z",
            "2026-10-02T12:00:00.1234567890Z",
            "2026-10-02T12:00:00,5Z",
            "2026-10-02T12:00:00.5.5Z",
            "2026-10-02T12:00:00.-5Z",
            "2026\u{2011}10-02T12:00:00Z",
            "\u{FF12}026-10-02T12:00:00Z",
            "20261002T120000Z",
        ] {
            assert_eq!(instant(bad), None, "{bad}");
        }
        assert_eq!(
            instant("2026-10-02T12:00:00Z"),
            some("2026-10-02T12:00:00Z")
        );
        assert_eq!(
            instant("2026-10-02T12:00:00.5Z"),
            some("2026-10-02T12:00:00.5Z")
        );
        assert_eq!(
            instant("2026-10-02T12:00:00.123456789+05:30"),
            some("2026-10-02T06:30:00.123456789Z")
        );
        assert_eq!(
            instant("2026-10-02T12:00:00-00:00"),
            some("2026-10-02T12:00:00Z")
        );
        assert_eq!(
            instant("2026-06-30T23:59:59Z"),
            some("2026-06-30T23:59:59Z")
        );
    }

    #[test]
    fn basura_no_es_instante() {
        assert_eq!(instant(""), None);
        assert_eq!(instant("ayer"), None);
        assert_eq!(instant("20"), None);
        assert_eq!(parse_instant(Some(&json!(5))), None);
        assert_eq!(parse_instant(None), None);
    }
}
