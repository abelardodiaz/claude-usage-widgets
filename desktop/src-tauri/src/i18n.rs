//! Textos de la bandeja (menu nativo, fuera del WebView) en es/en. ASCII a proposito.

pub struct Strings {
    pub app_title: &'static str,
    pub tray_toggle: &'static str,
    pub tray_refresh: &'static str,
    pub tray_autostart: &'static str,
    pub tray_update: &'static str,
    pub tray_quit: &'static str,
}

pub static ES: Strings = Strings {
    app_title: "Uso de Claude",
    tray_toggle: "Mostrar / ocultar",
    tray_refresh: "Actualizar",
    tray_autostart: "Iniciar con Windows",
    tray_update: "Buscar actualizaciones",
    tray_quit: "Salir",
};

pub static EN: Strings = Strings {
    app_title: "Claude usage",
    tray_toggle: "Show / hide",
    tray_refresh: "Refresh",
    tray_autostart: "Start with Windows",
    tray_update: "Check for updates",
    tray_quit: "Quit",
};

/// "es" -> espanol; cualquier otro -> ingles.
pub fn strings(lang: &str) -> &'static Strings {
    if lang == "es" { &ES } else { &EN }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn espanol_e_ingles() {
        assert_eq!(strings("es").tray_quit, "Salir");
        assert_eq!(strings("en").tray_quit, "Quit");
        assert_eq!(strings("pt").tray_quit, "Quit");
    }

    #[test]
    fn todos_los_textos_son_ascii() {
        for s in [&ES, &EN] {
            for text in [
                s.app_title,
                s.tray_toggle,
                s.tray_refresh,
                s.tray_autostart,
                s.tray_update,
                s.tray_quit,
            ] {
                assert!(text.is_ascii() && !text.is_empty(), "{text}");
            }
        }
    }
}
