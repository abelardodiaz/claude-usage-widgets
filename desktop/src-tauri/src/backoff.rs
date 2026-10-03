//! Ritmo de sondeo: 2 min normales; ante 429/5xx/sin red, espera exponencial hasta 30 min.

use std::time::Duration;

/// Cada cuanto pide datos la UI.
pub const POLL_INTERVAL: Duration = Duration::from_secs(120);
/// Separacion minima entre dos consultas reales (evita martillar el endpoint).
pub const MIN_FETCH_GAP: Duration = Duration::from_secs(60);
/// Tope del backoff.
pub const MAX_BACKOFF: Duration = Duration::from_secs(30 * 60);

/// Contador de fallos transitorios consecutivos.
#[derive(Debug, Default, Clone)]
pub struct Backoff {
    failures: u32,
}

impl Backoff {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn failures(&self) -> u32 {
        self.failures
    }

    /// Registra un fallo y devuelve cuanto esperar antes del siguiente intento.
    pub fn record_failure(&mut self) -> Duration {
        self.failures = self.failures.saturating_add(1);
        self.delay()
    }

    /// Un exito borra el historial de fallos.
    pub fn reset(&mut self) {
        self.failures = 0;
    }

    /// `2 min * 2^(fallos-1)`, acotado a 30 min; cero sin fallos.
    pub fn delay(&self) -> Duration {
        if self.failures == 0 {
            return Duration::ZERO;
        }
        let exponent = self.failures.saturating_sub(1).min(16);
        let secs = POLL_INTERVAL.as_secs().saturating_mul(1_u64 << exponent);
        Duration::from_secs(secs.min(MAX_BACKOFF.as_secs()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn calendario_exponencial_hasta_30_min() {
        let mut b = Backoff::new();
        assert_eq!(b.delay(), Duration::ZERO);
        let seen: Vec<u64> = (0..7).map(|_| b.record_failure().as_secs()).collect();
        assert_eq!(seen, vec![120, 240, 480, 960, 1800, 1800, 1800]);
        assert_eq!(b.failures(), 7);
    }

    #[test]
    fn exito_reinicia() {
        let mut b = Backoff::new();
        b.record_failure();
        b.record_failure();
        b.reset();
        assert_eq!(b.failures(), 0);
        assert_eq!(b.delay(), Duration::ZERO);
        assert_eq!(b.record_failure(), Duration::from_secs(120));
    }

    #[test]
    fn no_desborda() {
        let mut b = Backoff::new();
        for _ in 0..100 {
            b.record_failure();
        }
        assert_eq!(b.delay(), MAX_BACKOFF);
    }
}
