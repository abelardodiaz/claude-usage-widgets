//! Icono de bandeja: clic izquierdo muestra/oculta; menu contextual con actualizar,
//! autoarranque y salir. Todo corre en Rust: la UI no necesita permisos de bandeja.

use std::error::Error;

use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, Runtime};
use tauri_plugin_autostart::ManagerExt;

use crate::i18n::Strings;

pub const ID_TOGGLE: &str = "toggle";
pub const ID_REFRESH: &str = "refresh";
pub const ID_AUTOSTART: &str = "autostart";
pub const ID_QUIT: &str = "quit";
/// Evento que la UI escucha para volver a pedir datos.
pub const REFRESH_EVENT: &str = "refresh";

/// Construye el icono y su menu. Se llama una vez en `setup`.
pub fn build_tray<R: Runtime>(
    app: &tauri::App<R>,
    strings: &'static Strings,
) -> Result<(), Box<dyn Error>> {
    let toggle = MenuItem::with_id(app, ID_TOGGLE, strings.tray_toggle, true, None::<&str>)?;
    let refresh = MenuItem::with_id(app, ID_REFRESH, strings.tray_refresh, true, None::<&str>)?;
    let autostart_enabled = app.autolaunch().is_enabled().unwrap_or(false);
    let autostart = CheckMenuItem::with_id(
        app,
        ID_AUTOSTART,
        strings.tray_autostart,
        true,
        autostart_enabled,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, ID_QUIT, strings.tray_quit, true, None::<&str>)?;
    let separator_a = PredefinedMenuItem::separator(app)?;
    let separator_b = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[
            &toggle,
            &refresh,
            &separator_a,
            &autostart,
            &separator_b,
            &quit,
        ],
    )?;

    let icon = app
        .default_window_icon()
        .cloned()
        .ok_or("la app no tiene icono de ventana")?;
    let autostart_item = autostart.clone();

    TrayIconBuilder::with_id("main")
        .icon(icon)
        .tooltip(strings.app_title)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| handle_menu(app, event.id.as_ref(), &autostart_item))
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                toggle_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

/// Muestra la ventana si esta oculta; la oculta si esta visible.
pub fn toggle_window<R: Runtime>(app: &AppHandle<R>) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    if window.is_visible().unwrap_or(false) {
        let _ = window.hide();
    } else {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn handle_menu<R: Runtime>(app: &AppHandle<R>, id: &str, autostart_item: &CheckMenuItem<R>) {
    match id {
        ID_TOGGLE => toggle_window(app),
        ID_REFRESH => {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
            }
            let _ = app.emit(REFRESH_EVENT, ());
        }
        ID_AUTOSTART => {
            let manager = app.autolaunch();
            let enabled = manager.is_enabled().unwrap_or(false);
            let result = if enabled {
                manager.disable()
            } else {
                manager.enable()
            };
            if let Err(e) = result {
                eprintln!("autoarranque: {e}");
            }
            // El menu ya cambio la marca al hacer clic; se alinea con el estado real.
            let _ = autostart_item.set_checked(manager.is_enabled().unwrap_or(false));
        }
        ID_QUIT => app.exit(0),
        _ => {}
    }
}
