//! Comprobacion MANUAL de actualizaciones (decision D1): solo cuando el usuario la pide desde
//! la bandeja; nunca al arrancar ni periodica. Unico contacto con github.com del producto.

use serde::Serialize;
use tauri::{AppHandle, Emitter, Runtime};
use tauri_plugin_updater::UpdaterExt;

/// Evento con el estado para la UI (ella lo traduce).
pub const EVENT: &str = "update-status";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateStatus {
    Checking,
    UpToDate,
    Installing,
    Failed,
}

/// Comprueba, descarga e instala si hay version nueva. En Windows el instalador cierra la app.
pub async fn check_and_install<R: Runtime>(app: AppHandle<R>) {
    let _ = app.emit(EVENT, UpdateStatus::Checking);
    let status = match run(&app).await {
        Ok(true) => UpdateStatus::Installing,
        Ok(false) => UpdateStatus::UpToDate,
        Err(_) => UpdateStatus::Failed,
    };
    let _ = app.emit(EVENT, status);
}

async fn run<R: Runtime>(app: &AppHandle<R>) -> tauri_plugin_updater::Result<bool> {
    let Some(update) = app.updater()?.check().await? else {
        return Ok(false);
    };
    let _ = app.emit(EVENT, UpdateStatus::Installing);
    update
        .download_and_install(|_chunk, _total| {}, || {})
        .await?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::UpdateStatus;

    /// La UI traduce exactamente estas cuatro cadenas (`T.update[...]` en `ui/i18n.js`).
    #[test]
    fn estados_en_snake_case() {
        let got: Vec<String> = [
            UpdateStatus::Checking,
            UpdateStatus::UpToDate,
            UpdateStatus::Installing,
            UpdateStatus::Failed,
        ]
        .iter()
        .map(|s| serde_json::to_string(s).expect("serializa"))
        .collect();
        assert_eq!(
            got,
            [
                "\"checking\"",
                "\"up_to_date\"",
                "\"installing\"",
                "\"failed\""
            ]
        );
    }

    /// Guarda de D1: la comprobacion solo se dispara desde el item de bandeja. Recorre todo
    /// `src/` y exige que `check_and_install` y `.updater()` solo aparezcan en este archivo,
    /// salvo una unica llamada en `tray.rs` dentro del brazo `ID_UPDATE` de `handle_menu`.
    /// Si alguien la agrega en `setup`, en un hilo o en un temporizador, este test falla.
    #[test]
    fn updater_solo_desde_el_item_de_bandeja() {
        let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut tray_calls = 0;
        for entry in std::fs::read_dir(&src).expect("lee src/") {
            let path = entry.expect("entrada").path();
            if path.extension().and_then(|e| e.to_str()) != Some("rs") {
                continue;
            }
            let name = path.file_name().unwrap().to_string_lossy().into_owned();
            if name == "updater.rs" {
                continue;
            }
            let code = std::fs::read_to_string(&path).expect("lee el archivo");
            assert!(
                !code.contains(".updater()") && !code.contains("UpdaterExt"),
                "{name} usa el updater directamente"
            );
            let calls = code.matches("check_and_install").count();
            if name == "tray.rs" {
                tray_calls = calls;
                let arm = code
                    .find("ID_UPDATE =>")
                    .expect("tray.rs tiene el brazo ID_UPDATE");
                let call = code.find("check_and_install").expect("tray.rs lo llama");
                assert!(
                    call > arm && call - arm < 200,
                    "la llamada debe estar dentro del brazo ID_UPDATE"
                );
            } else {
                assert_eq!(calls, 0, "{name} dispara el updater");
            }
        }
        assert_eq!(tray_calls, 1, "tray.rs debe llamarlo exactamente una vez");
    }
}
