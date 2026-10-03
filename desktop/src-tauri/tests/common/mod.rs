//! Utilidades compartidas por los tests de contrato: carga de fixtures y aserciones con las
//! tolerancias de R0 (numeros 0.001, instantes 1 s).
#![allow(dead_code)]

use std::fs;
use std::path::PathBuf;

use claude_usage_widgets_lib::model::{Basis, Sample, Window};
use jiff::Timestamp;
use serde_json::Value;

pub const NUM_TOL: f64 = 0.001;
pub const INSTANT_TOL_SECS: f64 = 1.0;

/// Carpeta spec/fixtures/<kind>, relativa al crate.
pub fn fixtures_dir(kind: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
        .join("spec")
        .join("fixtures")
        .join(kind)
}

/// Todos los fixtures de una carpeta, ordenados por nombre: (nombre, json).
/// Entra en panico si la carpeta falta o no tiene ningun `.json`.
pub fn load_fixtures(kind: &str) -> Vec<(String, Value)> {
    let dir = fixtures_dir(kind);
    let mut paths: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("no se pudo leer {}: {e}", dir.display()))
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "json"))
        .collect();
    paths.sort();
    // Una familia sin fixtures no prueba nada: pasar en verde con cero casos es un fallo.
    assert!(!paths.is_empty(), "{} no tiene fixtures", dir.display());
    paths
        .into_iter()
        .map(|p| {
            let name = p.file_name().unwrap().to_string_lossy().into_owned();
            let text = fs::read_to_string(&p).unwrap();
            let json: Value = serde_json::from_str(&text)
                .unwrap_or_else(|e| panic!("{name}: JSON invalido: {e}"));
            (name, json)
        })
        .collect()
}

/// Instante opcional a partir de un valor JSON (cadena RFC 3339 o null).
pub fn instant(v: &Value) -> Option<Timestamp> {
    v.as_str().map(|s| {
        s.parse()
            .unwrap_or_else(|e| panic!("instante invalido {s}: {e}"))
    })
}

/// Ventana `{ percent, resets_at }` de un valor JSON.
pub fn window(v: &Value) -> Window {
    Window {
        percent: v["percent"].as_f64().expect("percent numerico"),
        resets_at: instant(&v["resets_at"]),
    }
}

/// Lista de muestras `{ t, percent, resets_at }`.
pub fn samples(v: &Value) -> Vec<Sample> {
    v.as_array()
        .expect("samples es arreglo")
        .iter()
        .map(|s| serde_json::from_value(s.clone()).expect("muestra valida"))
        .collect()
}

pub fn basis(v: &Value) -> Option<Basis> {
    match v.as_str() {
        None => None,
        Some("window") => Some(Basis::Window),
        Some("24h") => Some(Basis::Last24h),
        Some(other) => panic!("basis desconocida {other}"),
    }
}

pub fn assert_num(name: &str, expected: f64, actual: f64) {
    assert!(
        (expected - actual).abs() <= NUM_TOL,
        "{name}: esperado {expected} obtenido {actual}"
    );
}

pub fn assert_num_opt(name: &str, expected: Option<f64>, actual: Option<f64>) {
    match (expected, actual) {
        (None, None) => {}
        (Some(e), Some(a)) => assert_num(name, e, a),
        (e, a) => panic!("{name}: esperado {e:?} obtenido {a:?}"),
    }
}

pub fn assert_instant(name: &str, expected: Option<Timestamp>, actual: Option<Timestamp>) {
    match (expected, actual) {
        (None, None) => {}
        (Some(e), Some(a)) => {
            let diff = e.duration_since(a).abs().as_secs_f64();
            assert!(
                diff <= INSTANT_TOL_SECS,
                "{name}: esperado {e} obtenido {a} (dif {diff} s)"
            );
        }
        (e, a) => panic!("{name}: esperado {e:?} obtenido {a:?}"),
    }
}
