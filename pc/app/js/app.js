// Starts the app and switches between the home page and the reader.
import * as store from "./store.js";
import * as home from "./home.js";
import * as reader from "./reader.js";
import { toast, dialogOpen } from "./ui.js";

const homeEl = document.getElementById("home");
const readerEl = document.getElementById("reader");

function showHome() {
  homeEl.hidden = false;
  home.build();
}

async function openBook(b) {
  homeEl.hidden = true;
  await reader.open(b);
}

/** Books the app was started with, or handed later, from Explorer's "Open with". */
async function takeOpenFiles() {
  if (!window.desktop) return;
  const files = await window.desktop.takeOpenFiles();
  if (!files.length) return;
  if (reader.isOpen()) reader.close();
  const last = await home.addFiles(files);
  if (last) openBook(last);
}

async function start() {
  try {
    await store.load();
  } catch (e) {
    console.error(e);
    homeEl.textContent = "MyBookSpace couldn't open its library on this computer.";
    return;
  }
  home.init(homeEl, openBook);
  reader.init(readerEl, showHome);
  showHome();

  window.addEventListener("keydown", (e) => {
    if (reader.isOpen()) {
      if (reader.key(e)) e.preventDefault();
      return;
    }
    if (dialogOpen()) return;
    if ((e.ctrlKey || e.metaKey) && (e.key === "o" || e.key === "O")) {
      e.preventDefault();
      home.pick();
    } else if (e.key === "F11" && window.desktop) {
      e.preventDefault();
      window.desktop.toggleFullscreen();
    }
  });
  window.addEventListener("unhandledrejection", (e) => console.error(e.reason));

  if (window.desktop) {
    window.desktop.onOpenFiles(takeOpenFiles);
    takeOpenFiles();
  }
}

start().catch((e) => {
  console.error(e);
  toast("Something went wrong while starting");
});
