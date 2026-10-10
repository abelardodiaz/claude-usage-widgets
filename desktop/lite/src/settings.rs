//! Preferencias de la version lite en `settings.json`, junto a las muestras del servicio.
//! Nada sensible: intervalo, siempre encima, posicion de la ventana y avisos ya mostrados.
//! "Iniciar con Windows" NO se guarda aqui: la verdad es el registro (ver `autostart`).

use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::notify::NotifyState;

pub const FILE_NAME: &str = "settings.json";
const TMP_NAME: &str = "settings.json.tmp";
/// Intervalos de consulta permitidos (minutos). El primero de la lista no es el de fabrica.
pub const INTERVALS: [u32; 2] = [3, 5];
pub const DEFAULT_INTERVAL: u32 = 5;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// Minutos entre consultas: 3 o 5.
    pub interval_min: u32,
    /// Ventana siempre encima de las demas.
    pub topmost: bool,
    /// Esquina superior izquierda de la ventana en px fisicos de pantalla.
    pub pos: Option<[i32; 2]>,
    /// Umbrales semanales ya avisados en la ventana semanal actual.
    pub notified: NotifyState,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            interval_min: DEFAULT_INTERVAL,
            topmost: true,
            pos: None,
            notified: NotifyState::default(),
        }
    }
}

impl Settings {
    /// Corrige valores fuera de rango (un archivo editado a mano no rompe nada).
    pub fn normalized(mut self) -> Self {
        if !INTERVALS.contains(&self.interval_min) {
            self.interval_min = DEFAULT_INTERVAL;
        }
        self
    }
}

pub fn path_in(dir: &Path) -> PathBuf {
    dir.join(FILE_NAME)
}

/// Lee las preferencias; si falta el archivo o esta corrupto, valores de fabrica.
pub fn load(path: &Path) -> Settings {
    fs::read_to_string(path)
        .ok()
        .and_then(|t| serde_json::from_str::<Settings>(&t).ok())
        .unwrap_or_default()
        .normalized()
}

/// Escribe en un temporal de la misma carpeta y lo renombra encima: nunca queda a medias.
pub fn save(path: &Path, s: &Settings) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_file_name(TMP_NAME);
    let text = serde_json::to_string_pretty(s)?;
    let written = (|| {
        let mut f = File::create(&tmp)?;
        f.write_all(text.as_bytes())?;
        f.sync_all()?;
        fs::rename(&tmp, path)
    })();
    if written.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    written
}

#[cfg(test)]
mod lite_tests {
    use super::*;

    #[test]
    fn fabrica_corrupto_y_fuera_de_rango() {
        let dir = tempfile::tempdir().unwrap();
        let p = path_in(dir.path());
        assert_eq!(load(&p), Settings::default());
        fs::write(&p, "{no es json").unwrap();
        assert_eq!(load(&p), Settings::default());
        fs::write(&p, r#"{"interval_min": 7, "topmost": false}"#).unwrap();
        let s = load(&p);
        assert_eq!(s.interval_min, DEFAULT_INTERVAL);
        assert!(!s.topmost);
    }

    #[test]
    fn ida_y_vuelta() {
        let dir = tempfile::tempdir().unwrap();
        let p = path_in(dir.path());
        let mut s = Settings {
            interval_min: 3,
            topmost: false,
            pos: Some([-1200, 40]),
            ..Settings::default()
        };
        s.notified.week = Some("1760000000".into());
        s.notified.fired = vec![50, 80];
        save(&p, &s).unwrap();
        assert_eq!(load(&p), s);
        assert!(!p.with_file_name(TMP_NAME).exists());
    }
}
