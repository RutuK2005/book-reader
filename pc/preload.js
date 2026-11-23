// The few things the page cannot do by itself: take files it was opened with, and ask the window
// to go full screen. Nothing else from the computer is exposed to the page.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("desktop", {
  takeOpenFiles: () => ipcRenderer.invoke("take-open-files"),
  onOpenFiles: (fn) => ipcRenderer.on("open-files", () => fn()),
  toggleFullscreen: () => ipcRenderer.invoke("toggle-fullscreen"),
  onFullscreen: (fn) => ipcRenderer.on("fullscreen", (e, on) => fn(on)),
  setTitle: (title) => ipcRenderer.invoke("set-title", title),
  keepAwake: (on) => ipcRenderer.invoke("keep-awake", on),
});
