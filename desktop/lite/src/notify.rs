//! Avisos de la semana: al llegar por primera vez al 50, 80 y 95 % de la cuota semanal, un aviso
//! por umbral y por ventana semanal. La ventana se identifica por su `resets_at`; una semana nueva
//! vuelve a armar los tres. Si de golpe se cruzan varios (p. ej. al arrancar), solo se avisa el
//! mas alto y los demas quedan marcados como avisados. Logica pura: la ventana solo la muestra.

use jiff::Timestamp;
use serde::{Deserialize, Serialize};

pub const THRESHOLDS: [u8; 3] = [50, 80, 95];

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NotifyState {
    /// Clave de la ventana semanal a la que pertenecen `fired`.
    pub week: Option<String>,
    /// Umbrales ya avisados (o saltados) en esa ventana.
    pub fired: Vec<u8>,
}

/// Clave de la ventana semanal: `resets_at` redondeado a la hora (segundos epoch). El redondeo
/// evita que una diferencia de segundos entre respuestas parezca una semana nueva.
pub fn week_key(resets_at: Option<Timestamp>) -> Option<String> {
    let secs = resets_at?.as_second();
    Some(
        (secs + 1800)
            .div_euclid(3600)
            .saturating_mul(3600)
            .to_string(),
    )
}

/// Nuevo estado y, si toca, el umbral a avisar. Sin clave de semana no se avisa nada.
pub fn evaluate(
    state: &NotifyState,
    week: Option<&str>,
    percent: f64,
) -> (NotifyState, Option<u8>) {
    let Some(week) = week else {
        return (state.clone(), None);
    };
    let mut fired = if state.week.as_deref() == Some(week) {
        state.fired.clone()
    } else {
        Vec::new()
    };
    let crossed: Vec<u8> = THRESHOLDS
        .iter()
        .copied()
        .filter(|t| percent >= f64::from(*t) && !fired.contains(t))
        .collect();
    let show = crossed.iter().copied().max();
    fired.extend(crossed);
    fired.sort_unstable();
    (
        NotifyState {
            week: Some(week.to_string()),
            fired,
        },
        show,
    )
}

#[cfg(test)]
mod lite_tests {
    use super::*;

    fn st(week: &str, fired: &[u8]) -> NotifyState {
        NotifyState {
            week: Some(week.into()),
            fired: fired.to_vec(),
        }
    }

    #[test]
    fn debajo_del_primer_umbral_no_avisa() {
        let (s, show) = evaluate(&NotifyState::default(), Some("w1"), 49.9);
        assert_eq!(show, None);
        assert_eq!(s, st("w1", &[]));
    }

    #[test]
    fn cada_umbral_una_sola_vez() {
        let (s, show) = evaluate(&st("w1", &[]), Some("w1"), 50.0);
        assert_eq!(show, Some(50));
        let (s, show) = evaluate(&s, Some("w1"), 63.0);
        assert_eq!(show, None);
        let (s, show) = evaluate(&s, Some("w1"), 80.2);
        assert_eq!(show, Some(80));
        let (s, show) = evaluate(&s, Some("w1"), 81.0);
        assert_eq!(show, None);
        let (s, show) = evaluate(&s, Some("w1"), 99.0);
        assert_eq!(show, Some(95));
        assert_eq!(s, st("w1", &[50, 80, 95]));
        assert_eq!(evaluate(&s, Some("w1"), 100.0).1, None);
    }

    #[test]
    fn arrancar_por_encima_de_varios_solo_el_mas_alto() {
        let (s, show) = evaluate(&NotifyState::default(), Some("w1"), 86.0);
        assert_eq!(show, Some(80));
        assert_eq!(s, st("w1", &[50, 80]));
        let (_, show) = evaluate(&NotifyState::default(), Some("w1"), 97.0);
        assert_eq!(show, Some(95));
    }

    #[test]
    fn semana_nueva_vuelve_a_armar() {
        let old = st("w1", &[50, 80, 95]);
        let (s, show) = evaluate(&old, Some("w2"), 10.0);
        assert_eq!(show, None);
        assert_eq!(s, st("w2", &[]));
        let (_, show) = evaluate(&s, Some("w2"), 55.0);
        assert_eq!(show, Some(50));
    }

    #[test]
    fn reinicio_de_la_app_no_repite() {
        // Estado persistido tras avisar el 80: al volver a arrancar con 82 % no se repite.
        let persisted = st("w1", &[50, 80]);
        assert_eq!(evaluate(&persisted, Some("w1"), 82.0).1, None);
    }

    #[test]
    fn sin_semana_no_avisa_ni_toca_el_estado() {
        let old = st("w1", &[50]);
        let (s, show) = evaluate(&old, None, 99.0);
        assert_eq!(show, None);
        assert_eq!(s, old);
    }

    #[test]
    fn clave_redondeada_a_la_hora() {
        let a: Timestamp = "2026-10-09T18:00:00Z".parse().unwrap();
        let b: Timestamp = "2026-10-09T18:00:41.5Z".parse().unwrap();
        let c: Timestamp = "2026-10-09T17:59:12Z".parse().unwrap();
        assert_eq!(week_key(Some(a)), week_key(Some(b)));
        assert_eq!(week_key(Some(a)), week_key(Some(c)));
        assert_ne!(
            week_key(Some(a)),
            week_key(Some("2026-10-16T18:00:00Z".parse().unwrap()))
        );
        assert_eq!(week_key(None), None);
    }
}
