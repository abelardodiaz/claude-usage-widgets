//! Cascara Tauri: estado compartido, ventana y arranque.

pub mod backoff;
pub mod colors;
pub mod commands;
pub mod credentials;
pub mod history;
pub mod model;
pub mod parse;
pub mod projection;
pub mod service;
pub mod source_claude_code;
pub mod store;
pub mod timez;
pub mod view;

use std::sync::Arc;

use tauri::{Manager, PhysicalPosition};

use crate::commands::AppState;
use crate::service::UsageService;
use crate::source_claude_code::ClaudeCodeSource;
use crate::store::SampleStore;

/// Ancho logico de la ventana (la UI ajusta solo el alto).
pub const WINDOW_WIDTH: f64 = 380.0;

/// "es" si la etiqueta BCP 47 del sistema empieza por "es"; "en" en cualquier otro caso.
pub fn language_from_locale(locale: &str) -> &'static str {
    if locale.to_ascii_lowercase().starts_with("es") {
        "es"
    } else {
        "en"
    }
}

/// Coloca la ventana arriba a la derecha del monitor principal (24 px de margen, 40 px de alto).
fn place_top_right(window: &tauri::WebviewWindow) -> tauri::Result<()> {
    if let Some(monitor) = window.primary_monitor()? {
        let scale = monitor.scale_factor();
        let width_px = (WINDOW_WIDTH * scale) as i32;
        let margin_px = (24.0 * scale) as i32;
        let top_px = (40.0 * scale) as i32;
        let origin = monitor.position();
        let x = origin.x + monitor.size().width as i32 - width_px - margin_px;
        let y = origin.y + top_px;
        window.set_position(PhysicalPosition::new(x, y))?;
    }
    Ok(())
}

/// Fuente de Claude Code + almacen en la carpeta de datos local de la app + zona e idioma del sistema.
fn build_service(app: &tauri::App) -> Result<UsageService, Box<dyn std::error::Error>> {
    let data_dir = app.path().app_local_data_dir()?;
    std::fs::create_dir_all(&data_dir)?;
    let credentials_path =
        credentials::default_path().ok_or("no se pudo determinar la carpeta del usuario")?;
    let locale = sys_locale::get_locale().unwrap_or_default();
    Ok(UsageService::new(
        ClaudeCodeSource::new(credentials_path),
        SampleStore::new(&data_dir),
        timez::system_zone(),
        language_from_locale(&locale),
    ))
}

pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![commands::get_usage])
        .setup(|app| {
            let service = build_service(app)?;
            app.manage(AppState {
                service: Arc::new(service),
            });
            let window = app
                .get_webview_window("main")
                .ok_or("no existe la ventana main")?;
            place_top_right(&window)?;
            window.show()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error al arrancar la aplicacion");
}

#[cfg(test)]
mod tests {
    use super::language_from_locale;

    #[test]
    fn idioma_por_locale() {
        assert_eq!(language_from_locale("es-MX"), "es");
        assert_eq!(language_from_locale("ES"), "es");
        assert_eq!(language_from_locale("en-US"), "en");
        assert_eq!(language_from_locale("pt-BR"), "en");
        assert_eq!(language_from_locale(""), "en");
    }
}
