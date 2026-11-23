// The bookshelf. Imported books are copied into the app's own storage, so a book keeps working
// even if the original file is moved or deleted. Also keeps the reader's shelves, settings and
// a per-day reading log for the streak and daily goal.
import { openBook, sniff, DEFAULT_TEXT_SIZE } from "./docs.js";

const DB_NAME = "mybookspace";
let db = null;

function request(r) {
  return new Promise((ok, fail) => {
    r.onsuccess = () => ok(r.result);
    r.onerror = () => fail(r.error);
  });
}

async function openDb() {
  const r = indexedDB.open(DB_NAME, 1);
  r.onupgradeneeded = () => {
    r.result.createObjectStore("kv");
    r.result.createObjectStore("files");
  };
  db = await request(r);
}

const kv = (mode) => db.transaction("kv", mode).objectStore("kv");
const files = (mode) => db.transaction("files", mode).objectStore("files");

export const getFile = (key) => request(files("readonly").get(key));
const putFile = (key, blob) => request(files("readwrite").put(blob, key));
const delFile = (key) => request(files("readwrite").delete(key));

const DEFAULT_PREFS = {
  theme: 1, warmth: 0, bright: -1, anim: 0, crop: true, dual: true, clock: true, sound: true,
  autoNight: false, goalMin: 20, shelf: "", homeOpen: false, homeScheme: 0, coversOnly: false,
  tabOrder: [], guideShown: false, rulerOn: false, rulerBand: 120, rulerY: 0.4, menuTab: 0, markColor: 0,
};

/** Everything that is not a book file: the list of books, shelves, the reading log, settings. */
export const state = { books: [], shelves: [], daily: {}, prefs: { ...DEFAULT_PREFS } };

export const prefs = () => state.prefs;

export async function load() {
  await openDb();
  const saved = await request(kv("readonly").get("state"));
  if (saved) {
    state.books = saved.books || [];
    state.shelves = saved.shelves || [];
    state.daily = saved.daily || {};
    state.prefs = { ...DEFAULT_PREFS, ...(saved.prefs || {}) };
  }
  // Ask the browser not to clear the library when the disk runs low.
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
}

let saving = Promise.resolve();

export function save() {
  const snapshot = JSON.parse(JSON.stringify(state));
  saving = saving.then(() => request(kv("readwrite").put(snapshot, "state"))).catch(() => {
    // Losing a progress update is better than stopping the reader.
  });
  return saving;
}

export function setPref(key, value) {
  state.prefs[key] = value;
  save();
}

// ---------------------------------------------------------------- books

/** The books in the order the reader has arranged them; new books go to the front. */
export const list = () => state.books.filter((b) => !b.hidden);
export const find = (id) => state.books.find((b) => b.id === id) || null;
export const started = (b) => b.opened > 0 && b.page > 0;
export const finished = (b) => b.pages > 0 && b.page >= b.pages - 1;
export const percent = (b) => (b.pages > 0 ? Math.min(100, Math.floor(((b.page + 1) * 100) / b.pages)) : 0);
export const kindName = (b) => (b.kind === "pdf" ? "PDF" : b.kind === "txt" ? "Text" : "EPUB");

/** A readable title from a file name: no download-site tags, separators turned into spaces. */
export function niceTitle(name) {
  let t = name.replace(/\.(pdf|epub|txt)$/i, "");
  t = t.replace(/\(?\s*pdf\s*drive(\.com)?\s*\)?/gi, " ");
  t = t.replace(/\(?\s*z-lib(\.org)?\s*\)?/gi, " ");
  t = t.replace(/_?ocean\s*of\s*pdf(\.com)?_?/gi, " ");
  t = t.replace(/[_-]+/g, " ").replace(/\s+/g, " ").trim();
  return t || "Untitled";
}

function canvasBlob(canvas) {
  return new Promise((ok) => canvas.toBlob(ok, "image/jpeg", 0.88));
}

/** A cover for a book with no picture of its own: its title set on a sheet of paper. */
function titleCover(title, kind) {
  const c = document.createElement("canvas");
  c.width = 420;
  c.height = 600;
  const g = c.getContext("2d");
  g.fillStyle = "#F3E7CC";
  g.fillRect(0, 0, 420, 600);
  g.fillStyle = "#B5651D";
  g.fillRect(0, 0, 420, 14);
  g.fillStyle = "#3A2A22";
  g.textAlign = "center";
  g.font = "bold 40px Georgia, serif";
  const words = title.split(/\s+/), lines = [];
  let line = "";
  for (const w of words) {
    if (line && g.measureText(line + " " + w).width > 340) {
      lines.push(line);
      line = w;
    } else {
      line = line ? line + " " + w : w;
    }
  }
  if (line) lines.push(line);
  const shown = lines.slice(0, 7);
  let y = 300 - (shown.length - 1) * 25;
  for (const l of shown) {
    g.fillText(l, 210, y);
    y += 50;
  }
  g.font = "16px Georgia, serif";
  g.fillStyle = "#8A6F55";
  g.fillText(kind.toUpperCase(), 210, 560);
  return c;
}

async function imageToCover(source) {
  const bmp = await createImageBitmap(source, { imageOrientation: "from-image" });
  const width = Math.min(600, bmp.width);
  const c = document.createElement("canvas");
  c.width = width;
  c.height = Math.max(1, Math.round((bmp.height * width) / bmp.width));
  const g = c.getContext("2d");
  // Transparent pictures get a white page behind them; JPEG would turn the gaps black.
  g.fillStyle = "#fff";
  g.fillRect(0, 0, c.width, c.height);
  g.drawImage(bmp, 0, 0, c.width, c.height);
  bmp.close();
  return canvasBlob(c);
}

/** The cover a book has before the reader chooses one: its first page, or its own cover picture. */
async function firstCover(book, doc) {
  if (doc.kind === "pdf") {
    const canvas = await doc.draw(0, 420, null).done;
    return canvasBlob(canvas);
  }
  if (doc.coverUrl) {
    try {
      return await imageToCover(await (await fetch(doc.coverUrl)).blob());
    } catch (e) {
      // Fall through to a cover made from the title.
    }
  }
  return canvasBlob(titleCover(book.title, kindName(book)));
}

/** Copies a book into the library. `file` is a File, or {name, data} from the desktop shell. */
export async function importFile(file) {
  const blob = file instanceof Blob ? file : new Blob([file.data]);
  const name = file.name || "Book.pdf";
  const dup = state.books.find((b) => b.size === blob.size && b.src === name);
  if (dup) return dup;
  const kind = await sniff(blob);
  const book = {
    id: Date.now().toString(36) + Math.floor(Math.random() * 1296).toString(36),
    title: niceTitle(name), src: name, shelf: "", kind, pages: 0, page: 0, opened: 0,
    added: Date.now(), size: blob.size, textSize: DEFAULT_TEXT_SIZE, crop: null,
    bookmarks: [], notes: {}, highlights: {}, secPerPage: 0, samples: 0, readSec: 0,
    celebrated: false, partner: "", hidden: false, ownCover: false,
  };
  const doc = await openBook(blob, kind, book.textSize);
  try {
    book.pages = doc.pages;
    if (doc.title) book.title = doc.title;
    await putFile("book:" + book.id, blob);
    await putFile("cover:" + book.id, await firstCover(book, doc));
  } catch (e) {
    await delFile("book:" + book.id);
    await delFile("cover:" + book.id);
    throw e;
  } finally {
    doc.close();
  }
  state.books.unshift(book);
  await save();
  return book;
}

export const bookFile = (b) => getFile("book:" + b.id);
export const coverFile = (b) => getFile("cover:" + b.id);

export async function remove(b) {
  // Removing one version leaves the other in the library on its own.
  if (b.partner) unmerge(b);
  state.books = state.books.filter((x) => x !== b);
  await delFile("book:" + b.id);
  await delFile("cover:" + b.id);
  await save();
}

/** Makes a picture the reader picked the book's cover. */
export async function setCover(b, source) {
  let blob;
  try {
    blob = await imageToCover(source);
  } catch (e) {
    throw new Error("That file isn't a picture");
  }
  await putFile("cover:" + b.id, blob);
  b.ownCover = true;
  await save();
}

/** Goes back to the cover the book came with. */
export async function resetCover(b) {
  const doc = await openBook(await bookFile(b), b.kind, b.textSize);
  try {
    await putFile("cover:" + b.id, await firstCover(b, doc));
  } finally {
    doc.close();
  }
  b.ownCover = false;
  await save();
}

/**
 * Makes two books one entry with two versions. The PDF stays on show, since a PDF usually has
 * the better cover; otherwise the book that was dropped onto does. Returns false if either
 * already has a second version.
 */
export function merge(dragged, target) {
  if (dragged === target || dragged.hidden || target.hidden || dragged.partner || target.partner) return false;
  const shown = dragged.kind === "pdf" && target.kind !== "pdf" ? dragged : target;
  const tucked = shown === target ? dragged : target;
  if (shown === dragged) {
    // Keep the entry where the reader dropped it.
    state.books.splice(state.books.indexOf(dragged), 1);
    state.books.splice(state.books.indexOf(target), 0, dragged);
  }
  shown.partner = tucked.id;
  tucked.hidden = true;
  if (!shown.shelf) shown.shelf = tucked.shelf;
  save();
  return true;
}

export function unmerge(b) {
  const other = find(b.partner);
  b.partner = "";
  if (other) {
    other.hidden = false;
    other.shelf = b.shelf;
    state.books.splice(state.books.indexOf(other), 1);
    state.books.splice(state.books.indexOf(b) + 1, 0, other);
  }
  save();
}

/** Moves a book to where another one sits; the books in between shift along. */
export function move(book, onto) {
  const from = state.books.indexOf(book);
  if (from < 0 || book === onto) return;
  state.books.splice(from, 1);
  const to = state.books.indexOf(onto);
  state.books.splice(from <= to ? to + 1 : to, 0, book);
  save();
}

// ---------------------------------------------------------------- shelves

export function addShelf(name) {
  const n = name.trim().replace(/^#+/, "");
  if (!n || state.shelves.includes(n)) return null;
  state.shelves.push(n);
  save();
  return n;
}

export function renameShelf(from, to) {
  const n = to.trim().replace(/^#+/, "");
  if (!n || n === from || state.shelves.includes(n)) return false;
  state.shelves[state.shelves.indexOf(from)] = n;
  for (const b of state.books) if (b.shelf === from) b.shelf = n;
  const order = state.prefs.tabOrder, i = order.indexOf(from);
  if (i >= 0) order[i] = n;
  if (state.prefs.shelf === from) state.prefs.shelf = n;
  save();
  return true;
}

export function deleteShelf(name) {
  state.shelves = state.shelves.filter((s) => s !== name);
  for (const b of state.books) if (b.shelf === name) b.shelf = "";
  save();
}

// ---------------------------------------------------------------- reading log

function dayKey(daysAgo) {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
}

export function addReading(b, seconds) {
  if (seconds <= 0) return;
  if (b) b.readSec += seconds;
  const key = dayKey(0);
  state.daily[key] = (state.daily[key] || 0) + seconds;
  const keys = Object.keys(state.daily);
  if (keys.length > 400) {
    // Keep about a year: anything older can no longer affect a streak or a weekly total.
    const oldest = dayKey(370);
    for (const k of keys) if (k < oldest) delete state.daily[k];
  }
}

export const secondsOn = (daysAgo) => state.daily[dayKey(daysAgo)] || 0;

export function weekSeconds() {
  let sum = 0;
  for (let i = 0; i < 7; i++) sum += secondsOn(i);
  return sum;
}

/** Consecutive days with at least a minute of reading. Today not having happened yet does not break it. */
export function streak() {
  let n = 0;
  for (let i = secondsOn(0) >= 60 ? 0 : 1; secondsOn(i) >= 60; i++) n++;
  return n;
}
