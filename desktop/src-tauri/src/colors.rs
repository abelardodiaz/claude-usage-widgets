//! R7: colores de las barras y marca de ritmo parejo. Unica implementacion de los umbrales:
//! la UI solo mapea el nombre del color a su paleta.

use jiff::Timestamp;
use serde::Serialize;

use crate::timez::seconds_between;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Color {
    Green,
    Amber,
    Red,
    Gray,
}

/// Barras de sesion, semana y limitados, por `percent` crudo: verde < 60, ambar < 85, rojo >= 85.
pub fn bar_color(percent: f64) -> Color {
    if percent < 60.0 {
        Color::Green
    } else if percent < 85.0 {
        Color::Amber
    } else {
        Color::Red
    }
}

/// Barra de hoy, en este orden: cuota nula -> gris; negativa -> rojo (la cuota semanal ya se
/// agoto); cero -> gris; si no, por el cociente `today_used / quota_today` en doble precision:
/// verde < 0.7, ambar < 1, rojo >= 1.
pub fn today_color(today_used: f64, quota_today: Option<f64>) -> Color {
    let Some(quota) = quota_today else {
        return Color::Gray;
    };
    if quota < 0.0 {
        return Color::Red;
    }
    if quota == 0.0 {
        return Color::Gray;
    }
    let ratio = today_used / quota;
    if ratio < 0.7 {
        Color::Green
    } else if ratio < 1.0 {
        Color::Amber
    } else {
        Color::Red
    }
}

/// Marca de ritmo parejo: fraccion transcurrida de la ventana de 7 dias, acotada a [0, 1].
/// Con el reinicio ya pasado vale 1 (la ventana transcurrio entera); nula solo sin `resets_at`.
pub fn pace_mark(now: Timestamp, resets_at: Option<Timestamp>) -> Option<f64> {
    let resets_at = resets_at?;
    let fraction = 1.0 - seconds_between(now, resets_at) / (7.0 * 86_400.0);
    Some(fraction.clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cociente_no_multiplicacion_cruzada() {
        // Con cuota negativa, `used < 0.7 * quota` daria otro resultado; el paso 2 lo evita.
        assert_eq!(today_color(0.5, Some(-0.266667)), Color::Red);
        assert_eq!(today_color(-1.0, Some(10.0)), Color::Green);
    }

    #[test]
    fn marca_con_reinicio_lejano_no_explota() {
        // "9999-12-31T23:59:59Z" no cabe en jiff (el parseo lo deja nulo); con el instante mas
        // lejano representable la marca queda acotada a 0, igual que en el nucleo Java.
        let now: Timestamp = "2026-10-06T12:00:00-06:00".parse().unwrap();
        assert_eq!(pace_mark(now, Some(Timestamp::MAX)), Some(0.0));
        assert_eq!(pace_mark(Timestamp::MAX, Some(Timestamp::MIN)), Some(1.0));
        assert_eq!(pace_mark(now, None), None);
    }
}
