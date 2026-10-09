//! Orquesta una consulta: decide si toca pedir datos (intervalo minimo, backoff), guarda la
//! muestra y arma la `UsageView`. Un solo `Mutex`: las llamadas concurrentes se serializan.

use std::sync::Mutex;

use jiff::Timestamp;
use jiff::tz::TimeZone;

use crate::backoff::{Backoff, MIN_FETCH_GAP};
use crate::model::Sample;
use crate::source_claude_code::ClaudeCodeSource;
use crate::store::SampleStore;
use crate::timez::{add_seconds, seconds_between};
use crate::view::{ErrorCode, Snapshot, UsageView, build};

struct Inner {
    snapshot: Snapshot,
    backoff: Backoff,
    last_attempt: Option<Timestamp>,
    attempts: u32,
}

pub struct UsageService {
    source: ClaudeCodeSource,
    store: SampleStore,
    tz: TimeZone,
    lang: String,
    inner: Mutex<Inner>,
}

impl UsageService {
    pub fn new(source: ClaudeCodeSource, store: SampleStore, tz: TimeZone, lang: &str) -> Self {
        Self {
            source,
            store,
            tz,
            lang: lang.to_string(),
            inner: Mutex::new(Inner {
                snapshot: Snapshot::default(),
                backoff: Backoff::new(),
                last_attempt: None,
                attempts: 0,
            }),
        }
    }

    pub fn store(&self) -> &SampleStore {
        &self.store
    }

    pub fn lang(&self) -> &str {
        &self.lang
    }

    /// Consultas reales hechas hasta ahora (diagnostico y tests).
    pub fn attempts(&self) -> u32 {
        self.lock().attempts
    }

    /// Vista actual; consulta la fuente si toca (o si `force`, salvo durante un backoff).
    pub fn get(&self, force: bool) -> UsageView {
        self.get_at(Timestamp::now(), force)
    }

    pub fn get_at(&self, now: Timestamp, force: bool) -> UsageView {
        let mut inner = self.lock();
        let in_backoff = inner.snapshot.retry_at.is_some_and(|t| now < t);
        let due = inner
            .last_attempt
            .is_none_or(|t| seconds_between(t, now) >= MIN_FETCH_GAP.as_secs_f64());
        if !in_backoff && (force || due) {
            self.attempt(&mut inner, now);
        }
        let samples = self.store.load();
        build(&inner.snapshot, &samples, now, &self.tz, &self.lang)
    }

    fn attempt(&self, inner: &mut Inner, now: Timestamp) {
        inner.last_attempt = Some(now);
        inner.attempts = inner.attempts.saturating_add(1);
        match self.source.fetch(now) {
            Ok(usage) => {
                inner.backoff.reset();
                inner.snapshot.retry_at = None;
                inner.snapshot.error = None;
                let sample = Sample {
                    t: now,
                    percent: usage.weekly.percent,
                    resets_at: usage.weekly.resets_at,
                };
                if let Err(e) = self.store.append(&sample, now) {
                    // Solo el tipo de error de E/S: sin rutas ni datos.
                    eprintln!("no se pudo guardar la muestra: {}", e.kind());
                }
                inner.snapshot.usage = Some(usage);
                inner.snapshot.updated_at = Some(now);
            }
            Err(err) => {
                inner.snapshot.error = Some(ErrorCode::from(&err));
                inner.snapshot.retry_at = if err.is_transient() {
                    let delay = inner.backoff.record_failure();
                    add_seconds(now, delay.as_secs_f64())
                } else {
                    inner.backoff.reset();
                    None
                };
            }
        }
    }

    /// Siembra el estado (solo pruebas): simula una consulta anterior sin red.
    #[cfg(test)]
    fn seed(&self, snapshot: Snapshot, last_attempt: Option<Timestamp>) {
        let mut inner = self.lock();
        inner.snapshot = snapshot;
        inner.last_attempt = last_attempt;
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::timez::{add_seconds, zone_from_spec};
    use crate::view::ErrorCode;

    #[test]
    fn sin_credenciales_informa_sin_backoff_y_respeta_el_intervalo() {
        let dir = tempfile::tempdir().unwrap();
        let service = UsageService::new(
            ClaudeCodeSource::new(dir.path().join("no-existe.json")),
            SampleStore::new(dir.path()),
            zone_from_spec("-06:00").unwrap(),
            "es",
        );
        let now: Timestamp = "2026-10-06T12:00:00-06:00".parse().unwrap();

        let view = service.get_at(now, true);
        assert_eq!(view.lang, "es");
        assert_eq!(service.lang(), "es");
        assert_eq!(view.error, Some(ErrorCode::NoCredentials));
        assert_eq!(
            view.retry_at, None,
            "un error de credencial no activa backoff"
        );
        assert!(view.usage.is_none());
        assert_eq!(service.attempts(), 1);
        assert!(
            service.store().load().is_empty(),
            "sin exito no se guarda muestra"
        );

        service.get_at(add_seconds(now, 30.0).unwrap(), false);
        assert_eq!(
            service.attempts(),
            1,
            "antes de 60 s no se vuelve a consultar"
        );

        service.get_at(add_seconds(now, 61.0).unwrap(), false);
        assert_eq!(service.attempts(), 2);

        service.get_at(add_seconds(now, 62.0).unwrap(), true);
        assert_eq!(service.attempts(), 3, "forzar salta el intervalo minimo");
    }

    #[test]
    fn el_backoff_se_respeta_tambien_al_forzar() {
        // D2: durante un backoff ni siquiera "actualizar" (force) consulta la fuente.
        let dir = tempfile::tempdir().unwrap();
        let service = UsageService::new(
            ClaudeCodeSource::new(dir.path().join("no-existe.json")),
            SampleStore::new(dir.path()),
            zone_from_spec("-06:00").unwrap(),
            "es",
        );
        let now: Timestamp = "2026-10-06T12:00:00-06:00".parse().unwrap();
        let retry_at = add_seconds(now, 120.0);
        service.seed(
            Snapshot {
                error: Some(ErrorCode::RateLimited),
                retry_at,
                ..Snapshot::default()
            },
            Some(now),
        );

        let view = service.get_at(add_seconds(now, 10.0).unwrap(), true);
        assert_eq!(service.attempts(), 0, "forzar no salta el backoff");
        assert_eq!(view.error, Some(ErrorCode::RateLimited));
        assert_eq!(view.retry_at, retry_at);

        service.get_at(add_seconds(now, 121.0).unwrap(), false);
        assert_eq!(
            service.attempts(),
            1,
            "pasado retry_at se vuelve a consultar"
        );
    }
}
