//! Almacen local de muestras: una linea JSON por muestra (`t`, `percent`, `resets_at`),
//! en la carpeta de datos de la app, recortado a 15 dias en cada escritura.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use jiff::{SignedDuration, Timestamp};

use crate::model::Sample;

/// Dias que se conservan.
pub const RETENTION: SignedDuration = SignedDuration::from_secs(15 * 86_400);
/// Nombre del archivo dentro de la carpeta de datos.
pub const FILE_NAME: &str = "samples.jsonl";

pub struct SampleStore {
    path: PathBuf,
}

impl SampleStore {
    /// Almacen en `dir/samples.jsonl` (la carpeta se crea al escribir).
    pub fn new(dir: &Path) -> Self {
        Self {
            path: dir.join(FILE_NAME),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Todas las muestras validas, en el orden del archivo. Lineas corruptas se ignoran.
    pub fn load(&self) -> Vec<Sample> {
        let Ok(text) = fs::read_to_string(&self.path) else {
            return Vec::new();
        };
        text.lines()
            .filter_map(|line| serde_json::from_str::<Sample>(line).ok())
            .collect()
    }

    /// Agrega una muestra y descarta las anteriores a `now - 15 dias`.
    pub fn append(&self, sample: &Sample, now: Timestamp) -> io::Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let cutoff = now
            .checked_sub(RETENTION)
            .map_err(|e| io::Error::other(e.to_string()))?;
        let mut all = self.load();
        all.push(sample.clone());
        let kept: Vec<&Sample> = all.iter().filter(|s| s.t >= cutoff).collect();
        if kept.len() == all.len() {
            let mut file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.path)?;
            writeln!(file, "{}", serde_json::to_string(sample)?)?;
        } else {
            let mut text = String::new();
            for s in kept {
                text.push_str(&serde_json::to_string(s)?);
                text.push('\n');
            }
            fs::write(&self.path, text)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ts(s: &str) -> Timestamp {
        s.parse().unwrap()
    }

    fn sample(t: &str, percent: f64) -> Sample {
        Sample {
            t: ts(t),
            percent,
            resets_at: Some(ts("2026-10-09T18:00:00-06:00")),
        }
    }

    #[test]
    fn carpeta_vacia_devuelve_nada() {
        let dir = tempfile::tempdir().unwrap();
        let store = SampleStore::new(dir.path());
        assert!(store.load().is_empty());
    }

    #[test]
    fn agrega_y_lee_en_orden() {
        let dir = tempfile::tempdir().unwrap();
        let store = SampleStore::new(&dir.path().join("sub"));
        let now = ts("2026-10-06T12:00:00-06:00");
        store
            .append(&sample("2026-10-06T10:00:00-06:00", 20.0), now)
            .unwrap();
        store
            .append(&sample("2026-10-06T12:00:00-06:00", 25.0), now)
            .unwrap();
        let all = store.load();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].percent, 20.0);
        assert_eq!(all[1].t, ts("2026-10-06T18:00:00+00:00"));
        assert_eq!(all[1].resets_at, Some(ts("2026-10-10T00:00:00+00:00")));
    }

    #[test]
    fn recorta_a_quince_dias() {
        let dir = tempfile::tempdir().unwrap();
        let store = SampleStore::new(dir.path());
        let now = ts("2026-10-20T12:00:00-06:00");
        store
            .append(&sample("2026-10-05T11:59:59-06:00", 1.0), now)
            .unwrap(); // 15 d + 1 s: fuera
        store
            .append(&sample("2026-10-05T12:00:00-06:00", 2.0), now)
            .unwrap(); // exactamente 15 d: dentro
        store
            .append(&sample("2026-10-20T12:00:00-06:00", 3.0), now)
            .unwrap();
        let all = store.load();
        assert_eq!(
            all.iter().map(|s| s.percent).collect::<Vec<_>>(),
            vec![2.0, 3.0]
        );
    }

    #[test]
    fn ignora_lineas_corruptas() {
        let dir = tempfile::tempdir().unwrap();
        let store = SampleStore::new(dir.path());
        let now = ts("2026-10-06T12:00:00-06:00");
        store
            .append(&sample("2026-10-06T10:00:00-06:00", 20.0), now)
            .unwrap();
        let mut text = std::fs::read_to_string(store.path()).unwrap();
        text.push_str("esto no es json\n{\"t\":\"ayer\",\"percent\":1,\"resets_at\":null}\n");
        std::fs::write(store.path(), text).unwrap();
        assert_eq!(store.load().len(), 1);
    }
}
