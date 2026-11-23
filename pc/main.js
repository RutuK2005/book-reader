// The desktop shell. Everything the reader does lives in app/ as an ordinary web page; this file
// only gives it a window, a stable address to keep its library under, and the files it is opened with.
const { app, BrowserWindow, Menu, ipcMain, net, protocol, session, shell } = require("electron");
const fs = require("fs");
const path = require("path");
const { pathToFileURL } = require("url");

const APP_DIR = path.join(__dirname, "app");
const BOOK_TYPES = [".pdf", ".epub", ".txt"];

// The library is stored under this address, so it must never change between versions.
protocol.registerSchemesAsPrivileged([
  { scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
]);

// Lets the build check run against a throwaway library instead of the real one.
if (process.env.MBS_PROFILE) app.setPath("userData", process.env.MBS_PROFILE);

let win = null;
/** Books handed to the app from Explorer, waiting for the page to ask for them. */
let pending = [];

function booksIn(argv) {
  return argv.filter((a) => BOOK_TYPES.includes(path.extname(a).toLowerCase()) && fs.existsSync(a));
}

function stateFile() {
  return path.join(app.getPath("userData"), "window.json");
}

function readBounds() {
  try {
    return JSON.parse(fs.readFileSync(stateFile(), "utf8"));
  } catch (e) {
    return {};
  }
}

function createWindow() {
  const saved = readBounds();
  win = new BrowserWindow({
    width: saved.width || 1180,
    height: saved.height || 820,
    x: saved.x,
    y: saved.y,
    minWidth: 420,
    minHeight: 480,
    backgroundColor: "#EFEAE0",
    title: "MyBookSpace",
    icon: path.join(APP_DIR, "icon.png"),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });
  if (saved.maximized) win.maximize();
  win.once("ready-to-show", () => win.show());
  win.on("close", () => {
    try {
      const b = win.getNormalBounds();
      fs.writeFileSync(stateFile(), JSON.stringify({ ...b, maximized: win.isMaximized() }));
    } catch (e) {
      // The window just opens at its default size next time.
    }
  });
  win.on("closed", () => (win = null));
  win.on("enter-full-screen", () => win.webContents.send("fullscreen", true));
  win.on("leave-full-screen", () => win.webContents.send("fullscreen", false));
  // Links (the dictionary source, a cover search) open in the real browser, never in the app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith("app://")) e.preventDefault();
  });
  win.loadURL("app://mybookspace/index.html");

  const shot = process.env.MBS_SCREENSHOT;
  if (shot) {
    // Used by the build check: prove the packaged app starts and draws its home page.
    win.webContents.once("did-finish-load", () => {
      setTimeout(async () => {
        const image = await win.webContents.capturePage();
        fs.writeFileSync(shot, image.toPNG());
        app.quit();
      }, Number(process.env.MBS_SCREENSHOT_DELAY || 2500));
    });
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", (e, argv) => {
    const files = booksIn(argv.slice(1));
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
      if (files.length) {
        pending.push(...files);
        win.webContents.send("open-files");
      }
    }
  });

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null);
    protocol.handle("app", (request) => {
      const url = new URL(request.url);
      const file = path.normalize(path.join(APP_DIR, decodeURIComponent(url.pathname)));
      if (!file.startsWith(APP_DIR)) return new Response("Forbidden", { status: 403 });
      return net.fetch(pathToFileURL(file).toString());
    });
    // Pasting a cover picture needs the clipboard; nothing else is ever granted.
    session.defaultSession.setPermissionRequestHandler((wc, permission, done) => {
      done(permission === "clipboard-read" || permission === "clipboard-sanitized-write" || permission === "fullscreen");
    });
    pending = booksIn(process.argv.slice(1));
    createWindow();
  });

  app.on("window-all-closed", () => app.quit());

  ipcMain.handle("take-open-files", () => {
    const out = [];
    for (const p of pending.splice(0)) {
      try {
        out.push({ name: path.basename(p), data: fs.readFileSync(p) });
      } catch (e) {
        // A file that vanished or cannot be read is simply skipped.
      }
    }
    return out;
  });
  ipcMain.handle("toggle-fullscreen", () => {
    if (win) win.setFullScreen(!win.isFullScreen());
  });
  ipcMain.handle("set-title", (e, title) => {
    if (win) win.setTitle(title);
  });
  ipcMain.handle("keep-awake", (e, on) => {
    const { powerSaveBlocker } = require("electron");
    if (on && app.awakeId === undefined) app.awakeId = powerSaveBlocker.start("prevent-display-sleep");
    if (!on && app.awakeId !== undefined) {
      powerSaveBlocker.stop(app.awakeId);
      app.awakeId = undefined;
    }
  });
}
