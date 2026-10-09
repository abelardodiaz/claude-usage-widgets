//! Cascara Tauri: plugins, estado compartido, bandeja, ventana y arranque.

pub mod backoff;
pub mod colors;
pub mod commands;
pub mod credentials;
pub mod history;
pub mod i18n;
pub mod model;
pub mod parse;
pub mod projection;
pub mod service;
pub mod source_claude_code;
pub mod store;
pub mod timez;
pub mod tray;
pub mod view;

use std::sync::Arc;

use tauri::{Manager, PhysicalPosition, RunEvent, WindowEvent};
use tauri_plugin_autostart::MacosLauncher;
use tauri_plugin_window_state::{StateFlags, WindowExt};

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

/// Fuente de Claude Code + almacen en la carpeta de datos local de la app + zona del sistema.
fn build_service(app: &tauri::App, lang: &str) -> Result<UsageService, Box<dyn std::error::Error>> {
    let data_dir = app.path().app_local_data_dir()?;
    std::fs::create_dir_all(&data_dir)?;
    let credentials_path =
        credentials::default_path().ok_or("no se pudo determinar la carpeta del usuario")?;
    Ok(UsageService::new(
        ClaudeCodeSource::new(credentials_path),
        SampleStore::new(&data_dir),
        timez::system_zone(),
        lang,
    ))
}

pub fn run() {
    tauri::Builder::default()
        // Debe ser el primer plugin: una segunda instancia solo enfoca la primera.
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        // Solo la posicion: no se guarda VISIBLE, para que ocultar a la bandeja no la deje
        // oculta en el siguiente arranque. Sin restauracion automatica al crear la ventana: el
        // plugin guardaria en su cache la posicion que da el sistema y `restore_state` en `setup`
        // desharia `place_top_right` en el primer arranque. Se restaura a mano en `setup`.
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(StateFlags::POSITION)
                .skip_initial_state("main")
                .build(),
        )
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .invoke_handler(tauri::generate_handler![commands::get_usage])
        // Cerrar la ventana la oculta; salir de verdad es el item de la bandeja.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .setup(|app| {
            let locale = sys_locale::get_locale().unwrap_or_default();
            let lang = language_from_locale(&locale);
            let service = build_service(app, lang)?;
            app.manage(AppState {
                service: Arc::new(service),
            });
            tray::build_tray(app, i18n::strings(lang))?;
            let window = app
                .get_webview_window("main")
                .ok_or("no existe la ventana main")?;
            place_top_right(&window)?;
            window.restore_state(StateFlags::POSITION)?;
            window.show()?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error al construir la aplicacion")
        .run(|_app, event| {
            // Sin ventanas visibles la app sigue viva en la bandeja; app.exit(0) trae code = Some.
            if let RunEvent::ExitRequested {
                api, code: None, ..
            } = event
            {
                api.prevent_exit();
            }
        });
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

    /// Guarda de seguridad: la UI solo puede escuchar eventos, ajustar su alto, arrastrar y
    /// ocultar la ventana. Agregar cualquier permiso (por ejemplo `core:event:allow-emit` o un
    /// comando de plugin) tiene que pasar por este test y por la revision.
    #[test]
    fn capacidades_de_la_ui_son_exactamente_las_esperadas() {
        let raw = include_str!("../capabilities/default.json");
        let json: serde_json::Value = serde_json::from_str(raw).expect("default.json valido");
        let mut got: Vec<&str> = json["permissions"]
            .as_array()
            .expect("permissions es un arreglo")
            .iter()
            .map(|p| p.as_str().expect("cada permiso es una cadena"))
            .collect();
        got.sort_unstable();
        let mut want = vec![
            "core:event:allow-listen",
            "core:event:allow-unlisten",
            "core:window:allow-set-size",
            "core:window:allow-start-dragging",
            "core:window:allow-hide",
        ];
        want.sort_unstable();
        assert_eq!(got, want);
        assert_eq!(json["windows"], serde_json::json!(["main"]));
    }
}
