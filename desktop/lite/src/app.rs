//! Ventana Win32 + GDI, bandeja e hilo de consultas.
//!
//! - Ventana emergente sin bordes, fuera de la barra de tareas (WS_EX_TOOLWINDOW), siempre encima
//!   si asi se eligio, esquinas redondeadas de Windows 11 (DWM), se arrastra desde cualquier punto
//!   (HTCAPTION) salvo la X de cerrar, que aparece al pasar el raton y oculta a la bandeja.
//! - DPI por monitor v2: todo se dibuja en px logicos * dpi / 96; WM_DPICHANGED rehace fuentes.
//! - Pintado con doble bufer (bitmap en memoria + BitBlt): sin parpadeo.
//! - Un hilo aparte llama a `UsageService::get` (bloquea en red) y avisa a la ventana con
//!   WM_USAGE; la ventana solo lee la ultima `UsageView`. El token nunca pasa por aqui.
//! - Preferencias (intervalo, siempre encima, posicion, avisos ya mostrados) en `settings.json`
//!   de la carpeta de datos; "Iniciar con Windows" vive en el registro (`autostart`).

use std::cell::RefCell;
use std::ffi::c_void;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use jiff::Timestamp;
use jiff::tz::TimeZone;
use windows::Win32::Foundation::{
    COLORREF, ERROR_ALREADY_EXISTS, GetLastError, HWND, LPARAM, LRESULT, POINT, RECT, SIZE, WPARAM,
};
use windows::Win32::Graphics::Dwm::{
    DWM_WINDOW_CORNER_PREFERENCE, DWMWA_BORDER_COLOR, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND,
    DwmSetWindowAttribute,
};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, BitBlt, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, CreateCompatibleBitmap,
    CreateCompatibleDC, CreateFontW, CreatePen, CreateSolidBrush, DEFAULT_CHARSET, DT_END_ELLIPSIS,
    DT_LEFT, DT_NOPREFIX, DT_RIGHT, DT_SINGLELINE, DT_VCENTER, DeleteDC, DeleteObject, DrawTextW,
    EndPaint, EnumDisplayMonitors, FF_DONTCARE, FW_BOLD, FW_NORMAL, FillRect, GetMonitorInfoW,
    GetStockObject, GetTextExtentPoint32W, HDC, HFONT, HGDIOBJ, HMONITOR, InvalidateRect, LineTo,
    MONITOR_DEFAULTTONEAREST, MONITOR_DEFAULTTOPRIMARY, MONITORINFO, MonitorFromPoint, MoveToEx,
    NULL_PEN, OUT_DEFAULT_PRECIS, PAINTSTRUCT, PS_SOLID, RoundRect, SRCCOPY, SelectObject,
    SetBkMode, SetTextAlign, SetTextCharacterExtra, SetTextColor, TA_BASELINE, TA_LEFT, TA_RIGHT,
    TRANSPARENT, TextOutW,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::CreateMutexW;
use windows::Win32::UI::HiDpi::{
    DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, GetDpiForMonitor, GetDpiForWindow,
    GetSystemMetricsForDpi, MDT_EFFECTIVE_DPI, SetProcessDpiAwarenessContext,
};
use windows::Win32::UI::Shell::{
    NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_TIP, NIIF_LARGE_ICON, NIIF_USER, NIM_ADD, NIM_DELETE,
    NIM_MODIFY, NOTIFYICONDATAW, Shell_NotifyIconW,
};
use windows::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CS_HREDRAW, CS_VREDRAW, CheckMenuRadioItem, CreateIconFromResourceEx,
    CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu, DestroyWindow, DispatchMessageW,
    FindWindowW, GetClientRect, GetCursorPos, GetMessageW, GetWindowRect, HICON, HMENU, HTCAPTION,
    HTCLIENT, HWND_NOTOPMOST, HWND_TOP, HWND_TOPMOST, IDC_ARROW, IsWindowVisible, KillTimer,
    LR_DEFAULTCOLOR, LoadCursorW, MF_BYCOMMAND, MF_CHECKED, MF_POPUP, MF_SEPARATOR, MF_STRING, MSG,
    PostMessageW, PostQuitMessage, RegisterClassExW, RegisterWindowMessageW, SM_CXICON,
    SM_CXSMICON, SW_HIDE, SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, SWP_NOZORDER,
    SetForegroundWindow, SetTimer, SetWindowPos, ShowWindow, TPM_BOTTOMALIGN, TPM_RIGHTBUTTON,
    TrackPopupMenu, TranslateMessage, WM_APP, WM_COMMAND, WM_CONTEXTMENU, WM_DESTROY,
    WM_DPICHANGED, WM_ERASEBKGND, WM_EXITSIZEMOVE, WM_LBUTTONUP, WM_MOUSEMOVE, WM_NCHITTEST,
    WM_NCLBUTTONDBLCLK, WM_NCMOUSEMOVE, WM_NCRBUTTONUP, WM_NULL, WM_PAINT, WM_RBUTTONUP, WM_TIMER,
    WNDCLASSEXW, WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP,
};
use windows::core::{BOOL, PCWSTR, w};

use crate::autostart;
use crate::credentials;
use crate::format;
use crate::i18n;
use crate::notify;
use crate::placement;
use crate::service::UsageService;
use crate::settings::{self, Settings};
use crate::source_claude_code::ClaudeCodeSource;
use crate::store::SampleStore;
use crate::timez;
use crate::view::UsageView;

const CLASS_NAME: PCWSTR = w!("ClaudeUsageWidgetsLite");
const MUTEX_NAME: PCWSTR = w!("Local\\io.github.abelardodiaz.claude-usage-widgets-lite");
/// WM_POWERBROADCAST: reanudacion tras suspension (automatica o por el usuario).
const WM_POWERBROADCAST: u32 = 0x0218;
const PBT_APMRESUMESUSPEND: u32 = 0x0007;
const PBT_APMRESUMEAUTOMATIC: u32 = 0x0012;
/// Carpeta de datos propia (muestras del servicio y preferencias), separada de la app normal.
const DATA_DIR: &str = "io.github.abelardodiaz.claude-usage-widgets-lite";

/// Hilo de consultas -> ventana: hay una `UsageView` nueva en `VIEW`.
const WM_USAGE: u32 = WM_APP + 1;
/// Mensajes del icono de la bandeja.
const WM_TRAY: u32 = WM_APP + 2;
/// Otra instancia pide que se muestre esta ventana.
const WM_SHOW_REQ: u32 = WM_APP + 3;
const TRAY_ID: u32 = 1;
const ID_TOGGLE: usize = 1;
const ID_REFRESH: usize = 2;
const ID_QUIT: usize = 3;
const ID_TOPMOST: usize = 4;
const ID_AUTOSTART: usize = 5;
/// "Cada N min": ID_INTERVAL + N.
const ID_INTERVAL: usize = 100;
/// Temporizador que vigila si el raton salio de la ventana (para ocultar la X).
const TIMER_HOVER: usize = 1;

// Medidas en px logicos (a 96 dpi).
const WIDTH: i32 = 285;
const MARGIN_RIGHT: i32 = 24;
const TOP: i32 = 40;
const PAD_X: i32 = 14;
const PAD_TOP: i32 = 11;
const PAD_BOTTOM: i32 = 9;
const TEXT_LINE: i32 = 17;
const BAR_GAP: i32 = 5;
const BAR_H: i32 = 7;
const ROW_GAP: i32 = 10;
const FOOT_GAP: i32 = 8;
const FOOT_LINE: i32 = 15;
/// X de cerrar: cuadro de CLOSE_SIZE en la esquina, a CLOSE_INSET del borde derecho y de arriba.
const CLOSE_SIZE: i32 = 13;
const CLOSE_INSET: i32 = 3;

// Paleta de desktop/ui/style.css (0xRRGGBB).
const BG: u32 = 0x16181d;
const TEXT: u32 = 0xe7e9ee;
const MUTED: u32 = 0x8b919c;
const FAINT: u32 = 0x838993;
const TRACK: u32 = 0x262a32;
const LINE: u32 = 0x2a2e37;
const AMBER: u32 = 0xe8b03f;
/// Marca de ritmo parejo: --muted con opacidad .7 sobre la pista (como `.bar .mark`).
const MARK: u32 = 0x6d727c;
/// Fondo de la X cuando el raton esta encima.
const CLOSE_HOT: u32 = 0x3a3f4a;

static VIEW: Mutex<Option<UsageView>> = Mutex::new(None);
static WORKER: OnceLock<mpsc::Sender<Cmd>> = OnceLock::new();
/// Minutos entre consultas; lo lee el hilo de consultas en cada espera.
static INTERVAL_MIN: AtomicU32 = AtomicU32::new(settings::DEFAULT_INTERVAL);
/// `--probar-aviso`: con el primer dato se muestra UN aviso de prueba ("Semana al 50%" con el
/// reinicio real), sin tocar los avisos guardados. Sirve para comprobar que Windows los ensena.
static PROBE: AtomicBool = AtomicBool::new(false);

/// Ordenes al hilo de consultas.
enum Cmd {
    /// "Actualizar": consultar ya (el servicio sigue aplicando el backoff).
    Force,
    /// Cambio el intervalo: recalcular la espera sin consultar.
    Reschedule,
}

struct Fonts {
    label: HFONT,
    pct: HFONT,
    small: HFONT,
    foot: HFONT,
}

struct Ui {
    lang: &'static str,
    tz: TimeZone,
    dpi: u32,
    fonts: Fonts,
    icon: HICON,
    balloon_icon: HICON,
    taskbar_created: u32,
    settings: Settings,
    settings_path: PathBuf,
    /// El raton esta sobre la ventana: se dibuja la X.
    hover: bool,
    /// El raton esta sobre la X.
    hot: bool,
}

impl Ui {
    fn save(&self) {
        if let Err(e) = settings::save(&self.settings_path, &self.settings) {
            // Solo el tipo de error de E/S: sin rutas.
            eprintln!("no se pudieron guardar las preferencias: {}", e.kind());
        }
    }
}

thread_local! {
    static UI: RefCell<Option<Ui>> = const { RefCell::new(None) };
}

/// COLORREF de GDI (0x00BBGGRR) a partir de 0xRRGGBB.
fn cref(rgb: u32) -> COLORREF {
    COLORREF(((rgb & 0xff) << 16) | (rgb & 0xff00) | ((rgb >> 16) & 0xff))
}

/// px logicos -> px fisicos para un dpi.
fn px(v: i32, dpi: u32) -> i32 {
    ((v as i64 * dpi as i64 + 48) / 96) as i32
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

fn wide_z(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Coordenadas con signo de un LPARAM de raton.
fn point_of(lparam: LPARAM) -> (i32, i32) {
    let v = lparam.0 as usize;
    (
        (v & 0xffff) as u16 as i16 as i32,
        ((v >> 16) & 0xffff) as u16 as i16 as i32,
    )
}

fn window_height(dpi: u32) -> i32 {
    let row = TEXT_LINE + BAR_GAP + BAR_H;
    px(
        PAD_TOP + row + ROW_GAP + row + FOOT_GAP + FOOT_LINE + PAD_BOTTOM,
        dpi,
    )
}

/// Cuadro de la X en coordenadas de cliente, para un ancho de ventana `width`.
fn close_rect(width: i32, dpi: u32) -> RECT {
    let right = width - px(CLOSE_INSET, dpi);
    let top = px(CLOSE_INSET, dpi) - px(1, dpi);
    RECT {
        left: right - px(CLOSE_SIZE, dpi),
        top,
        right,
        bottom: top + px(CLOSE_SIZE, dpi),
    }
}

fn in_rect(r: &RECT, x: i32, y: i32) -> bool {
    x >= r.left && x < r.right && y >= r.top && y < r.bottom
}

fn make_font(size_tenths: i32, bold: bool, dpi: u32) -> HFONT {
    // Alto negativo = tamano de la letra en px (igual que font-size de CSS).
    let height = -((size_tenths as i64 * dpi as i64 + 480) / 960) as i32;
    let weight = if bold { FW_BOLD.0 } else { FW_NORMAL.0 } as i32;
    unsafe {
        CreateFontW(
            height,
            0,
            0,
            0,
            weight,
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            FF_DONTCARE.0 as u32,
            w!("Segoe UI"),
        )
    }
}

fn make_fonts(dpi: u32) -> Fonts {
    Fonts {
        label: make_font(120, true, dpi),
        pct: make_font(140, true, dpi),
        small: make_font(115, false, dpi),
        foot: make_font(110, false, dpi),
    }
}

fn free_fonts(f: &Fonts) {
    unsafe {
        for font in [f.label, f.pct, f.small, f.foot] {
            let _ = DeleteObject(HGDIOBJ(font.0));
        }
    }
}

/// Icono desde el icon.ico de la app normal (entradas PNG): la mas chica que cubra `size`, o la
/// mas grande si ninguna alcanza.
fn load_icon(size: i32) -> HICON {
    static ICO: &[u8] = include_bytes!("../../src-tauri/icons/icon.ico");
    let count = u16::from_le_bytes([ICO[4], ICO[5]]) as usize;
    let mut best: Option<(i32, usize, usize)> = None;
    for i in 0..count {
        let e = 6 + 16 * i;
        let Some(entry) = ICO.get(e..e + 16) else {
            break;
        };
        let w = if entry[0] == 0 { 256 } else { entry[0] as i32 };
        let len = u32::from_le_bytes([entry[8], entry[9], entry[10], entry[11]]) as usize;
        let off = u32::from_le_bytes([entry[12], entry[13], entry[14], entry[15]]) as usize;
        let better = match best {
            None => true,
            Some((bw, _, _)) => (w >= size && (bw < size || w < bw)) || (bw < size && w > bw),
        };
        if better {
            best = Some((w, off, len));
        }
    }
    let Some((_, off, len)) = best else {
        return HICON::default();
    };
    let Some(bits) = ICO.get(off..off + len) else {
        return HICON::default();
    };
    unsafe {
        CreateIconFromResourceEx(bits, true, 0x0003_0000, size, size, LR_DEFAULTCOLOR)
            .unwrap_or_default()
    }
}

fn tray_data(hwnd: HWND, icon: HICON, tip: &str) -> NOTIFYICONDATAW {
    let mut nid = NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ID,
        uFlags: NIF_ICON | NIF_MESSAGE | NIF_TIP,
        uCallbackMessage: WM_TRAY,
        hIcon: icon,
        ..Default::default()
    };
    for (dst, src) in nid.szTip.iter_mut().zip(tip.encode_utf16().take(127)) {
        *dst = src;
    }
    nid
}

fn add_tray(hwnd: HWND, lang: &str, icon: HICON) {
    let tip = format!("{} lite", i18n::strings(lang).app_title);
    let nid = tray_data(hwnd, icon, &tip);
    unsafe {
        let _ = Shell_NotifyIconW(NIM_ADD, &nid);
    }
}

/// Aviso nativo: globo del icono de la bandeja, que Windows 11 muestra como notificacion.
fn balloon(hwnd: HWND, icon: HICON, title: &str, body: &str) {
    let mut nid = NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ID,
        uFlags: NIF_INFO,
        dwInfoFlags: NIIF_USER | NIIF_LARGE_ICON,
        hBalloonIcon: icon,
        ..Default::default()
    };
    for (dst, src) in nid
        .szInfoTitle
        .iter_mut()
        .zip(title.encode_utf16().take(63))
    {
        *dst = src;
    }
    // Un cuerpo vacio quitaria el globo en vez de mostrarlo.
    let body = if body.is_empty() { " " } else { body };
    for (dst, src) in nid.szInfo.iter_mut().zip(body.encode_utf16().take(255)) {
        *dst = src;
    }
    unsafe {
        let _ = Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}

fn toggle(hwnd: HWND, topmost: bool) {
    unsafe {
        if IsWindowVisible(hwnd).as_bool() {
            let _ = ShowWindow(hwnd, SW_HIDE);
        } else {
            show(hwnd, topmost);
        }
    }
}

fn apply_topmost(hwnd: HWND, topmost: bool) {
    let after = if topmost {
        HWND_TOPMOST
    } else {
        HWND_NOTOPMOST
    };
    unsafe {
        let _ = SetWindowPos(
            hwnd,
            Some(after),
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );
    }
}

fn show(hwnd: HWND, topmost: bool) {
    unsafe {
        let _ = ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    }
    apply_topmost(hwnd, topmost);
    if !topmost {
        // Sin "Siempre encima" la ventana puede quedar detras de otras: al pedir mostrarla
        // (bandeja o segunda instancia) se trae al frente.
        unsafe {
            let _ = SetWindowPos(hwnd, Some(HWND_TOP), 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
            let _ = SetForegroundWindow(hwnd);
        }
    }
}

fn send(cmd: Cmd) {
    if let Some(tx) = WORKER.get() {
        let _ = tx.send(cmd);
    }
}

fn show_menu(hwnd: HWND, lang: &str, interval: u32, topmost: bool) {
    let s = i18n::strings(lang);
    let x = format::texts(lang);
    let toggle_text = wide_z(s.tray_toggle);
    let refresh_text = wide_z(s.tray_refresh);
    let interval_text = wide_z(x.menu_interval);
    let topmost_text = wide_z(x.menu_topmost);
    let autostart_text = wide_z(s.tray_autostart);
    let quit_text = wide_z(s.tray_quit);
    let every: Vec<(usize, Vec<u16>)> = settings::INTERVALS
        .iter()
        .map(|m| {
            (
                ID_INTERVAL + *m as usize,
                wide_z(&format::every_text(*m, x)),
            )
        })
        .collect();
    let checked = |on: bool| {
        if on {
            MF_STRING | MF_CHECKED
        } else {
            MF_STRING
        }
    };
    unsafe {
        let Ok(menu) = CreatePopupMenu() else {
            return;
        };
        let _ = AppendMenuW(menu, MF_STRING, ID_TOGGLE, PCWSTR(toggle_text.as_ptr()));
        let _ = AppendMenuW(menu, MF_STRING, ID_REFRESH, PCWSTR(refresh_text.as_ptr()));
        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
        if let Ok(sub) = CreatePopupMenu() {
            for (id, text) in &every {
                let _ = AppendMenuW(sub, MF_STRING, *id, PCWSTR(text.as_ptr()));
            }
            if let (Some((first, _)), Some((last, _))) = (every.first(), every.last()) {
                let _ = CheckMenuRadioItem(
                    sub,
                    *first as u32,
                    *last as u32,
                    (ID_INTERVAL + interval as usize) as u32,
                    MF_BYCOMMAND.0,
                );
            }
            // El submenu pasa a ser del menu: DestroyMenu(menu) lo libera tambien.
            let _ = AppendMenuW(
                menu,
                MF_STRING | MF_POPUP,
                sub.0 as usize,
                PCWSTR(interval_text.as_ptr()),
            );
        }
        let _ = AppendMenuW(
            menu,
            checked(topmost),
            ID_TOPMOST,
            PCWSTR(topmost_text.as_ptr()),
        );
        // La marca sale del registro cada vez que se abre el menu.
        let _ = AppendMenuW(
            menu,
            checked(autostart::is_enabled()),
            ID_AUTOSTART,
            PCWSTR(autostart_text.as_ptr()),
        );
        let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
        let _ = AppendMenuW(menu, MF_STRING, ID_QUIT, PCWSTR(quit_text.as_ptr()));
        popup(hwnd, menu);
    }
}

unsafe fn popup(hwnd: HWND, menu: HMENU) {
    unsafe {
        let mut pt = POINT::default();
        let _ = GetCursorPos(&mut pt);
        // Sin esto el menu no se cierra al hacer clic fuera (KB135788).
        let _ = SetForegroundWindow(hwnd);
        let _ = TrackPopupMenu(
            menu,
            TPM_RIGHTBUTTON | TPM_BOTTOMALIGN,
            pt.x,
            pt.y,
            None,
            hwnd,
            None,
        );
        let _ = PostMessageW(Some(hwnd), WM_NULL, WPARAM(0), LPARAM(0));
        let _ = DestroyMenu(menu);
    }
}

fn fill(hdc: HDC, rect: &RECT, rgb: u32) {
    unsafe {
        let brush = CreateSolidBrush(cref(rgb));
        FillRect(hdc, rect, brush);
        let _ = DeleteObject(HGDIOBJ(brush.0));
    }
}

/// Rectangulo redondeado relleno, sin borde.
fn round_fill(hdc: HDC, r: &RECT, radius: i32, rgb: u32) {
    unsafe {
        let old_pen = SelectObject(hdc, GetStockObject(NULL_PEN));
        let brush = CreateSolidBrush(cref(rgb));
        let old_brush = SelectObject(hdc, HGDIOBJ(brush.0));
        // Con lapiz nulo RoundRect no pinta la ultima fila/columna: +1 para el tamano exacto.
        let _ = RoundRect(
            hdc,
            r.left,
            r.top,
            r.right + 1,
            r.bottom + 1,
            radius,
            radius,
        );
        SelectObject(hdc, old_brush);
        SelectObject(hdc, old_pen);
        let _ = DeleteObject(HGDIOBJ(brush.0));
    }
}

/// Barra redondeada (pista + relleno). `frac` en [0, 1].
fn bar(hdc: HDC, x: i32, y: i32, w: i32, h: i32, frac: f64, rgb: u32) {
    round_fill(
        hdc,
        &RECT {
            left: x,
            top: y,
            right: x + w,
            bottom: y + h,
        },
        h,
        TRACK,
    );
    if frac > 0.0 {
        let fw = ((w as f64 * frac).round() as i32).max(h);
        round_fill(
            hdc,
            &RECT {
                left: x,
                top: y,
                right: x + fw,
                bottom: y + h,
            },
            h,
            rgb,
        );
    }
}

fn text_width(hdc: HDC, font: HFONT, s: &[u16]) -> i32 {
    unsafe {
        let old = SelectObject(hdc, HGDIOBJ(font.0));
        let mut size = SIZE::default();
        let _ = GetTextExtentPoint32W(hdc, s, &mut size);
        SelectObject(hdc, old);
        size.cx
    }
}

/// Una fila: "ETIQUETA  NN%" a la izquierda, "\u{21bb} 04:50" a la derecha, barra debajo y, si
/// hay `mark` (fraccion de la semana transcurrida), la marca de ritmo parejo sobre la barra.
#[allow(clippy::too_many_arguments)]
fn row(
    hdc: HDC,
    ui: &Ui,
    top: i32,
    width: i32,
    label: &str,
    pct: Option<(f64, u32)>,
    reset: &str,
    mark: Option<f64>,
) {
    let d = ui.dpi;
    let x0 = px(PAD_X, d);
    let x1 = width - px(PAD_X, d);
    let baseline = top + px(13, d);
    unsafe {
        SetTextAlign(hdc, TA_LEFT | TA_BASELINE);
        SetTextColor(hdc, cref(TEXT));
        let label_w = wide(label);
        SelectObject(hdc, HGDIOBJ(ui.fonts.label.0));
        // letter-spacing .05em de la UI normal.
        SetTextCharacterExtra(hdc, px(1, d).min(((12 * d) as i32 + 960) / 1920).max(0));
        let _ = TextOutW(hdc, x0, baseline, &label_w);
        let lw = text_width(hdc, ui.fonts.label, &label_w);
        SetTextCharacterExtra(hdc, 0);
        let pct_text = wide(&pct.map_or("--".to_string(), |(p, _)| format::percent_text(p)));
        SelectObject(hdc, HGDIOBJ(ui.fonts.pct.0));
        let _ = TextOutW(hdc, x0 + lw + px(6, d), baseline, &pct_text);
        if !reset.is_empty() {
            SetTextAlign(hdc, TA_RIGHT | TA_BASELINE);
            SetTextColor(hdc, cref(MUTED));
            SelectObject(hdc, HGDIOBJ(ui.fonts.small.0));
            let _ = TextOutW(hdc, x1, baseline, &wide(reset));
        }
    }
    let (frac, rgb) = pct.map_or((0.0, TRACK), |(p, c)| (format::bar_fraction(p), c));
    let bar_top = top + px(TEXT_LINE + BAR_GAP, d);
    let bar_h = px(BAR_H, d);
    bar(hdc, x0, bar_top, x1 - x0, bar_h, frac, rgb);
    if let Some(m) = mark {
        // Como `.bar .mark`: 2 px de ancho, 1 px por encima y por debajo de la barra.
        let mw = px(2, d).max(2);
        let cx = x0 + ((x1 - x0) as f64 * m.clamp(0.0, 1.0)).round() as i32;
        let left = (cx - mw / 2).clamp(x0, x1 - mw);
        fill(
            hdc,
            &RECT {
                left,
                top: bar_top - px(1, d),
                right: left + mw,
                bottom: bar_top + bar_h + px(1, d),
            },
            MARK,
        );
    }
}

/// X de cerrar (solo con el raton encima de la ventana).
fn close_button(hdc: HDC, ui: &Ui, width: i32) {
    let d = ui.dpi;
    let r = close_rect(width, d);
    if ui.hot {
        round_fill(hdc, &r, px(4, d), CLOSE_HOT);
    }
    let inset = px(4, d);
    let pen_w = px(1, d).max(1) + i32::from(d >= 144);
    unsafe {
        let pen = CreatePen(PS_SOLID, pen_w, cref(if ui.hot { TEXT } else { MUTED }));
        let old = SelectObject(hdc, HGDIOBJ(pen.0));
        let (l, t) = (r.left + inset, r.top + inset);
        let (rr, b) = (r.right - inset, r.bottom - inset);
        let _ = MoveToEx(hdc, l, t, None);
        let _ = LineTo(hdc, rr, b);
        let _ = MoveToEx(hdc, rr - 1, t, None);
        let _ = LineTo(hdc, l - 1, b);
        SelectObject(hdc, old);
        let _ = DeleteObject(HGDIOBJ(pen.0));
    }
}

fn paint(hwnd: HWND, ui: &Ui) {
    unsafe {
        let mut ps = PAINTSTRUCT::default();
        let hdc = BeginPaint(hwnd, &mut ps);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        let (w, h) = (rc.right - rc.left, rc.bottom - rc.top);
        let mem = CreateCompatibleDC(Some(hdc));
        let bmp = CreateCompatibleBitmap(hdc, w, h);
        let old_bmp = SelectObject(mem, HGDIOBJ(bmp.0));
        let old_font = SelectObject(mem, HGDIOBJ(ui.fonts.foot.0));
        SetBkMode(mem, TRANSPARENT);
        fill(mem, &rc, BG);

        let view = VIEW.lock().unwrap_or_else(|p| p.into_inner()).clone();
        let x = format::texts(ui.lang);
        let now = Timestamp::now();
        let d = ui.dpi;
        let usage = view.as_ref().and_then(|v| v.usage.as_ref());
        let (s_pct, s_reset, w_pct, w_reset, mark) = match (usage, view.as_ref()) {
            (Some(u), Some(v)) => (
                Some((
                    u.session.percent,
                    v.session_color.map_or(TRACK, format::rgb),
                )),
                format::reset_text(u.session.resets_at, now, &ui.tz, x),
                Some((u.weekly.percent, v.weekly_color.map_or(TRACK, format::rgb))),
                format::reset_text(u.weekly.resets_at, now, &ui.tz, x),
                v.pace_mark,
            ),
            _ => (None, String::new(), None, String::new(), None),
        };
        let row_h = px(TEXT_LINE + BAR_GAP + BAR_H, d);
        let top1 = px(PAD_TOP, d);
        let top2 = top1 + row_h + px(ROW_GAP, d);
        row(mem, ui, top1, w, x.session, s_pct, &s_reset, None);
        row(mem, ui, top2, w, x.week, w_pct, &w_reset, mark);

        let (foot, is_err) = format::footer(view.as_ref(), now, &ui.tz, x);
        if !foot.is_empty() {
            let foot_top = top2 + row_h + px(FOOT_GAP, d);
            let mut fr = RECT {
                left: px(PAD_X, d),
                top: foot_top,
                right: w - px(PAD_X, d),
                bottom: foot_top + px(FOOT_LINE, d),
            };
            SelectObject(mem, HGDIOBJ(ui.fonts.foot.0));
            SetTextAlign(mem, TA_LEFT);
            SetTextColor(mem, cref(if is_err { AMBER } else { FAINT }));
            // Error: a la izquierda, con "..." si no cabe. Normal: hora de la ultima consulta a la derecha.
            let align = if is_err { DT_LEFT } else { DT_RIGHT };
            let mut text = wide(&foot);
            DrawTextW(
                mem,
                &mut text,
                &mut fr,
                align | DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX,
            );
        }
        if ui.hover {
            close_button(mem, ui, w);
        }

        let _ = BitBlt(hdc, 0, 0, w, h, Some(mem), 0, 0, SRCCOPY);
        SelectObject(mem, old_font);
        SelectObject(mem, old_bmp);
        let _ = DeleteObject(HGDIOBJ(bmp.0));
        let _ = DeleteDC(mem);
        let _ = EndPaint(hwnd, &ps);
    }
}

fn loword(v: usize) -> u32 {
    (v & 0xffff) as u32
}

/// Acceso corto al estado de la ventana. Nunca se llama a Win32 que despache mensajes con el
/// prestamo activo (TrackPopupMenu, SetWindowPos, DestroyWindow reentran en `wndproc`).
fn with_ui<R>(f: impl FnOnce(&mut Ui) -> R) -> Option<R> {
    UI.with(|cell| {
        cell.try_borrow_mut()
            .ok()
            .and_then(|mut slot| slot.as_mut().map(f))
    })
}

fn redraw(hwnd: HWND) {
    unsafe {
        let _ = InvalidateRect(Some(hwnd), None, false);
    }
}

/// El raton se movio sobre la ventana (`on_close`: sobre la X). Arranca el temporizador que
/// detecta cuando sale.
fn mouse_over(hwnd: HWND, on_close: bool) {
    let changed = with_ui(|ui| {
        let changed = !ui.hover || ui.hot != on_close;
        ui.hover = true;
        ui.hot = on_close;
        changed
    })
    .unwrap_or(false);
    unsafe {
        SetTimer(Some(hwnd), TIMER_HOVER, 100, None);
    }
    if changed {
        redraw(hwnd);
    }
}

/// Temporizador: si el raton ya no esta sobre la ventana, se quita la X.
fn hover_check(hwnd: HWND) {
    let mut pt = POINT::default();
    let mut wr = RECT::default();
    let inside = unsafe {
        GetCursorPos(&mut pt).is_ok()
            && GetWindowRect(hwnd, &mut wr).is_ok()
            && IsWindowVisible(hwnd).as_bool()
            && in_rect(&wr, pt.x, pt.y)
    };
    if inside {
        return;
    }
    unsafe {
        let _ = KillTimer(Some(hwnd), TIMER_HOVER);
    }
    let changed = with_ui(|ui| {
        let changed = ui.hover || ui.hot;
        ui.hover = false;
        ui.hot = false;
        changed
    })
    .unwrap_or(false);
    if changed {
        redraw(hwnd);
    }
}

/// Dato nuevo: si la semana cruzo un umbral por primera vez, aviso.
fn check_week_notice(hwnd: HWND) {
    let view = VIEW.lock().unwrap_or_else(|p| p.into_inner()).clone();
    let Some(v) = view else {
        return;
    };
    // Solo con dato fresco: un dato viejo con error no cruza nada nuevo.
    if v.error.is_some() {
        return;
    }
    let Some(u) = v.usage else {
        return;
    };
    let week = notify::week_key(u.weekly.resets_at);
    let notice = with_ui(|ui| {
        let (state, threshold) =
            notify::evaluate(&ui.settings.notified, week.as_deref(), u.weekly.percent);
        if state != ui.settings.notified {
            ui.settings.notified = state;
            ui.save();
        }
        let threshold = if PROBE.swap(false, Ordering::Relaxed) {
            Some(notify::THRESHOLDS[0])
        } else {
            threshold
        };
        threshold.map(|t| {
            let x = format::texts(ui.lang);
            let (title, body) =
                format::week_notice(t, u.weekly.resets_at, Timestamp::now(), &ui.tz, x);
            (ui.balloon_icon, title, body)
        })
    })
    .flatten();
    if let Some((icon, title, body)) = notice {
        balloon(hwnd, icon, &title, &body);
    }
}

extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    let Some((lang, icon, taskbar_created, interval, topmost, dpi)) = with_ui(|ui| {
        (
            ui.lang,
            ui.icon,
            ui.taskbar_created,
            ui.settings.interval_min,
            ui.settings.topmost,
            ui.dpi,
        )
    }) else {
        return unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) };
    };
    match msg {
        WM_NCHITTEST => {
            // Toda la ventana arrastra (HTCAPTION) salvo la X, que es "cliente" para recibir el clic.
            let (sx, sy) = point_of(lparam);
            let mut wr = RECT::default();
            let on_close = unsafe { GetWindowRect(hwnd, &mut wr).is_ok() }
                && in_rect(
                    &close_rect(wr.right - wr.left, dpi),
                    sx - wr.left,
                    sy - wr.top,
                );
            LRESULT(if on_close { HTCLIENT } else { HTCAPTION } as isize)
        }
        // Doble clic en la "barra de titulo" no maximiza ni hace nada.
        WM_NCLBUTTONDBLCLK => LRESULT(0),
        WM_NCRBUTTONUP | WM_RBUTTONUP => {
            show_menu(hwnd, lang, interval, topmost);
            LRESULT(0)
        }
        WM_NCMOUSEMOVE => {
            mouse_over(hwnd, false);
            unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
        }
        WM_MOUSEMOVE => {
            // Solo llega sobre la X (lo demas es HTCAPTION).
            mouse_over(hwnd, true);
            LRESULT(0)
        }
        WM_LBUTTONUP => {
            let (cx, cy) = point_of(lparam);
            let mut rc = RECT::default();
            unsafe {
                let _ = GetClientRect(hwnd, &mut rc);
            }
            if in_rect(&close_rect(rc.right - rc.left, dpi), cx, cy) {
                // Cerrar = ocultar a la bandeja; se sale desde el menu.
                unsafe {
                    let _ = ShowWindow(hwnd, SW_HIDE);
                }
                hover_check(hwnd);
            }
            LRESULT(0)
        }
        WM_TIMER if wparam.0 == TIMER_HOVER => {
            hover_check(hwnd);
            LRESULT(0)
        }
        WM_EXITSIZEMOVE => {
            // Termino un arrastre: se guarda la posicion.
            let mut wr = RECT::default();
            if unsafe { GetWindowRect(hwnd, &mut wr).is_ok() } {
                with_ui(|ui| {
                    let pos = Some([wr.left, wr.top]);
                    if ui.settings.pos != pos {
                        ui.settings.pos = pos;
                        ui.save();
                    }
                });
            }
            LRESULT(0)
        }
        WM_ERASEBKGND => LRESULT(1),
        WM_PAINT => {
            // Se pinta con el prestamo de `UI` activo. BeginPaint puede enviar WM_ERASEBKGND de
            // forma sincrona, pero esa rama no toca `UI`; el resto (GDI, BitBlt) no despacha
            // mensajes. Si algun dia otro mensaje reentrante necesitara `UI`, `with_ui` daria
            // None en vez de entrar en panico.
            if with_ui(|ui| paint(hwnd, ui)).is_none() {
                return unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) };
            }
            LRESULT(0)
        }
        WM_USAGE => {
            redraw(hwnd);
            check_week_notice(hwnd);
            LRESULT(0)
        }
        WM_SHOW_REQ => {
            show(hwnd, topmost);
            LRESULT(0)
        }
        WM_TRAY => {
            match loword(lparam.0 as usize) {
                WM_LBUTTONUP => toggle(hwnd, topmost),
                WM_RBUTTONUP | WM_CONTEXTMENU => show_menu(hwnd, lang, interval, topmost),
                _ => {}
            }
            LRESULT(0)
        }
        WM_COMMAND => {
            match loword(wparam.0) as usize {
                ID_TOGGLE => toggle(hwnd, topmost),
                ID_REFRESH => send(Cmd::Force),
                ID_TOPMOST => {
                    let on = !topmost;
                    with_ui(|ui| {
                        ui.settings.topmost = on;
                        ui.save();
                    });
                    apply_topmost(hwnd, on);
                }
                ID_AUTOSTART => {
                    autostart::set_enabled(!autostart::is_enabled());
                }
                id if id > ID_INTERVAL
                    && settings::INTERVALS.contains(&((id - ID_INTERVAL) as u32)) =>
                {
                    let minutes = (id - ID_INTERVAL) as u32;
                    with_ui(|ui| {
                        ui.settings.interval_min = minutes;
                        ui.save();
                    });
                    INTERVAL_MIN.store(minutes, Ordering::Relaxed);
                    send(Cmd::Reschedule);
                }
                ID_QUIT => unsafe {
                    let _ = DestroyWindow(hwnd);
                },
                _ => {}
            }
            LRESULT(0)
        }
        WM_DPICHANGED => {
            let dpi = ((wparam.0 >> 16) & 0xffff) as u32;
            with_ui(|ui| {
                free_fonts(&ui.fonts);
                ui.dpi = dpi;
                ui.fonts = make_fonts(dpi);
            });
            // Se respeta la esquina sugerida por Windows; el tamano sale de nuestras medidas.
            let suggested = unsafe { *(lparam.0 as *const RECT) };
            unsafe {
                let _ = SetWindowPos(
                    hwnd,
                    None,
                    suggested.left,
                    suggested.top,
                    px(WIDTH, dpi),
                    window_height(dpi),
                    SWP_NOZORDER | SWP_NOACTIVATE,
                );
            }
            redraw(hwnd);
            LRESULT(0)
        }
        WM_DESTROY => {
            let nid = tray_data(hwnd, icon, "");
            unsafe {
                let _ = KillTimer(Some(hwnd), TIMER_HOVER);
                let _ = Shell_NotifyIconW(NIM_DELETE, &nid);
                PostQuitMessage(0);
            }
            LRESULT(0)
        }
        WM_POWERBROADCAST => {
            // Al despertar el equipo los datos pueden tener horas: se consulta ya (el servicio
            // sigue aplicando el backoff si la red aun no esta lista).
            if matches!(
                wparam.0 as u32,
                PBT_APMRESUMEAUTOMATIC | PBT_APMRESUMESUSPEND
            ) {
                send(Cmd::Force);
            }
            LRESULT(1)
        }
        m if m != 0 && m == taskbar_created => {
            // Explorer se reinicio: hay que volver a poner el icono.
            add_tray(hwnd, lang, icon);
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

fn data_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join(DATA_DIR)
}

fn build_service(dir: &Path, lang: &str) -> UsageService {
    // Sin carpeta de usuario la lectura falla como "sin credenciales" (ruta vacia).
    let credentials_path = credentials::default_path().unwrap_or_default();
    UsageService::new(
        ClaudeCodeSource::new(credentials_path),
        SampleStore::new(dir),
        timez::system_zone(),
        lang,
    )
}

/// Hilo de consultas: una al arrancar, luego cada `INTERVAL_MIN` minutos (o antes si toca
/// reintentar tras un backoff), o al pulsar "Actualizar". El servicio aplica el intervalo minimo
/// entre consultas y el backoff; aqui solo se decide cuando preguntarle.
fn start_worker(hwnd: HWND, service: UsageService) {
    let (tx, rx) = mpsc::channel::<Cmd>();
    let _ = WORKER.set(tx);
    // HWND no es Send: viaja como entero y solo se usa para PostMessageW.
    let raw = hwnd.0 as isize;
    thread::spawn(move || {
        let mut force = false;
        loop {
            let view = service.get(force);
            let fetched = Instant::now();
            let retry = view
                .retry_at
                .map(|t| timez::seconds_between(Timestamp::now(), t))
                .filter(|s| *s > 0.0)
                .map(|s| Duration::from_secs_f64(s + 1.0));
            *VIEW.lock().unwrap_or_else(|p| p.into_inner()) = Some(view);
            unsafe {
                let _ = PostMessageW(
                    Some(HWND(raw as *mut c_void)),
                    WM_USAGE,
                    WPARAM(0),
                    LPARAM(0),
                );
            }
            force = loop {
                let poll =
                    Duration::from_secs(u64::from(INTERVAL_MIN.load(Ordering::Relaxed)) * 60);
                let due = retry.map_or(poll, |r| r.min(poll));
                let wait = due.saturating_sub(fetched.elapsed());
                match rx.recv_timeout(wait) {
                    Ok(Cmd::Force) => break true,
                    // Otro intervalo: se recalcula la espera desde la ultima consulta.
                    Ok(Cmd::Reschedule) => continue,
                    Err(RecvTimeoutError::Timeout) => break false,
                    Err(RecvTimeoutError::Disconnected) => return,
                }
            };
            while let Ok(cmd) = rx.try_recv() {
                force |= matches!(cmd, Cmd::Force);
            }
        }
    });
}

unsafe extern "system" fn collect_monitor(
    _monitor: HMONITOR,
    _hdc: HDC,
    rect: *mut RECT,
    data: LPARAM,
) -> BOOL {
    unsafe {
        let list = &mut *(data.0 as *mut Vec<placement::Rect>);
        if let Some(r) = rect.as_ref() {
            list.push([r.left, r.top, r.right, r.bottom]);
        }
    }
    BOOL(1)
}

/// Rectangulos de todos los monitores conectados, en px fisicos de pantalla virtual.
fn monitors() -> Vec<placement::Rect> {
    let mut list: Vec<placement::Rect> = Vec::new();
    unsafe {
        let _ = EnumDisplayMonitors(
            None,
            None,
            Some(collect_monitor),
            LPARAM(&mut list as *mut _ as isize),
        );
    }
    list
}

fn monitor_dpi(monitor: HMONITOR) -> u32 {
    let (mut dpi_x, mut dpi_y) = (96u32, 96u32);
    unsafe {
        let _ = GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dpi_x, &mut dpi_y);
    }
    dpi_x.max(96)
}

pub fn run() {
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

        // Una sola instancia: si ya hay otra, se le pide que se muestre y se sale.
        let _mutex = CreateMutexW(None, false, MUTEX_NAME);
        if GetLastError() == ERROR_ALREADY_EXISTS {
            if let Ok(other) = FindWindowW(CLASS_NAME, PCWSTR::null()) {
                let _ = PostMessageW(Some(other), WM_SHOW_REQ, WPARAM(0), LPARAM(0));
            }
            return;
        }

        let locale = sys_locale::get_locale().unwrap_or_default();
        let lang = format::language_from_locale(&locale);
        let dir = data_dir();
        let settings_path = settings::path_in(&dir);
        let prefs = settings::load(&settings_path);
        INTERVAL_MIN.store(prefs.interval_min, Ordering::Relaxed);
        PROBE.store(
            std::env::args().any(|a| a == "--probar-aviso"),
            Ordering::Relaxed,
        );

        let Ok(module) = GetModuleHandleW(None) else {
            return;
        };
        let instance = module.into();
        let wc = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: CS_HREDRAW | CS_VREDRAW,
            lpfnWndProc: Some(wndproc),
            hInstance: instance,
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            lpszClassName: CLASS_NAME,
            ..Default::default()
        };
        if RegisterClassExW(&wc) == 0 {
            return;
        }

        // De fabrica: arriba a la derecha del monitor principal, 40 px logicos abajo y 24 de
        // margen (igual que la app normal). Si hay posicion guardada y sigue visible, esa.
        let primary = MonitorFromPoint(POINT { x: 0, y: 0 }, MONITOR_DEFAULTTOPRIMARY);
        let mut mi = MONITORINFO {
            cbSize: std::mem::size_of::<MONITORINFO>() as u32,
            ..Default::default()
        };
        let _ = GetMonitorInfoW(primary, &mut mi);
        let pdpi = monitor_dpi(primary);
        let m = mi.rcMonitor;
        let fallback = placement::default_pos(
            [m.left, m.top, m.right, m.bottom],
            px(WIDTH, pdpi),
            px(MARGIN_RIGHT, pdpi),
            px(TOP, pdpi),
        );
        let [left, top] = placement::choose(
            prefs.pos,
            (px(WIDTH, pdpi), window_height(pdpi)),
            &monitors(),
            fallback,
        );
        let dpi = monitor_dpi(MonitorFromPoint(
            POINT { x: left, y: top },
            MONITOR_DEFAULTTONEAREST,
        ));
        let (w, h) = (px(WIDTH, dpi), window_height(dpi));

        let title = wide_z(&format!("{} lite", i18n::strings(lang).app_title));
        let ex_style = if prefs.topmost {
            WS_EX_TOOLWINDOW | WS_EX_TOPMOST
        } else {
            WS_EX_TOOLWINDOW
        };
        let Ok(hwnd) = CreateWindowExW(
            ex_style,
            CLASS_NAME,
            PCWSTR(title.as_ptr()),
            WS_POPUP,
            left,
            top,
            w,
            h,
            None,
            None,
            Some(instance),
            None,
        ) else {
            return;
        };

        let corner: DWM_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND;
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            &corner as *const _ as *const c_void,
            std::mem::size_of::<DWM_WINDOW_CORNER_PREFERENCE>() as u32,
        );
        // Borde fino del color --line de la UI normal (sin esto Windows 11 lo pinta gris claro).
        let border = cref(LINE);
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_BORDER_COLOR,
            &border as *const _ as *const c_void,
            std::mem::size_of::<COLORREF>() as u32,
        );

        let real_dpi = GetDpiForWindow(hwnd).max(96);
        let icon_size = GetSystemMetricsForDpi(SM_CXSMICON, real_dpi).max(16);
        let big_size = GetSystemMetricsForDpi(SM_CXICON, real_dpi).max(32);
        let topmost = prefs.topmost;
        let ui = Ui {
            lang,
            tz: timez::system_zone(),
            dpi: real_dpi,
            fonts: make_fonts(real_dpi),
            icon: load_icon(icon_size),
            balloon_icon: load_icon(big_size),
            taskbar_created: RegisterWindowMessageW(w!("TaskbarCreated")),
            settings: prefs,
            settings_path,
            hover: false,
            hot: false,
        };
        if real_dpi != dpi {
            let _ = SetWindowPos(
                hwnd,
                None,
                left,
                top,
                px(WIDTH, real_dpi),
                window_height(real_dpi),
                SWP_NOZORDER | SWP_NOACTIVATE,
            );
        }
        add_tray(hwnd, ui.lang, ui.icon);
        UI.with_borrow_mut(|slot| *slot = Some(ui));

        start_worker(hwnd, build_service(&dir, lang));
        show(hwnd, topmost);

        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        UI.with_borrow_mut(|slot| {
            if let Some(ui) = slot.take() {
                free_fonts(&ui.fonts);
            }
        });
    }
}
