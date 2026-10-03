// Evita la ventana de consola en Windows en release. No quitar.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    claude_usage_widgets_lib::run()
}
