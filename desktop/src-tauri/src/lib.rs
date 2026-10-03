//! Cascara Tauri: construye la ventana y arranca la app.

pub mod model;
pub mod timez;

use tauri::{Manager, PhysicalPosition};

/// Ancho logico de la ventana (la UI ajusta solo el alto).
pub const WINDOW_WIDTH: f64 = 380.0;

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

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
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
