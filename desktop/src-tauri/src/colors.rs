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

/// Estado de la barra de hoy (R7). Viaja como "unknown" | "exhausted" | "ok".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TodayState {
    /// Sin cuota calculable (`quota_today` nulo).
    Unknown,
    /// `quota_today <= 0`: la semana ya esta en el 100 % o por encima, hoy no queda nada.
    Exhausted,
    /// Cuota positiva.
    Ok,
}

/// Relleno de la barra de hoy (R7): estado y fraccion gastada del cupo.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct TodayFill {
    pub state: TodayState,
    /// Nula con `Unknown`; 1 con `Exhausted`; con `Ok`, `today_used / quota_today` crudo en doble
    /// precision, SIN acotar (puede pasar de 1). La UI la acota a [0, 1] solo para dibujar.
    pub fraction: Option<f64>,
}

/// Relleno de hoy, en este orden: cuota nula -> unknown; cuota <= 0 -> exhausted (fraccion 1);
/// si no, ok con el cociente crudo.
pub fn today_fill(today_used: f64, quota_today: Option<f64>) -> TodayFill {
    match quota_today {
        None => TodayFill {
            state: TodayState::Unknown,
            fraction: None,
        },
        Some(quota) if quota <= 0.0 => TodayFill {
            state: TodayState::Exhausted,
            fraction: Some(1.0),
        },
        Some(quota) => TodayFill {
            state: TodayState::Ok,
            fraction: Some(today_used / quota),
        },
    }
}

/// Barra de hoy, sobre `today_fill`: unknown -> gris; exhausted (cuota <= 0) -> rojo; ok, por el
/// cociente en doble precision: verde < 0.7, ambar < 1, rojo >= 1.
pub fn today_color(today_used: f64, quota_today: Option<f64>) -> Color {
    let fill = today_fill(today_used, quota_today);
    match (fill.state, fill.fraction) {
        (TodayState::Unknown, _) => Color::Gray,
        (TodayState::Exhausted, _) => Color::Red,
        (TodayState::Ok, Some(ratio)) if ratio < 0.7 => Color::Green,
        (TodayState::Ok, Some(ratio)) if ratio < 1.0 => Color::Amber,
        (TodayState::Ok, _) => Color::Red,
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
    fn cuota_cero_es_roja_y_nula_gris() {
        // 0 = la semana justo agotada (el caso frecuente): rojo, no "no se puede calcular".
        assert_eq!(today_color(0.0, Some(0.0)), Color::Red);
        assert_eq!(today_color(10.0, Some(0.0)), Color::Red);
        assert_eq!(today_color(10.0, None), Color::Gray);
        assert_eq!(
            today_fill(0.0, Some(0.0)),
            TodayFill {
                state: TodayState::Exhausted,
                fraction: Some(1.0)
            }
        );
        assert_eq!(today_fill(15.0, Some(10.0)).fraction, Some(1.5));
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
