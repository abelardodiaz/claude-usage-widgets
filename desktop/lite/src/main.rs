//! claude-usage-widgets-lite: widget nativo minimo para Windows, Win32 + GDI.
//! Sin WebView y sin Tauri: solo sesion 5 h y semana, con su barra y su reinicio, y un pie.
//!
//! El nucleo NO se reescribe: se compilan aqui, tal cual, los modulos sin tauri de
//! `desktop/src-tauri/src` (credenciales, fuente de Anthropic, servicio con intervalo minimo y
//! backoff, colores R7, vista). Viven en la raiz del crate para que sus `crate::...` resuelvan.
//!
//! IMPORTANTE: los modulos incluidos con `#[path]` deben seguir libres de tauri (ni `use tauri`
//! ni dependencias que este crate no tenga). Si uno de ellos empieza a necesitar tauri, la
//! integracion va en `lib.rs` de src-tauri, no en el modulo. El job `lite` del CI lo comprueba.

#![cfg_attr(not(test), windows_subsystem = "windows")]

#[cfg(not(windows))]
compile_error!("claude-usage-widgets-lite es solo para Windows");

// Nucleo compartido. `dead_code` permitido: este binario usa solo una parte de cada modulo.
#[allow(dead_code)]
#[path = "../../src-tauri/src/backoff.rs"]
mod backoff;
#[allow(dead_code)]
#[path = "../../src-tauri/src/colors.rs"]
mod colors;
#[allow(dead_code)]
#[path = "../../src-tauri/src/credentials.rs"]
mod credentials;
#[allow(dead_code)]
#[path = "../../src-tauri/src/history.rs"]
mod history;
#[allow(dead_code)]
#[path = "../../src-tauri/src/i18n.rs"]
mod i18n;
#[allow(dead_code)]
#[path = "../../src-tauri/src/model.rs"]
mod model;
#[allow(dead_code)]
#[path = "../../src-tauri/src/parse.rs"]
mod parse;
#[allow(dead_code)]
#[path = "../../src-tauri/src/projection.rs"]
mod projection;
#[allow(dead_code)]
#[path = "../../src-tauri/src/service.rs"]
mod service;
#[allow(dead_code)]
#[path = "../../src-tauri/src/source_claude_code.rs"]
mod source_claude_code;
#[allow(dead_code)]
#[path = "../../src-tauri/src/store.rs"]
mod store;
#[allow(dead_code)]
#[path = "../../src-tauri/src/timez.rs"]
mod timez;
#[allow(dead_code)]
#[path = "../../src-tauri/src/view.rs"]
mod view;

// Propio de la version lite.
mod app;
mod autostart;
mod format;
mod notify;
mod placement;
mod settings;

fn main() {
    app::run();
}
