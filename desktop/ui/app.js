"use strict";
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const { getCurrentWindow } = window.__TAURI__.window;
const { LogicalSize } = window.__TAURI__.dpi;

const WIDTH = 380;
const POLL_MS = 120000;
const $ = (id) => document.getElementById(id);
const COLORS = { claude_code: "var(--cc)", cowork: "var(--cowork)", chat: "var(--chat)", other: "var(--other)" };
// Los umbrales de color viven en Rust (R7, modulo colors); aqui solo se mapea el nombre a la paleta.
const PALETTE = { green: "var(--green)", amber: "var(--amber)", red: "var(--red)", gray: "var(--dim)" };
const paint = (name) => PALETTE[name] || PALETTE.gray;

let data = null;
let open = null;
let T = I18N.es;

// Toda cadena que venga de la API pasa por aqui antes de entrar en innerHTML.
function esc(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function setLang(lang) {
  T = I18N[lang] || I18N.en;
  document.documentElement.lang = lang;
  $("lblToday").textContent = T.today;
  $("lblSession").textContent = T.session;
  $("lblWeek").textContent = T.week;
  $("icoHist").title = T.titles.hist;
  $("icoMix").title = T.titles.mix;
  $("icoProj").title = T.titles.proj;
  $("btnRefresh").title = T.titles.refresh;
  $("btnClose").title = T.titles.close;
}

const hm = (d) => d.toLocaleTimeString(T.locale, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

function when(iso) {
  if (!iso) return "--";
  const d = new Date(iso);
  const now = new Date();
  const days = Math.round((new Date(d.toDateString()) - new Date(now.toDateString())) / 864e5);
  if (days === 0) return hm(d);
  if (days === 1) return T.tomorrow + " " + hm(d);
  if (days > 1 && days < 7) return T.daysLong[d.getDay()] + " " + hm(d);
  return d.toLocaleDateString(T.locale, { day: "numeric", month: "short" }) + " " + hm(d);
}

function ago(seconds) {
  if (seconds < 3600) return Math.max(1, Math.round(seconds / 60)) + T.minutes;
  return Math.round(seconds / 3600) + T.hours;
}

function clampPct(p) { return Math.min(Math.max(p, 0), 100); }

function setBar(el, pct, color) {
  el.style.width = clampPct(pct) + "%";
  el.style.background = color;
}

function statusText() {
  if (!data) return T.errors.loading;
  if (data.error === "ipc") return T.errors.ipc;
  if (!data.error) return "";
  let text = T.errors[data.error] || T.errors.unexpected;
  if (data.error === "rate_limited" || data.error === "server_error") {
    text += data.retry_at ? when(data.retry_at) : "";
  }
  // Con cualquier error se sigue mostrando el ultimo dato: se dice de cuando es.
  if (data.age_seconds != null) text += T.ago + ago(data.age_seconds);
  return text;
}

function render() {
  $("status").textContent = statusText();
  $("status").classList.toggle("err", !!(data && data.error));
  $("updated").textContent = data && data.updated_at ? T.updated + when(data.updated_at) : "";
  if (!data || !data.usage) { renderPanel(); fit(); return; }

  const u = data.usage;
  const t = data.today;
  const quotaText = t.quota == null ? T.noQuota : T.ofQuota + t.quota.toFixed(1) + "%";
  $("todayNum").textContent = t.used.toFixed(1) + "%" + quotaText + (t.partial ? " (" + T.partial + ")" : "");
  // Ancho de la barra de hoy: sin cupo o cupo 0 -> vacia; cupo negativo (semana agotada) ->
  // llena; si no, el cociente. El color ya viene decidido por Rust (R7).
  const todayWidth = t.quota == null || t.quota === 0 ? 0 : t.quota < 0 ? 100 : (t.used / t.quota) * 100;
  setBar($("todayBar"), todayWidth, paint(t.color));

  $("sPct").textContent = Math.round(u.session.percent) + "%";
  $("sReset").textContent = T.reset + when(u.session.resets_at);
  setBar($("sBar"), u.session.percent, paint(data.session_color));

  $("wPct").textContent = Math.round(u.weekly.percent) + "%";
  $("wReset").textContent = T.reset + when(u.weekly.resets_at);
  setBar($("wBar"), u.weekly.percent, paint(data.weekly_color));
  const mark = $("wMark");
  if (data.pace_mark == null) {
    mark.hidden = true;
  } else {
    mark.hidden = false;
    mark.style.left = data.pace_mark * 100 + "%";
    mark.title = T.pace + Math.round(data.pace_mark * 100) + "%";
  }

  $("extra").innerHTML = u.scoped.map((e, i) => `
    <div class="row extra" data-tauri-drag-region><div class="t"><b><span>${T.weekPrefix}${esc(String(e.label).toUpperCase())}</span><span>${Math.round(e.percent)}%</span></b>
      <span class="r">${e.resets_at ? T.reset + esc(when(e.resets_at)) : ""}</span></div>
      <div class="bar"><i style="width:${clampPct(e.percent)}%;background:${paint(data.scoped_colors[i])}"></i></div></div>`).join("");

  renderPanel();
  fit();
}

function closeButton() {
  return `<span class="ico pclose"><svg viewBox="0 0 16 16" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="M4 4l8 8M12 4l-8 8"/></svg></span>`;
}

function renderHist() {
  const h = data.history;
  // Solo un cupo positivo se dibuja como linea; nulo, cero o negativo no tienen linea.
  const q = data.today.quota > 0 ? data.today.quota : null;
  const max = Math.max(q || 0, ...h.map((d) => d.used), 1) * 1.15;
  const todayIso = h[h.length - 1].date;
  const since = data.today.tracking_since;
  const limit = q == null ? "" : `<div class="limit" style="bottom:${(q / max) * 78}px"><em>${T.hist.quota}${q.toFixed(1)}%</em></div>`;
  return `<h4>${T.hist.title} ${closeButton()}</h4>
    <div class="chart">${limit}
      ${h.map((d) => {
        // Hoy lleva el color que decide el nucleo (R7); los dias pasados, gris neutro.
        const isToday = d.date === todayIso;
        const bg = isToday ? `;background:${paint(data.today.color)}` : "";
        return `<div class="col ${isToday ? "today" : ""}">
        <span class="v">${d.used > 0 ? d.used.toFixed(1) + "%" : ""}</span>
        <div class="b" style="height:${(d.used / max) * 78}px${bg}"></div></div>`;
      }).join("")}
    </div>
    <div class="days">${h.map((d) => {
      const dt = new Date(d.date + "T12:00");
      return `<span class="${d.date === todayIso ? "today" : ""}">${d.date === todayIso ? T.todayShort : T.days[dt.getDay()]}</span>`;
    }).join("")}</div>
    <div class="note">${T.hist.note}${since ? T.hist.since + esc(when(since)) + "." : ""}</div>`;
}

function renderMix() {
  const rows = data.usage.breakdown;
  return `<h4>${T.mix.title} ${closeButton()}</h4>
    <div class="stack">${rows.filter((r) => r.percent > 0).map((r) =>
      `<span style="flex:${Math.max(r.percent, 0)};background:${COLORS[r.key] || "var(--other)"}"></span>`).join("")}</div>
    <div class="legend">${rows.map((r) =>
      `<div><i style="background:${COLORS[r.key] || "var(--other)"}"></i>${esc(r.label)}<b>${Math.round(r.percent)}%</b></div>`).join("")}</div>
    <div class="note">${T.mix.note}${Math.round(data.usage.weekly.percent)}${T.mix.noteEnd}</div>`;
}

function projItem(label, x, resetIso) {
  if (!x.hits_at) return `<div class="proj ok"><small>${label}</small><p>${T.proj.none}</p></div>`;
  // El borde solo traduce el booleano del nucleo (R5/R6): antes del reinicio -> rojo; si no, verde.
  const cls = x.before_reset === true ? "bad" : "ok";
  const head = x.before_reset ? T.proj.hitsBefore + esc(when(x.hits_at)) : T.proj.notBefore;
  const sub = x.before_reset
    ? T.proj.subBefore + esc(when(resetIso)) + T.proj.subBeforeEnd
    : T.proj.subAfter + esc(when(x.hits_at)) + T.proj.subAfterMid + esc(when(resetIso)) + T.proj.subAfterEnd;
  return `<div class="proj ${cls}"><small>${label}</small><p>${head}</p><p>${sub}</p></div>`;
}

function renderProj() {
  const p = data.projection;
  const note = p.weekly.basis === "24h" ? T.proj.note24 : T.proj.noteWindow;
  return `<h4>${T.proj.title} ${closeButton()}</h4>
    ${projItem(T.session, p.session, data.usage.session.resets_at)}
    ${projItem(T.week, p.weekly, data.usage.weekly.resets_at)}
    <div class="note">${note}</div>`;
}

function renderPanel() {
  const p = $("panel");
  document.querySelectorAll(".ico[data-p]").forEach((el) => el.classList.toggle("on", el.dataset.p === open));
  if (!open || !data || !data.usage) { p.hidden = true; return; }
  p.hidden = false;
  p.innerHTML = open === "hist" ? renderHist() : open === "mix" ? renderMix() : renderProj();
  p.querySelector(".pclose").addEventListener("click", () => toggle(null));
}

function toggle(name) {
  open = open === name ? null : name;
  renderPanel();
  fit();
}

// Ajusta el alto de la ventana al contenido (ancho fijo).
function fit() {
  requestAnimationFrame(() => {
    const height = Math.ceil($("root").getBoundingClientRect().height);
    getCurrentWindow().setSize(new LogicalSize(WIDTH, height)).catch(() => {});
  });
}

async function load(force = false) {
  try {
    const view = await invoke("get_usage", { force });
    setLang(view.lang);
    data = view;
  } catch (e) {
    data = Object.assign({}, data || {}, { error: "ipc" });
  }
  render();
}

document.querySelectorAll(".ico[data-p]").forEach((el) => el.addEventListener("click", () => toggle(el.dataset.p)));
$("btnRefresh").addEventListener("click", () => load(true));
$("btnClose").addEventListener("click", () => getCurrentWindow().close());
listen("refresh", () => load(true));
setLang("es");
load();
setInterval(() => load(false), POLL_MS);
