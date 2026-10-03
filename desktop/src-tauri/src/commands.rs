//! Comandos expuestos a la UI. Hay exactamente uno. La UI nunca recibe credenciales.

use std::sync::Arc;

use tauri::State;

use crate::service::UsageService;
use crate::view::UsageView;

pub struct AppState {
    pub service: Arc<UsageService>,
}

/// Devuelve la vista actual. `force` salta el intervalo minimo (no el backoff).
/// La consulta HTTP es sincrona: se ejecuta fuera del hilo del runtime.
#[tauri::command]
pub async fn get_usage(state: State<'_, AppState>, force: bool) -> Result<UsageView, String> {
    let service = Arc::clone(&state.service);
    tauri::async_runtime::spawn_blocking(move || service.get(force))
        .await
        .map_err(|e| e.to_string())
}
