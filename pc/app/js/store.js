// The bookshelf. Imported books are copied into the app's own storage, so a book keeps working
// even if the original file is moved or deleted. Also keeps the reader's shelves, settings and
// a per-day reading log for the streak and daily goal.
import { openBook, sniff, DEFAULT_TEXT_SIZE } from "./docs.js";
import { readZip, makeZip } from "./zip.js";

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
/** A write counts only once it is really on the disk: a full disk fails here, not at the request. */
function written(run) {
  return new Promise((ok, fail) => {
    const tx = db.transaction("files", "readwrite");
    tx.oncomplete = () => ok();
    tx.onabort = tx.onerror = () => fail(tx.error || new Error("The library couldn't be written to this computer's disk"));
    run(tx.objectStore("files"));
  });
}
const putFile = (key, blob) => written((st) => st.put(blob, key));
const delFile = (key) => written((st) => st.delete(key));

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

let saving = Promise.resolve(), saveFailed = false;
/** Called once if the library can no longer be saved (a full disk, usually), so the reader can be told. */
let onSaveError = null;
export function whenSaveFails(fn) {
  onSaveError = fn;
}

export function save() {
  const snapshot = JSON.parse(JSON.stringify(state));
  saving = saving.then(() => new Promise((ok, fail) => {
    const tx = db.transaction("kv", "readwrite");
    tx.oncomplete = () => ok();
    tx.onabort = tx.onerror = () => fail(tx.error);
    tx.objectStore("kv").put(snapshot, "state");
  })).then(() => {
    saveFailed = false;
  }).catch((e) => {
    // Losing a progress update is better than stopping the reader, but not without a word.
    console.error(e);
    if (!saveFailed && onSaveError) onSaveError();
    saveFailed = true;
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
/**
 * Read to the last page. A book that has never been opened is not finished, however short; and
 * one whose end was reached in a two-page spread counts even if it was left on the page before.
 */
export const finished = (b) => b.pages > 0 && b.opened > 0 && (b.page >= b.pages - 1 || (b.celebrated && b.page >= b.pages - 2));
export const percent = (b) => (b.pages <= 0 ? 0 : finished(b) ? 100 : Math.min(100, Math.floor(((b.page + 1) * 100) / b.pages)));
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

/** Whether the last importFile found the book already in the library. */
export const lastImport = { existed: false };

/** Copies a book into the library. `file` is a File, or {name, data} from the desktop shell. */
export async function importFile(file) {
  const blob = file instanceof Blob ? file : new Blob([file.data]);
  const name = file.name || "Book.pdf";
  const dup = state.books.find((b) => b.size === blob.size && b.src === name);
  lastImport.existed = !!dup;
  if (dup) return dup;
  const kind = await sniff(blob);
  if (kind === "other") throw new Error("isn't a book MyBookSpace can read. It opens PDF, EPUB and text files.");
  if (kind === "epub" && !/\.epub$/i.test(name)) {
    // Word documents and plain zips start the same way as an EPUB; only a real one is taken in.
    let real = false;
    try {
      real = (await readZip(blob)).has("META-INF/container.xml");
    } catch (e) {
      real = false;
    }
    if (!real) throw new Error("isn't a book MyBookSpace can read. It opens PDF, EPUB and text files.");
  }
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
  if (!n || state.shelves.some((s) => s.toLowerCase() === n.toLowerCase())) return null;
  state.shelves.push(n);
  save();
  return n;
}

export function renameShelf(from, to) {
  const n = to.trim().replace(/^#+/, "");
  if (n === from) return true;
  if (!n || state.shelves.some((s) => s !== from && s.toLowerCase() === n.toLowerCase())) return false;
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

// ---------------------------------------------------------------- moving the library

/*
 * A library file is a zip the PC and Android versions both read and write. manifest.json comes
 * first and lists the books with their place, bookmarks, notes and highlights; then each
 * book's original file and its cover.
 */
const TRANSFER_FORMAT = "mybookspace-library";

function manifestOf(b, hasCover) {
  const o = {
    id: b.id, title: b.title, src: b.src, shelf: b.shelf, kind: b.kind, file: `books/${b.id}.${b.kind}`,
    ownCover: b.ownCover, pages: b.pages, page: b.page, opened: b.opened, added: b.added, size: b.size,
    bookmarks: b.bookmarks, notes: b.notes, highlights: b.highlights, secPerPage: b.secPerPage,
    samples: b.samples, readSec: b.readSec, celebrated: b.celebrated, textSize: b.textSize,
    partner: b.partner, hidden: b.hidden,
  };
  if (hasCover) o.cover = `covers/${b.id}.jpg`;
  return o;
}

/**
 * Puts the chosen books, with everything the reader has done in them, into one file. A book's
 * second version goes along with it. Returns {blob, count}.
 */
export async function exportLibrary(chosen, progress) {
  const out = state.books.filter((b) => chosen.includes(b) || chosen.some((c) => c.partner === b.id));
  const entries = [], books = [];
  for (const b of out) {
    const file = await bookFile(b);
    if (!file) continue;
    const cover = await coverFile(b);
    books.push(manifestOf(b, !!cover));
    entries.push({ name: `books/${b.id}.${b.kind}`, blob: file });
    if (cover) entries.push({ name: `covers/${b.id}.jpg`, blob: cover });
  }
  const manifest = {
    format: TRANSFER_FORMAT, version: 1, app: "pc", exportedAt: Date.now(), books,
    shelves: state.shelves, daily: state.daily, goalMin: state.prefs.goalMin,
  };
  entries.unshift({ name: "manifest.json", blob: new Blob([JSON.stringify(manifest)]) });
  return { blob: await makeZip(entries, progress), count: books.length };
}

/** True for a library file, whatever it is called. */
export async function isLibraryFile(blob, name = "") {
  if (/\.mbs$/i.test(name)) return true;
  if ((await sniff(blob)) !== "epub") return false;
  try {
    const zip = await readZip(blob);
    return zip.has("manifest.json") && !zip.has("META-INF/container.xml");
  } catch (e) {
    return false;
  }
}

function fromManifest(o, sameApp) {
  const notes = {};
  for (const [k, v] of Object.entries(o.notes || {})) if (Number.isInteger(Number(k)) && typeof v === "string") notes[Number(k)] = v;
  const highlights = {};
  for (const [k, list] of Object.entries(o.highlights || {})) {
    const rects = (Array.isArray(list) ? list : []).filter((r) => Array.isArray(r) && r.length >= 4).map((r) => r.slice(0, 5).map(Number));
    if (rects.length && Number.isInteger(Number(k))) highlights[Number(k)] = rects;
  }
  return {
    id: String(o.id || ""), title: String(o.title || "Untitled"), src: String(o.src || ""), shelf: String(o.shelf || "").trim(),
    kind: "pdf", pages: Number(o.pages) || 0, page: Math.max(0, Number(o.page) || 0), opened: Number(o.opened) || 0,
    added: Number(o.added) || Date.now(), size: Number(o.size) || 0,
    // Text sizes mean different things on a PC and on Android, so only its own are kept.
    textSize: sameApp && Number(o.textSize) > 0 ? Number(o.textSize) : DEFAULT_TEXT_SIZE, crop: null,
    bookmarks: [...new Set((o.bookmarks || []).map(Number).filter(Number.isInteger))].sort((a, b) => a - b),
    notes, highlights, secPerPage: Number(o.secPerPage) || 0, samples: Number(o.samples) || 0,
    readSec: Number(o.readSec) || 0, celebrated: !!o.celebrated, partner: String(o.partner || ""),
    hidden: !!o.hidden, ownCover: !!o.ownCover,
  };
}

/**
 * A book laid out from an EPUB or text file has a different number of pages on each device, so
 * everything tied to a page moves to the same point in the book. Highlights mark an area of a
 * page and cannot be carried over.
 */
function fitPages(b, pages) {
  const old = b.pages;
  if (old > 0 && old !== pages) {
    // The last page stays the last page, so a finished book does not come back unfinished.
    const k = pages / old, at = (p) => (p >= old - 1 ? pages - 1 : Math.min(pages - 1, Math.round(p * k)));
    b.page = at(b.page);
    b.bookmarks = [...new Set(b.bookmarks.map(at))].sort((x, y) => x - y);
    const notes = {};
    for (const [p, text] of Object.entries(b.notes)) {
      const q = at(Number(p));
      notes[q] = notes[q] ? notes[q] + "\n" + text : text;
    }
    b.notes = notes;
    b.highlights = {};
    b.secPerPage = 0;
    b.samples = 0;
  }
  b.pages = pages;
  b.page = Math.max(0, Math.min(pages - 1, b.page));
}

/** A book that is already here takes the newer of the two reading positions and keeps both sets of notes. */
function mergeInto(have, o) {
  const from = fromManifest(o, false);
  if (have.pages > 0) fitPages(from, have.pages);
  if (from.opened > have.opened) {
    have.page = from.page;
    have.opened = from.opened;
    if (from.samples > 0) {
      have.secPerPage = from.secPerPage;
      have.samples = from.samples;
    }
  }
  // Highlights from both devices are kept, like bookmarks and notes; the same one is not added twice.
  for (const [p, rects] of Object.entries(from.highlights)) {
    const list = have.highlights[p] || (have.highlights[p] = []);
    const seen = new Set(list.map((r) => r.slice(0, 4).map((v) => Number(v).toFixed(4)).join()));
    for (const r of rects) {
      const key = r.slice(0, 4).map((v) => Number(v).toFixed(4)).join();
      if (!seen.has(key)) {
        seen.add(key);
        list.push(r);
      }
    }
  }
  have.bookmarks = [...new Set([...have.bookmarks, ...from.bookmarks])].sort((a, b) => a - b);
  for (const [p, text] of Object.entries(from.notes)) {
    const had = have.notes[p];
    if (!had) have.notes[p] = text;
    else if (!had.includes(text)) have.notes[p] = had + "\n" + text;
  }
  have.readSec = Math.max(have.readSec, from.readSec);
  have.celebrated = have.celebrated || from.celebrated;
  if (!have.shelf) have.shelf = from.shelf;
}

/**
 * Adds the books of a library file to this library, with their places, bookmarks, notes,
 * highlights, covers and shelves. Books already here are not copied again; they are brought up
 * to date instead. Returns {added, updated, failed}.
 */
export async function importLibrary(file, progress) {
  const wrong = new Error("That file isn't a MyBookSpace library");
  let zip, root;
  try {
    zip = await readZip(file);
    root = JSON.parse(new TextDecoder().decode(await zip.get("manifest.json").read()));
  } catch (e) {
    throw wrong;
  }
  if (!root || root.format !== TRANSFER_FORMAT || !Array.isArray(root.books)) throw wrong;
  if ((root.version || 1) > 1) throw new Error("That library file was made by a newer MyBookSpace. Update this app first.");
  const sameApp = root.app === "pc";
  const ids = new Map(), fresh = [], merges = [];
  let failed = 0, done = 0;
  for (const o of root.books) {
    const have = state.books.find((b) => b.size === Number(o.size) && b.src === o.src);
    if (have) {
      ids.set(o.id, have.id);
      merges.push([have, o]);
    } else {
      const book = fromManifest(o, sameApp);
      let id = book.id;
      while (!id || find(id) || fresh.some((b) => b.id === id)) id = Date.now().toString(36) + Math.floor(Math.random() * 46656).toString(36);
      ids.set(book.id, id);
      book.id = id;
      const entry = zip.get(o.file);
      let doc = null;
      try {
        if (!entry) throw new Error("missing");
        const blob = await entry.blob();
        book.kind = await sniff(blob);
        doc = await openBook(blob, book.kind, book.textSize);
        fitPages(book, doc.pages);
        const cover = o.cover && zip.get(o.cover) ? await zip.get(o.cover).blob("image/jpeg") : null;
        if (!cover) book.ownCover = false;
        await putFile("book:" + book.id, blob);
        await putFile("cover:" + book.id, cover || (await firstCover(book, doc)));
        fresh.push(book);
      } catch (e) {
        // One unreadable book is left out; the rest of the library still comes over.
        console.error(e);
        failed++;
        await delFile("book:" + book.id);
        await delFile("cover:" + book.id);
      } finally {
        if (doc) doc.close();
      }
    }
    if (progress) progress(++done, root.books.length);
  }
  // New books go to the front, in the order they had on the other device.
  state.books.unshift(...fresh);
  for (const [have, o] of merges) mergeInto(have, o);
  for (const b of fresh) {
    // A second version stays tucked under its book only if it came over in this file too.
    const partner = b.partner ? ids.get(b.partner) : null;
    b.partner = partner && partner !== b.id && fresh.some((o) => o.id === partner) ? partner : "";
  }
  for (const b of fresh) if (b.hidden && !state.books.some((o) => o.partner === b.id)) b.hidden = false;
  for (const s of [...(Array.isArray(root.shelves) ? root.shelves : []), ...state.books.map((b) => b.shelf)]) {
    const n = String(s || "").trim();
    if (n && !state.shelves.includes(n) && state.books.some((b) => b.shelf === n)) state.shelves.push(n);
  }
  // The reading log keeps the larger figure for each day, so importing twice adds nothing.
  for (const [day, sec] of Object.entries(root.daily || {})) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(day)) state.daily[day] = Math.max(state.daily[day] || 0, Number(sec) || 0);
  }
  await save();
  return { added: fresh.length, updated: merges.length, failed };
}
