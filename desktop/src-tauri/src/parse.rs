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

/// Instante RFC 3339; ausente, nulo, no cadena o no parseable -> None.
///
/// RFC 3339 solo admite anios de cuatro digitos (0000-9999). jiff acepta ademas anios
/// extendidos con signo (`+010000`, `-000001`); eso no es un dato sino una entrada hostil, y se
/// degrada a None igual que en el nucleo Java. Un instante que jiff no puede representar
/// (su `Timestamp::MAX` es 9999-12-30T22:00:00Z) tambien sale None.
pub fn parse_instant(v: Option<&Value>) -> Option<Timestamp> {
    let s = v?.as_str()?;
    let year = s.get(..4)?;
    if !year.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse().ok()
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
    fn anio_fuera_de_0000_9999_es_nulo() {
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
        // 9999-12-31T23:59:59Z es RFC 3339 valido pero queda fuera del rango de jiff: se degrada
        // a nulo en vez de entrar en panico.
        assert_eq!(instant("9999-12-31T23:59:59Z"), None);
        let raw = json!({
            "five_hour": { "utilization": 10, "resets_at": "9999-12-31T23:59:59Z" },
            "seven_day": { "utilization": 40, "resets_at": "9999-12-31T23:59:59Z" },
        });
        let usage = parse_usage(&raw, Source::ClaudeCode).unwrap();
        assert_eq!(usage.session.resets_at, None);
        assert_eq!(usage.weekly.resets_at, None);
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
