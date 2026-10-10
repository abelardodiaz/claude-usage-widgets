//! "Iniciar con Windows": valor `claude-usage-widgets-lite` en
//! HKCU\Software\Microsoft\Windows\CurrentVersion\Run apuntando a la ruta FIJA de instalacion
//! (%LOCALAPPDATA%\claude-usage-widgets-lite\claude-usage-widgets-lite.exe). El registro es la
//! unica verdad: la marca del menu se lee de ahi cada vez que se abre.

use std::ffi::c_void;
use std::path::PathBuf;

use windows::Win32::Foundation::ERROR_SUCCESS;
use windows::Win32::System::Registry::{
    HKEY_CURRENT_USER, REG_SZ, RRF_RT_REG_SZ, RegDeleteKeyValueW, RegGetValueW, RegSetKeyValueW,
};
use windows::core::{PCWSTR, w};

const RUN_KEY: PCWSTR = w!("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
const VALUE: PCWSTR = w!("claude-usage-widgets-lite");
pub const INSTALL_DIR: &str = "claude-usage-widgets-lite";
pub const EXE_NAME: &str = "claude-usage-widgets-lite.exe";

/// Ruta fija del ejecutable instalado.
pub fn installed_exe() -> Option<PathBuf> {
    dirs::data_local_dir().map(|d| d.join(INSTALL_DIR).join(EXE_NAME))
}

/// Hay valor en Run.
pub fn is_enabled() -> bool {
    let mut size = 0u32;
    let rc = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            RUN_KEY,
            VALUE,
            RRF_RT_REG_SZ,
            None,
            None,
            Some(&mut size),
        )
    };
    rc == ERROR_SUCCESS
}

/// Crea o borra el valor. Devuelve el estado real tras el cambio.
pub fn set_enabled(on: bool) -> bool {
    unsafe {
        if on {
            if let Some(exe) = installed_exe() {
                let cmd = format!("\"{}\"", exe.display());
                let data: Vec<u16> = cmd.encode_utf16().chain(std::iter::once(0)).collect();
                let _ = RegSetKeyValueW(
                    HKEY_CURRENT_USER,
                    RUN_KEY,
                    VALUE,
                    REG_SZ.0,
                    Some(data.as_ptr() as *const c_void),
                    (data.len() * 2) as u32,
                );
            }
        } else {
            let _ = RegDeleteKeyValueW(HKEY_CURRENT_USER, RUN_KEY, VALUE);
        }
    }
    is_enabled()
}
