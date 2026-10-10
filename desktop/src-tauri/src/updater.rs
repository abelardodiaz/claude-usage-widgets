//! Comprobacion MANUAL de actualizaciones (decision D1): solo cuando el usuario la pide desde
//! la bandeja; nunca al arrancar ni periodica. Unico contacto con github.com del producto.

use std::sync::atomic::{AtomicBool, Ordering};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Runtime};
use tauri_plugin_updater::{Error, UpdaterExt};

/// Evento con el estado para la UI (ella lo traduce).
pub const EVENT: &str = "update-status";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateStatus {
    Checking,
    UpToDate,
    Installing,
    /// Fallo antes de saber si hay version nueva (red, manifiesto, configuracion).
    Failed,
    /// Habia version nueva pero la descarga, la firma o el instalador fallaron.
    InstallFailed,
}

/// Una sola comprobacion a la vez: dos clics seguidos no lanzan dos descargas.
static IN_PROGRESS: AtomicBool = AtomicBool::new(false);

/// Marca de "comprobacion en curso"; se libera al soltarla, tambien si algo falla.
struct InProgress;

impl InProgress {
    fn try_begin() -> Option<Self> {
        IN_PROGRESS
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .ok()
            .map(|_| InProgress)
    }
}

impl Drop for InProgress {
    fn drop(&mut self) {
        IN_PROGRESS.store(false, Ordering::Release);
    }
}

/// Tipo de error, sin URLs ni detalles: solo para el diagnostico en consola.
fn error_kind(e: &Error) -> &'static str {
    match e {
        Error::Reqwest(_) | Error::Network(_) | Error::Http(_) => "red",
        Error::Minisign(_)
        | Error::Base64(_)
        | Error::SignatureUtf8(_)
        | Error::SignedVersionMismatch { .. }
        | Error::MissingSignedVersion => "firma",
        Error::ReleaseNotFound | Error::Serialization(_) | Error::Semver(_) => "manifiesto",
        _ => "otro",
    }
}

/// Comprueba, descarga e instala si hay version nueva. En Windows el instalador cierra la app.
/// Si ya hay una comprobacion en curso, no hace nada.
pub async fn check_and_install<R: Runtime>(app: AppHandle<R>) {
    let Some(_guard) = InProgress::try_begin() else {
        return;
    };
    let _ = app.emit(EVENT, UpdateStatus::Checking);
    let status = run(&app).await;
    let _ = app.emit(EVENT, status);
}

async fn run<R: Runtime>(app: &AppHandle<R>) -> UpdateStatus {
    let checked = match app.updater() {
        Ok(updater) => updater.check().await,
        Err(e) => Err(e),
    };
    let update = match checked {
        Ok(Some(update)) => update,
        Ok(None) => return UpdateStatus::UpToDate,
        Err(e) => {
            eprintln!("actualizaciones: no se pudo comprobar ({})", error_kind(&e));
            return UpdateStatus::Failed;
        }
    };
    let _ = app.emit(EVENT, UpdateStatus::Installing);
    match update
        .download_and_install(|_chunk, _total| {}, || {})
        .await
    {
        Ok(()) => UpdateStatus::Installing,
        Err(e) => {
            eprintln!("actualizaciones: fallo la instalacion ({})", error_kind(&e));
            UpdateStatus::InstallFailed
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Error, InProgress, UpdateStatus, error_kind};

    /// La UI traduce exactamente estas cadenas (`T.update[...]` en `ui/i18n.js`).
    #[test]
    fn estados_en_snake_case() {
        let got: Vec<String> = [
            UpdateStatus::Checking,
            UpdateStatus::UpToDate,
            UpdateStatus::Installing,
            UpdateStatus::Failed,
            UpdateStatus::InstallFailed,
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
                "\"failed\"",
                "\"install_failed\""
            ]
        );
    }

    /// Cada estado tiene texto en es y en en `ui/i18n.js`.
    #[test]
    fn la_ui_traduce_todos_los_estados() {
        let js = include_str!("../../ui/i18n.js");
        for key in [
            "checking",
            "up_to_date",
            "installing",
            "failed",
            "install_failed",
        ] {
            // Con espacio delante: " failed: " no coincide dentro de "install_failed: ".
            let needle = format!(" {key}: \"");
            assert_eq!(
                js.matches(&needle).count(),
                2,
                "{key} debe estar en es y en"
            );
        }
    }

    #[test]
    fn una_sola_comprobacion_a_la_vez() {
        let first = InProgress::try_begin().expect("la primera entra");
        assert!(InProgress::try_begin().is_none(), "la segunda no entra");
        drop(first);
        let again = InProgress::try_begin().expect("al terminar se libera");
        drop(again);
    }

    #[test]
    fn tipo_de_error_sin_detalles() {
        assert_eq!(error_kind(&Error::Network("x".into())), "red");
        assert_eq!(error_kind(&Error::MissingSignedVersion), "firma");
        assert_eq!(error_kind(&Error::ReleaseNotFound), "manifiesto");
        assert_eq!(error_kind(&Error::EmptyEndpoints), "otro");
    }

    /// Todos los `.rs` bajo `dir`, recursivo.
    fn rust_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        for entry in std::fs::read_dir(dir).expect("lee el directorio") {
            let path = entry.expect("entrada").path();
            if path.is_dir() {
                rust_files(&path, out);
            } else if path.extension().and_then(|e| e.to_str()) == Some("rs") {
                out.push(path);
            }
        }
    }

    /// Guarda de D1: la comprobacion solo se dispara desde el item de bandeja. Recorre todo
    /// `src/` (con subdirectorios) y exige que `check_and_install` y `.updater()` solo
    /// aparezcan en este archivo, salvo una unica llamada en `tray.rs` dentro del brazo
    /// `ID_UPDATE` de `handle_menu`. Si alguien la agrega en `setup`, en un hilo o en un
    /// temporizador, este test falla.
    #[test]
    fn updater_solo_desde_el_item_de_bandeja() {
        let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut files = Vec::new();
        rust_files(&src, &mut files);
        assert!(files.len() > 10, "no encontro los fuentes");
        let mut tray_calls = 0;
        for path in files {
            let name = path
                .strip_prefix(&src)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
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
