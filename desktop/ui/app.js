"use strict";
const { getCurrentWindow } = window.__TAURI__.window;

document.getElementById("btnClose").addEventListener("click", () => {
  getCurrentWindow().close();
});
