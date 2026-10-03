//! Modelo normalizado de uso (spec/usage-model.schema.json) y tipos derivados del contrato.

use std::collections::BTreeMap;

use jiff::Timestamp;
use serde::{Deserialize, Serialize};

/// Origen de los datos.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Source {
    ClaudeCode,
    ClaudeAi,
}

/// Ventana de uso (sesion de 5 h o semana).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Window {
    pub percent: f64,
    pub resets_at: Option<Timestamp>,
}

/// Limite acotado (por modelo o superficie).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ScopedLimit {
    pub label: String,
    pub percent: f64,
    pub resets_at: Option<Timestamp>,
}

/// Fila del desglose semanal.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BreakdownRow {
    pub key: String,
    pub label: String,
    pub percent: f64,
}

/// Modelo normalizado completo.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Usage {
    pub source: Source,
    pub session: Window,
    pub weekly: Window,
    pub scoped: Vec<ScopedLimit>,
    pub breakdown: Vec<BreakdownRow>,
}

/// Muestra del historial: (t, weekly.percent, weekly.resets_at).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Sample {
    pub t: Timestamp,
    pub percent: f64,
    pub resets_at: Option<Timestamp>,
}

/// Resultado de R4.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct TodayStats {
    pub per_day: BTreeMap<String, f64>,
    pub today_used: f64,
    pub quota_today: Option<f64>,
    pub partial: bool,
}

/// Base del ritmo usado en una proyeccion.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Basis {
    #[serde(rename = "window")]
    Window,
    #[serde(rename = "24h")]
    Last24h,
}

/// Resultado de R5/R6.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Projection {
    pub hits_at: Option<Timestamp>,
    pub before_reset: Option<bool>,
    pub basis: Option<Basis>,
}

impl Projection {
    /// Sin proyeccion.
    pub const NONE: Projection = Projection {
        hits_at: None,
        before_reset: None,
        basis: None,
    };
}
