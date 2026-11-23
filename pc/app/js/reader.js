// The reading screen: one page (or a two-page spread in a wide window), turned by clicking,
// dragging, the keyboard or the mouse wheel, with the settings on a sheet that stays out of the way.
import { h, clear, toast, dialog, menu, ask, dialogOpen, minutes, hex } from "./ui.js";
import * as store from "./store.js";
import { openBook, TEXT_SIZES } from "./docs.js";
import { THEME_NAMES, THEME_BG, THEME_FG, isDark, tint, matrix, MARK_COLORS } from "./themes.js";

const AUTO_CHOICES = [0, 15, 30, 45, 60, 90, 120];
const SLEEP_CHOICES = [0, 15, 30, 45, 60];
const ANIM_MS = 340;

let root = null, onClose = null;
let book = null, doc = null, ready = false;
/** Bumped whenever the pages must be laid out again; work started before then is thrown away. */
let generation = 0;
let L = { W: 1, H: 1, dual: false };
let screen = 0;
const cache = new Map();
let busy = false, queued = 0, drag = null, ptr = null;
let zoom = 1, panX = 0, panY = 0, sharpenTimer = 0;
let menuVisible = false, marking = false, lastMark = null;
let autoSec = 0, autoTimer = 0, sleepMin = 0, sleepTimer = 0, sleeping = false;
let lastTurnAt = 0, lastCredit = 0, lastInput = 0, saveTimer = 0, tickTimer = 0, resizeTimer = 0;
let holdTimer = 0, riffleTimer = 0, wheelAcc = 0, lastWheelTurn = 0, thumbSeq = 0;
let audio = null, pageSound = null, battery = null;
const el = {};

const prefs = () => store.prefs();
const now = () => performance.now();
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

// ---------------------------------------------------------------- building the screen once

export function init(container, closed) {
  root = container;
  onClose = closed;
  root.append(h("div", { html:
    '<svg width="0" height="0" style="position:absolute"><filter id="themeFilter" color-interpolation-filters="sRGB">' +
    '<feColorMatrix id="themeMatrix" type="matrix" values="1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 1 0"/></filter></svg>' }));

  el.stage = h("div", { class: "stage" });
  el.flap = h("div", { class: "flap" });
  el.shade = h("div", { class: "curl-shade" });
  el.stage.append(el.flap, el.shade);
  el.rulerTop = h("div", { class: "ruler-dim" });
  el.rulerBottom = h("div", { class: "ruler-dim" });
  el.footLeft = h("span");
  el.footMid = h("span", { class: "grow center" });
  el.footRight = h("span");
  el.footer = h("div", { class: "footer" }, el.footLeft, el.footMid, el.footRight);
  el.dim = h("div", { class: "dim-layer" });
  el.loading = h("div", { class: "loading", text: "Opening…" });
  el.thumb = h("div", { class: "thumb" });
  el.sleep = h("div", { class: "sleep", text: "Sleep timer ended\n\nClick to keep reading", onclick: () => {
    sleeping = false;
    el.sleep.classList.remove("show");
    lastTurnAt = lastCredit = now();
  } });
  el.confetti = h("canvas", { class: "confetti" });
  root.append(el.stage, el.rulerTop, el.rulerBottom, el.footer, el.dim, el.loading);
  buildMenu();
  root.append(el.thumb, el.sleep, el.confetti);

  el.stage.addEventListener("pointerdown", pointerDown);
  el.stage.addEventListener("pointermove", pointerMove);
  el.stage.addEventListener("pointerup", pointerUp);
  el.stage.addEventListener("pointercancel", pointerUp);
  el.stage.addEventListener("dblclick", doubleClick);
  el.stage.addEventListener("wheel", wheel, { passive: false });
  el.stage.addEventListener("contextmenu", (e) => e.preventDefault());
  window.addEventListener("resize", () => {
    if (!book) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(relayout, 120);
  });
  window.addEventListener("blur", () => book && credit());
  if (navigator.getBattery) {
    navigator.getBattery().then((b) => {
      battery = b;
      b.addEventListener("levelchange", updateStatus);
    }).catch(() => {});
  }
}

function buildMenu() {
  // ---- top bar: back, title, marks list, bookmark
  el.title = h("div", { class: "bar-title grow" });
  el.star = h("button", { class: "icon star", title: "Bookmark this page (B)", text: "☆", onclick: toggleBookmark });
  el.topbar = h("div", { class: "topbar" },
    h("button", { class: "icon", title: "Back to the library (Esc)", text: "←", onclick: close }),
    el.title,
    h("button", { class: "icon", title: "Bookmarks and notes", text: "☰", onclick: bookmarksDialog }),
    el.star,
    window.desktop ? h("button", { class: "icon", title: "Full screen (F11)", text: "⛶", onclick: () => window.desktop.toggleFullscreen() }) : null);

  // ---- bottom sheet
  el.pageLabel = h("div", { class: "page-label grow" });
  el.pageSeek = h("input", { type: "range", class: "seek", min: 0, max: 0, step: 1 });
  el.pageSeek.addEventListener("input", () => {
    const page = Number(el.pageSeek.value);
    el.pageLabel.textContent = pageText(page);
    showThumb(page);
  });
  el.pageSeek.addEventListener("change", () => {
    el.thumb.classList.remove("show");
    goToPage(Number(el.pageSeek.value));
  });

  const names = ["Display", "Layout", "Tools", "More"];
  el.tabs = names.map((n, i) => h("button", { class: "tab", text: n, onclick: () => selectTab(i) }));
  el.panels = names.map(() => h("div", { class: "panel" }));
  const [display, layoutTab, tools, more] = el.panels;

  // ---- Display: theme, text size, page turn, brightness, warmth
  el.swatches = THEME_NAMES.map((name, i) => h("button", { class: "swatch", text: "Aa", title: name + " theme",
    style: { background: hex(THEME_BG[i]), color: hex(THEME_FG[i]) },
    onclick: () => {
      store.setPref("theme", i);
      applyTheme();
    } }));
  display.append(h("div", { class: "row center-row" }, el.swatches));
  el.textLabel = h("span", { class: "grow center" });
  el.textRow = h("div", { class: "row" },
    h("span", { class: "label", text: "Text size" }),
    h("button", { class: "pill grow", text: "A  −", onclick: () => changeTextSize(-1) }),
    el.textLabel,
    h("button", { class: "pill grow larger", text: "A  +", onclick: () => changeTextSize(1) }));
  display.append(el.textRow);
  el.animBtns = ["Curl", "Slide", "None"].map((n, i) => h("button", { class: "pill grow", text: n, onclick: () => {
    store.setPref("anim", i);
    refreshMenu();
  } }));
  display.append(h("div", { class: "row" }, h("span", { class: "label", text: "Page turn" }), el.animBtns));
  el.bright = h("input", { type: "range", class: "seek grow", min: 20, max: 100 });
  el.bright.addEventListener("input", () => {
    store.setPref("bright", Number(el.bright.value) / 100);
    applyBrightness();
  });
  display.append(h("div", { class: "row" }, h("span", { class: "label", text: "Brightness" }), el.bright));
  el.autoBright = toggle("Use the screen's own brightness", (on) => {
    store.setPref("bright", on ? -1 : Number(el.bright.value) / 100);
    refreshMenu();
    applyBrightness();
  });
  display.append(el.autoBright.row);
  el.warm = h("input", { type: "range", class: "seek grow", min: 0, max: 100 });
  el.warm.addEventListener("input", () => {
    store.setPref("warmth", Number(el.warm.value) / 100);
    applyTheme();
  });
  display.append(h("div", { class: "row" }, h("span", { class: "label", text: "Warmth" }), el.warm));

  // ---- Layout
  el.crop = toggle("Crop margins", (on) => {
    store.setPref("crop", on);
    if (on) ensureCrop();
    relayout();
  });
  el.dual = toggle("Two pages side by side in a wide window", (on) => {
    store.setPref("dual", on);
    relayout();
  });
  el.clock = toggle("Clock and battery in the footer", (on) => {
    store.setPref("clock", on);
    updateStatus();
  });
  layoutTab.append(el.crop.row, el.dual.row, el.clock.row);
  if (window.desktop) {
    layoutTab.append(h("div", { class: "row" }, h("button", { class: "pill grow", text: "Full screen  (F11)", onclick: () => window.desktop.toggleFullscreen() })));
  }

  // ---- Tools
  el.rulerBtn = h("button", { class: "pill grow", onclick: () => {
    store.setPref("rulerOn", !prefs().rulerOn);
    refreshMenu();
    applyRuler();
    if (prefs().rulerOn) toast("Drag up or down on the page to move the ruler");
  } });
  el.markBtn = h("button", { class: "pill grow", onclick: () => setMarking(!marking) });
  el.noteBtn = h("button", { class: "pill grow", onclick: noteDialog });
  el.dictBtn = h("button", { class: "pill grow", text: "Dictionary", onclick: () => dictionaryDialog("") });
  el.rulerSize = h("input", { type: "range", class: "seek grow", min: 40, max: 360 });
  el.rulerSize.addEventListener("input", () => {
    store.setPref("rulerBand", Number(el.rulerSize.value));
    applyRuler();
  });
  tools.append(h("div", { class: "row" }, el.rulerBtn, el.markBtn), h("div", { class: "row" }, el.noteBtn, el.dictBtn),
    h("div", { class: "row" }, h("span", { class: "label", text: "Ruler size" }), el.rulerSize));

  // ---- More
  el.autoBtn = h("button", { class: "pill grow", onclick: () => setAuto(next(AUTO_CHOICES, autoSec)) });
  el.sleepBtn = h("button", { class: "pill grow", onclick: () => setSleep(next(SLEEP_CHOICES, sleepMin)) });
  el.sound = toggle("Page-turn sound", (on) => {
    store.setPref("sound", on);
    if (on) playSound();
  });
  el.night = toggle("Night theme from 9 pm to 6 am", (on) => {
    store.setPref("autoNight", on);
    applyTheme();
  });
  more.append(h("div", { class: "row" }, el.autoBtn, el.sleepBtn), el.sound.row, el.night.row,
    h("div", { class: "row" }, h("button", { class: "pill grow", text: "Keyboard and mouse shortcuts", onclick: shortcutsDialog })));

  el.sheet = h("div", { class: "sheet" },
    h("div", { class: "handle" }),
    h("div", { class: "row" }, el.pageLabel, h("button", { class: "pill", text: "Go to page", onclick: goToDialog })),
    el.pageSeek,
    h("div", { class: "tabs" }, el.tabs),
    el.panels);

  // Shown while highlighting: pick a colour, take back the last highlight, or finish.
  el.markDots = MARK_COLORS.map((c, i) => h("button", { class: "mark-dot", style: { background: c.replace("0.40", "1") }, onclick: () => {
    store.setPref("markColor", i);
    refreshMenu();
  } }));
  el.markbar = h("div", { class: "markbar" }, el.markDots,
    h("button", { class: "pill", text: "Undo", onclick: undoMark }),
    h("button", { class: "pill accent", text: "Done", onclick: () => setMarking(false) }));

  root.append(el.topbar, el.sheet, el.markbar);
}

function toggle(label, onChange) {
  const input = h("input", { type: "checkbox" });
  input.addEventListener("change", () => onChange(input.checked));
  return { input, row: h("label", { class: "row switch" }, h("span", { class: "grow", text: label }), input, h("i")) };
}

function next(choices, current) {
  return choices[(choices.indexOf(current) + 1) % choices.length];
}

function selectTab(i) {
  store.setPref("menuTab", i);
  el.tabs.forEach((t, k) => t.classList.toggle("on", k === i));
  el.panels.forEach((p, k) => p.classList.toggle("on", k === i));
}

// ---------------------------------------------------------------- opening and closing

export async function open(b) {
  book = b;
  ready = false;
  generation++;
  screen = 0;
  zoom = 1;
  panX = panY = 0;
  marking = false;
  menuVisible = false;
  autoSec = sleepMin = 0;
  sleeping = false;
  root.hidden = false;
  el.loading.classList.add("show");
  el.title.textContent = b.title;
  applyTheme();
  applyBrightness();
  showMenu(false);
  try {
    const blob = await store.bookFile(b);
    if (!blob) throw new Error("That book's file is missing from the library");
    doc = await openBook(blob, b.kind, b.textSize);
  } catch (e) {
    console.error(e);
    toast(e.message || "That book couldn't be opened");
    el.loading.classList.remove("show");
    root.hidden = true;
    book = null;
    onClose();
    return;
  }
  if (b.kind !== "pdf" && doc.pages !== b.pages) remapPages(doc.pages);
  b.pages = doc.pages;
  b.page = clamp(b.page, 0, b.pages - 1);
  b.opened = Date.now();
  store.save();
  ready = true;
  lastTurnAt = lastCredit = lastInput = now();
  el.pageSeek.max = b.pages - 1;
  el.textRow.hidden = b.kind === "pdf";
  el.crop.row.hidden = b.kind !== "pdf";
  await relayout();
  el.loading.classList.remove("show");
  if (prefs().crop) ensureCrop();
  clearInterval(tickTimer);
  tickTimer = setInterval(tick, 30000);
  if (window.desktop) {
    window.desktop.setTitle(b.title + " — MyBookSpace");
    window.desktop.keepAwake(true);
  }
  if (!prefs().guideShown) {
    store.setPref("guideShown", true);
    showGuide();
  }
}

export function isOpen() {
  return book !== null;
}

export function close() {
  if (!book) return;
  credit();
  clearTimeout(saveTimer);
  clearInterval(tickTimer);
  clearTimeout(autoTimer);
  clearTimeout(sleepTimer);
  clearInterval(riffleTimer);
  generation++;
  dropScreens();
  if (doc) doc.close();
  doc = null;
  book = null;
  ready = false;
  store.save();
  root.hidden = true;
  el.thumb.classList.remove("show");
  el.sleep.classList.remove("show");
  if (window.desktop) {
    window.desktop.setTitle("MyBookSpace");
    window.desktop.keepAwake(false);
  }
  onClose();
}

/** Shown once: a map of where to click, laid over the page. Any click dismisses it. */
function showGuide() {
  const guide = h("div", { class: "guide", onclick: () => guide.remove() },
    h("div", { class: "zone", text: "‹\n\nClick, drag or press ←\nfor the previous page" }),
    h("div", { class: "middle", text: "Click the middle for settings\n\nHold an edge to flip quickly\n\nCtrl + scroll, or double-click, to zoom\n\nScroll the wheel to turn pages\n\n\nClick anywhere to start reading" }),
    h("div", { class: "zone", text: "›\n\nClick, drag or press →\nfor the next page" }));
  root.append(guide);
}

// ---------------------------------------------------------------- layout

const screens = () => (L.dual ? Math.floor(book.pages / 2) + 1 : book.pages);
const screenOf = (page) => (L.dual ? Math.floor((page + 1) / 2) : page);
const firstPage = (s) => (L.dual ? (s === 0 ? 0 : 2 * s - 1) : s);
const pagesOn = (s) => (L.dual && s > 0 && 2 * s < book.pages ? 2 : 1);

function cropFor(i) {
  const c = book.crop;
  if (!prefs().crop || book.kind !== "pdf" || !c) return null;
  if (i === 0 && !c.cover) return null;
  return i % 2 === 0 ? c.even : c.odd;
}

async function ensureCrop() {
  if (!book || book.kind !== "pdf" || book.crop) return;
  const b = book, d = doc;
  try {
    const crop = await d.computeCrop();
    if (book !== b) return;
    b.crop = crop;
    store.save();
    if (crop.even || crop.odd) relayout();
  } catch (e) {
    // No crop is a safe fallback.
  }
}

function dropScreens() {
  for (const e of cache.values()) {
    e.cancel();
    e.el.remove();
  }
  cache.clear();
  resetTurnStyles();
}

async function relayout() {
  if (!book || !ready) return;
  generation++;
  const gen = generation;
  dropScreens();
  drag = null;
  busy = false;
  zoom = 1;
  panX = panY = 0;
  L = { W: el.stage.clientWidth, H: el.stage.clientHeight, dual: false };
  L.dual = prefs().dual && L.W >= L.H * 1.25 && book.pages > 1;
  screen = clamp(screenOf(book.page), 0, screens() - 1);
  const e = entry(screen);
  await e.built;
  if (gen !== generation) return;
  el.stage.append(e.el);
  preload();
  refreshMenu();
  updateStatus();
  applyRuler();
}

/** Builds (or returns) the element for one screen: its pages placed, and their drawing started. */
function entry(s, quality = 1) {
  let e = cache.get(s);
  if (e && e.quality >= quality) return e;
  const gen = generation;
  const node = h("div", { class: "screen" });
  const cancels = [];
  let drawn;
  const built = (async () => {
    const first = firstPage(s), n = pagesOn(s), waits = [];
    for (let k = 0; k < n; k++) {
      const i = first + k, crop = cropFor(i);
      let a = await doc.aspect(i);
      if (gen !== generation) return;
      if (crop) a *= (crop[2] - crop[0]) / (crop[3] - crop[1]);
      // A cropped page has no margin of its own left, so it is kept off the window's edges
      // and, in a spread, off its neighbour.
      const pad = crop ? 18 : 0, gutter = crop && L.dual ? 22 : 0;
      const slotW = (L.dual ? L.W / 2 - gutter : L.W) - pad;
      const w = Math.floor(Math.min(slotW, (L.H - 2 * pad) * a)), hgt = Math.floor(w / a);
      // In a spread the two pages meet at the spine; a page on its own sits in the middle.
      const left = !L.dual || s === 0 ? (L.W - w) / 2 : k === 0 ? L.W / 2 - gutter - w : L.W / 2 + gutter;
      const page = h("div", { class: "page", style: { left: Math.round(left) + "px", top: Math.round((L.H - hgt) / 2) + "px", width: w + "px", height: hgt + "px" } });
      page.dataset.page = i;
      if (book.kind === "pdf") {
        const job = doc.draw(i, Math.round(w * (window.devicePixelRatio || 1) * quality), crop);
        job.canvas.className = "ink";
        page.append(job.canvas);
        waits.push(job.done.catch(() => {}));
        cancels.push(job.cancel);
      } else {
        page.append(doc.element(i, w));
      }
      page.append(h("div", { class: "marks" }));
      node.append(page);
      drawMarks(page);
    }
    drawn = Promise.all(waits);
  })();
  e = { s, el: node, quality, built, drawn: built.then(() => drawn), cancel: () => cancels.forEach((c) => c()) };
  if (cache.has(s)) cache.get(s).cancel();
  cache.set(s, e);
  return e;
}

function preload() {
  for (const s of [screen + 1, screen - 1]) if (s >= 0 && s < screens()) entry(s);
  for (const [s, e] of cache) {
    if (Math.abs(s - screen) > 1) {
      e.cancel();
      e.el.remove();
      cache.delete(s);
    }
  }
}

/** Highlights, the bookmark ribbon and the note marker of one page. */
function drawMarks(page) {
  const i = Number(page.dataset.page), layer = page.querySelector(".marks");
  clear(layer);
  for (const r of book.highlights[i] || []) {
    layer.append(h("i", { class: "hl", style: {
      left: r[0] * 100 + "%", top: r[1] * 100 + "%", width: (r[2] - r[0]) * 100 + "%", height: (r[3] - r[1]) * 100 + "%",
      background: MARK_COLORS[r[4] || 0] } }));
  }
  if (book.bookmarks.includes(i)) layer.append(h("i", { class: "ribbon" }));
  if (book.notes[i]) layer.append(h("i", { class: "note-dot", title: book.notes[i] }));
}

function redrawMarks() {
  const e = cache.get(screen);
  if (e) e.el.querySelectorAll(".page").forEach(drawMarks);
}

// ---------------------------------------------------------------- turning pages

function resetTurnStyles() {
  el.flap.style.display = "none";
  el.shade.style.display = "none";
}

function clean(node) {
  node.style.clipPath = "";
  node.style.transform = "";
  node.style.zIndex = "";
  node.style.boxShadow = "";
}

/** Places the two screens for a turn that is t (0..1) of the way done. */
function place(from, to, dir, t, mode) {
  const W = L.W;
  if (to.el.parentNode !== el.stage) el.stage.append(to.el);
  if (mode === 1) {
    // Slide: the page on top slides away to the left, or back in from the left.
    const top = dir === 1 ? from : to, under = dir === 1 ? to : from;
    const x = dir === 1 ? -t * W : -(1 - t) * W;
    top.el.style.zIndex = 2;
    under.el.style.zIndex = 1;
    top.el.style.transform = `translateX(${x}px)`;
    top.el.style.boxShadow = "18px 0 40px rgba(0,0,0,.28)";
    return;
  }
  // Curl: the page's edge is lifted and carried across; behind the fold is the page's back.
  const top = dir === 1 ? from : to, under = dir === 1 ? to : from;
  const p = dir === 1 ? W - 2 * W * t : -W + 2 * W * t;
  const f = (p + W) / 2;
  top.el.style.zIndex = 2;
  under.el.style.zIndex = 1;
  top.el.style.clipPath = `inset(0 ${Math.max(0, W - f)}px 0 0)`;
  el.flap.style.display = f - p > 0.5 ? "block" : "none";
  el.flap.style.left = p + "px";
  el.flap.style.width = Math.max(0, f - p) + "px";
  el.shade.style.display = "block";
  el.shade.style.left = f + "px";
  el.shade.style.width = Math.min(70, Math.max(0, W - f)) + "px";
}

function animate(from, to, dir, mode, t0, t1) {
  return new Promise((done) => {
    const start = now(), ms = ANIM_MS * Math.max(0.25, Math.abs(t1 - t0));
    const step = () => {
      const k = Math.min(1, (now() - start) / ms);
      const eased = 1 - Math.pow(1 - k, 3);
      place(from, to, dir, t0 + (t1 - t0) * eased, mode);
      if (k < 1) requestAnimationFrame(step);
      else done();
    };
    step();
  });
}

function settle(shown, hidden) {
  resetTurnStyles();
  clean(shown.el);
  clean(hidden.el);
  hidden.el.remove();
  if (shown.el.parentNode !== el.stage) el.stage.append(shown.el);
}

export async function turn(dir, animated = true) {
  if (!ready || drag) return;
  const target = screen + dir;
  if (target < 0 || target >= screens()) return;
  if (busy) {
    queued = dir;
    return;
  }
  busy = true;
  const gen = generation;
  if (zoom !== 1) setZoom(1, 0, 0);
  const from = cache.get(screen), to = entry(target);
  await to.built;
  const mode = animated ? prefs().anim : 2;
  if (mode !== 2) await Promise.race([to.drawn, sleep(450)]);
  if (gen !== generation) return;
  if (mode !== 2 && from) await animate(from, to, dir, mode, 0, 1);
  if (gen !== generation) return;
  if (from) settle(to, from);
  else el.stage.append(to.el);
  commit(target, dir);
  busy = false;
  if (queued) {
    const q = queued;
    queued = 0;
    turn(q, false);
  }
}

function commit(target, dir) {
  screen = target;
  preload();
  pageChanged(dir);
}

async function goToPage(page) {
  if (!ready) return;
  const target = clamp(screenOf(clamp(page, 0, book.pages - 1)), 0, screens() - 1);
  if (target === screen) return;
  const gen = generation;
  if (zoom !== 1) setZoom(1, 0, 0);
  const from = cache.get(screen), to = entry(target);
  await to.built;
  if (gen !== generation) return;
  if (from) from.el.remove();
  el.stage.append(to.el);
  commit(target, 0);
}

function pageChanged(dir) {
  const t = now();
  if (dir === 1) {
    // Only plausible reading times feed the estimate: not skimming, not a coffee break.
    const sec = (t - lastTurnAt) / 1000;
    if (sec >= 8 && sec <= 600) {
      const per = sec / pagesOn(screen);
      book.secPerPage = book.samples === 0 ? per : book.secPerPage * 0.85 + per * 0.15;
      book.samples++;
    }
  }
  lastTurnAt = t;
  credit();
  book.page = firstPage(screen);
  if (dir === 1 && !book.celebrated && book.page + pagesOn(screen) >= book.pages) {
    book.celebrated = true;
    celebrate();
  }
  if (dir !== 0 && prefs().sound) playSound();
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => store.save(), 1500);
  updateStatus();
  refreshMenu();
  armAuto();
}

/** Adds the time since the last credit to the reading log, unless the reader has wandered off. */
function credit() {
  const t = now();
  const idle = (t - lastInput) / 1000;
  const sec = Math.round((t - lastCredit) / 1000);
  lastCredit = t;
  if (!book || sleeping || sec <= 0 || idle > 600) return;
  store.addReading(book, Math.min(sec, 600));
}

function tick() {
  if (!book) return;
  if (document.hasFocus()) credit();
  else lastCredit = now();
  store.save();
  updateStatus();
  applyTheme();
}

// ---------------------------------------------------------------- pointer, wheel, keys

function zone(x) {
  const r = el.stage.getBoundingClientRect(), f = (x - r.left) / r.width;
  return f < 0.28 ? -1 : f > 0.72 ? 1 : 0;
}

function pointerDown(e) {
  if (e.button !== 0 || !ready) return;
  lastInput = now();
  ptr = { x0: e.clientX, y0: e.clientY, moved: false, mode: null, panX, panY, held: false };
  el.stage.setPointerCapture(e.pointerId);
  if (marking) {
    startMark(e);
    return;
  }
  const side = zone(e.clientX);
  clearTimeout(holdTimer);
  if (side !== 0 && !menuVisible && zoom === 1) {
    // Holding an edge riffles through the pages.
    holdTimer = setTimeout(() => {
      if (!ptr || ptr.moved) return;
      ptr.held = true;
      riffleTimer = setInterval(() => turn(side, false), 110);
    }, 480);
  }
}

function pointerMove(e) {
  if (!ptr) return;
  const dx = e.clientX - ptr.x0, dy = e.clientY - ptr.y0;
  if (ptr.mode === "mark") return moveMark(e);
  if (!ptr.mode) {
    if (Math.hypot(dx, dy) < 8 || ptr.held) return;
    ptr.moved = true;
    clearTimeout(holdTimer);
    if (zoom > 1) ptr.mode = "pan";
    else if (prefs().rulerOn && Math.abs(dy) > Math.abs(dx)) ptr.mode = "ruler";
    else if (Math.abs(dx) > Math.abs(dy) && !busy && prefs().anim !== 2) {
      const dir = dx < 0 ? 1 : -1, target = screen + dir;
      if (target >= 0 && target < screens() && cache.get(screen)) {
        ptr.mode = "turn";
        drag = { dir, from: cache.get(screen), to: entry(target), t: 0, gen: generation, lastX: e.clientX, lastT: now(), speed: 0 };
      } else {
        ptr.mode = "none";
      }
    } else {
      ptr.mode = "none";
    }
  }
  if (ptr.mode === "pan") {
    panX = ptr.panX + dx;
    panY = ptr.panY + dy;
    applyZoom();
  } else if (ptr.mode === "ruler") {
    const r = el.stage.getBoundingClientRect();
    store.prefs().rulerY = clamp((e.clientY - r.top) / r.height, 0.03, 0.97);
    applyRuler();
  } else if (ptr.mode === "turn" && drag) {
    const t = now();
    drag.speed = (e.clientX - drag.lastX) / Math.max(1, t - drag.lastT);
    drag.lastX = e.clientX;
    drag.lastT = t;
    drag.t = clamp((drag.dir === 1 ? -dx : dx) / L.W, 0, 1);
    if (drag.to.el.childNodes.length) place(drag.from, drag.to, drag.dir, drag.t, prefs().anim);
  }
}

async function pointerUp(e) {
  if (!ptr) return;
  const p = ptr;
  ptr = null;
  clearTimeout(holdTimer);
  clearInterval(riffleTimer);
  if (p.mode === "mark") {
    if (p.mark) finishMark(p.mark);
    return;
  }
  if (p.mode === "ruler") return store.save();
  if (p.mode === "turn" && drag) {
    const d = drag, mode = prefs().anim;
    // A quick flick turns the page even if it was not dragged far.
    const flick = d.dir === 1 ? d.speed < -0.5 : d.speed > 0.5;
    const finish = d.t > 0.3 || flick;
    busy = true;
    await d.to.built;
    if (d.gen !== generation) {
      drag = null;
      busy = false;
      return;
    }
    await animate(d.from, d.to, d.dir, mode, d.t, finish ? 1 : 0);
    drag = null;
    busy = false;
    if (d.gen !== generation) return;
    if (finish) {
      settle(d.to, d.from);
      commit(screen + d.dir, d.dir);
    } else {
      settle(d.from, d.to);
    }
    return;
  }
  if (p.moved || p.held || e.type === "pointercancel") return;
  if (menuVisible) return showMenu(false);
  const side = zone(e.clientX);
  if (side !== 0 && zoom === 1) turn(side);
  else showMenu(true);
}

function doubleClick(e) {
  if (!ready || marking || zone(e.clientX) !== 0) return;
  const r = el.stage.getBoundingClientRect();
  if (zoom === 1) setZoom(2.2, e.clientX - r.left, e.clientY - r.top);
  else setZoom(1, 0, 0);
  showMenu(false);
}

function wheel(e) {
  if (!ready) return;
  e.preventDefault();
  lastInput = now();
  const r = el.stage.getBoundingClientRect();
  if (e.ctrlKey) {
    setZoom(zoom * Math.exp(-e.deltaY * 0.0022), e.clientX - r.left, e.clientY - r.top);
    return;
  }
  if (zoom > 1) {
    panX -= e.deltaX;
    panY -= e.deltaY;
    applyZoom();
    return;
  }
  if (menuVisible) return;
  wheelAcc += e.deltaY + e.deltaX;
  if (Math.abs(wheelAcc) >= 60 && now() - lastWheelTurn > 160) {
    turn(wheelAcc > 0 ? 1 : -1, false);
    wheelAcc = 0;
    lastWheelTurn = now();
  }
}

/** Keyboard shortcuts. Returns true if the key was used. */
export function key(e) {
  if (!book || dialogOpen()) return false;
  if (["INPUT", "TEXTAREA"].includes(e.target.tagName) && e.target.type !== "range" && e.target.type !== "checkbox") return false;
  lastInput = now();
  if (sleeping) return false;
  const k = e.key;
  if (k === "Escape") {
    if (marking) setMarking(false);
    else if (zoom !== 1) setZoom(1, 0, 0);
    else if (menuVisible) showMenu(false);
    else close();
  } else if (k === "ArrowRight" || k === "PageDown" || (k === " " && !e.shiftKey) || k === "ArrowDown") turn(1, !e.repeat);
  else if (k === "ArrowLeft" || k === "PageUp" || (k === " " && e.shiftKey) || k === "ArrowUp") turn(-1, !e.repeat);
  else if (k === "Home") goToPage(0);
  else if (k === "End") goToPage(book.pages - 1);
  else if (k === "b" || k === "B") toggleBookmark();
  else if (k === "g" || k === "G") goToDialog();
  else if (k === "n" || k === "N") noteDialog();
  else if (k === "d" || k === "D") dictionaryDialog("");
  else if (k === "h" || k === "H") setMarking(!marking);
  else if (k === "r" || k === "R") el.rulerBtn.click();
  else if (k === "m" || k === "M" || k === "Enter") showMenu(!menuVisible);
  else if (k === "+" || k === "=") setZoom(zoom * 1.25, L.W / 2, L.H / 2);
  else if (k === "-") setZoom(zoom / 1.25, L.W / 2, L.H / 2);
  else if (k === "0") setZoom(1, 0, 0);
  else if (k === "F11" || k === "f" || k === "F") {
    if (window.desktop) window.desktop.toggleFullscreen();
    else if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen();
  } else return false;
  return true;
}

// ---------------------------------------------------------------- zoom

function applyZoom() {
  const e = cache.get(screen);
  if (!e) return;
  panX = clamp(panX, L.W - L.W * zoom, 0);
  panY = clamp(panY, L.H - L.H * zoom, 0);
  e.el.style.transformOrigin = "0 0";
  e.el.style.transform = zoom === 1 ? "" : `translate(${panX}px, ${panY}px) scale(${zoom})`;
  el.stage.classList.toggle("zoomed", zoom > 1);
}

function setZoom(z, cx, cy) {
  z = clamp(z, 1, 5);
  if (Math.abs(z - 1) < 0.04) z = 1;
  const k = z / zoom;
  panX = cx - (cx - panX) * k;
  panY = cy - (cy - panY) * k;
  zoom = z;
  if (zoom === 1) panX = panY = 0;
  applyZoom();
  clearTimeout(sharpenTimer);
  if (zoom > 1 && book.kind === "pdf") sharpenTimer = setTimeout(sharpen, 260);
}

/** After zooming in, the page is drawn again at the new size so the text is sharp. */
async function sharpen() {
  const old = cache.get(screen), gen = generation, s = screen;
  if (!old || old.quality >= zoom || zoom === 1) return;
  cache.delete(s);
  const fresh = entry(s, Math.min(zoom, 4));
  cache.set(s, old);
  await fresh.drawn;
  if (gen !== generation || screen !== s || cache.get(s) !== old) {
    fresh.cancel();
    return;
  }
  cache.set(s, fresh);
  old.el.replaceWith(fresh.el);
  applyZoom();
}

// ---------------------------------------------------------------- menu, status

function pageText(page) {
  return `Page ${page + 1} of ${book.pages}   ·   ${Math.floor(((page + 1) * 100) / book.pages)}%`;
}

function showMenu(on) {
  menuVisible = on && !marking;
  root.classList.toggle("menu", menuVisible);
  if (!menuVisible) el.thumb.classList.remove("show");
  if (menuVisible) refreshMenu();
}

function refreshMenu() {
  if (!book) return;
  const p = prefs();
  el.title.textContent = book.title;
  el.pageLabel.textContent = pageText(book.page);
  el.pageSeek.max = book.pages - 1;
  el.pageSeek.value = book.page;
  el.star.textContent = book.bookmarks.includes(book.page) ? "★" : "☆";
  el.star.classList.toggle("on", book.bookmarks.includes(book.page));
  el.animBtns.forEach((b, i) => b.classList.toggle("on", i === p.anim));
  el.bright.value = p.bright < 0 ? 100 : Math.round(p.bright * 100);
  el.bright.disabled = p.bright < 0;
  el.autoBright.input.checked = p.bright < 0;
  el.warm.value = Math.round(p.warmth * 100);
  el.crop.input.checked = p.crop;
  el.dual.input.checked = p.dual;
  el.clock.input.checked = p.clock;
  el.sound.input.checked = p.sound;
  el.night.input.checked = p.autoNight;
  el.textLabel.textContent = book.textSize + " px";
  el.rulerBtn.textContent = p.rulerOn ? "Reading ruler: on" : "Reading ruler";
  el.rulerBtn.classList.toggle("on", p.rulerOn);
  el.markBtn.textContent = marking ? "Highlighting…" : "Highlight";
  el.markBtn.classList.toggle("on", marking);
  el.noteBtn.textContent = book.notes[book.page] ? "Edit this page's note" : "Add a note";
  el.rulerSize.value = p.rulerBand;
  el.autoBtn.textContent = autoSec ? `Auto-turn: every ${autoSec} s` : "Auto-turn: off";
  el.autoBtn.classList.toggle("on", autoSec > 0);
  el.sleepBtn.textContent = sleepMin ? `Sleep timer: ${sleepMin} min` : "Sleep timer: off";
  el.sleepBtn.classList.toggle("on", sleepMin > 0);
  el.markDots.forEach((d, i) => d.classList.toggle("on", i === p.markColor));
  const shown = effectiveTheme();
  el.swatches.forEach((s, i) => s.classList.toggle("on", i === shown));
  selectTab(Math.min(el.tabs.length - 1, p.menuTab));
}

function updateStatus() {
  if (!book) return;
  const n = pagesOn(screen), first = firstPage(screen);
  el.footLeft.textContent = n === 2 ? `Pages ${first + 1}–${first + 2} of ${book.pages}` : `Page ${first + 1} of ${book.pages}`;
  const remaining = book.pages - 1 - book.page;
  el.footMid.textContent = book.samples >= 5 && book.secPerPage > 0 && remaining > 0
    ? `about ${minutes(Math.max(60, Math.round(remaining * book.secPerPage)))} left in the book` : "";
  let right = Math.floor(((book.page + 1) * 100) / book.pages) + "%";
  if (prefs().clock) {
    right += "   ·   " + new Date().toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    if (battery) right += "   ·   " + Math.round(battery.level * 100) + "%" + (battery.charging ? " ⚡" : "");
  }
  el.footRight.textContent = right;
}

function effectiveTheme() {
  const p = prefs(), hour = new Date().getHours();
  return p.autoNight && (hour >= 21 || hour < 6) ? 5 : p.theme;
}

function applyTheme() {
  const t = effectiveTheme(), w = prefs().warmth;
  document.getElementById("themeMatrix").setAttribute("values", matrix(t, w));
  root.style.setProperty("--paper", hex(tint(THEME_BG[t], w)));
  root.style.setProperty("--ink", hex(tint(THEME_FG[t], w)));
  root.classList.toggle("dark-page", isDark(t));
  root.classList.toggle("plain", t === 0 && w <= 0);
  if (!root.hidden) document.documentElement.style.background = hex(tint(THEME_BG[t], w));
  if (menuVisible) el.swatches.forEach((s, i) => s.classList.toggle("on", i === t));
}

function applyBrightness() {
  const b = prefs().bright;
  el.dim.style.opacity = b < 0 ? 0 : (1 - b) * 0.9;
}

function applyRuler() {
  const p = prefs(), on = p.rulerOn && book;
  el.rulerTop.style.display = el.rulerBottom.style.display = on ? "block" : "none";
  if (!on) return;
  const H = el.stage.clientHeight, band = Math.min(p.rulerBand, H - 40);
  const y = clamp((p.rulerY ?? 0.4) * H, band / 2, H - band / 2);
  el.rulerTop.style.top = "0";
  el.rulerTop.style.height = y - band / 2 + "px";
  el.rulerBottom.style.top = y + band / 2 + "px";
  el.rulerBottom.style.height = H - (y + band / 2) + "px";
}

async function showThumb(page) {
  const seq = ++thumbSeq;
  const r = el.pageSeek.getBoundingClientRect(), rr = root.getBoundingClientRect();
  const frac = book.pages > 1 ? page / (book.pages - 1) : 0;
  el.thumb.style.left = clamp(r.left - rr.left + frac * r.width - 85, 8, rr.width - 178) + "px";
  el.thumb.style.bottom = rr.bottom - r.top + 14 + "px";
  el.thumb.classList.add("show");
  let node;
  if (book.kind === "pdf") {
    const job = doc.draw(page, 340, cropFor(page));
    node = await job.done.catch(() => null);
    if (node) node.className = "ink";
  } else {
    node = doc.element(page, 170);
  }
  if (seq !== thumbSeq || !node) return;
  clear(el.thumb);
  el.thumb.append(node, h("span", { text: String(page + 1) }));
}

// ---------------------------------------------------------------- bookmarks, notes, highlights

function toggleBookmark() {
  if (!ready) return;
  const i = book.bookmarks.indexOf(book.page);
  if (i >= 0) book.bookmarks.splice(i, 1);
  else {
    book.bookmarks.push(book.page);
    book.bookmarks.sort((a, b) => a - b);
  }
  store.save();
  toast(i >= 0 ? "Bookmark removed" : "Page " + (book.page + 1) + " bookmarked");
  redrawMarks();
  refreshMenu();
}

async function noteDialog() {
  if (!ready) return;
  const page = book.page;
  const text = await ask("Note for page " + (page + 1), book.notes[page] || "", "Save", { multiline: true, hint: "Leave it empty to delete the note" });
  if (text === null) return;
  if (text) book.notes[page] = text;
  else delete book.notes[page];
  store.save();
  redrawMarks();
  refreshMenu();
}

function notesText() {
  const lines = [book.title, ""];
  const pages = [...new Set([...book.bookmarks, ...Object.keys(book.notes).map(Number)])].sort((a, b) => a - b);
  for (const p of pages) lines.push(`Page ${p + 1}${book.bookmarks.includes(p) ? "  (bookmark)" : ""}` + (book.notes[p] ? "\n" + book.notes[p] : ""), "");
  return lines.join("\n").trim() + "\n";
}

function bookmarksDialog() {
  if (!ready) return;
  const pages = [...new Set([...book.bookmarks, ...Object.keys(book.notes).map(Number)])].sort((a, b) => a - b);
  const list = h("div", { class: "menu-list" });
  let api = null;
  if (!pages.length) list.append(h("p", { class: "muted", text: "No bookmarks or notes yet. Press B to bookmark a page, or N to write a note on it." }));
  for (const p of pages) {
    list.append(h("button", { class: "menu-item", onclick: () => {
      api.close();
      showMenu(false);
      goToPage(p);
    } },
      h("b", { text: (book.bookmarks.includes(p) ? "★  " : "") + "Page " + (p + 1) }),
      book.notes[p] ? h("span", { class: "note-text", text: book.notes[p] }) : null));
  }
  const buttons = [{ label: "Close" }];
  if (pages.length) {
    buttons.unshift({ label: "Copy all", value: "copy" }, { label: "Save as a text file", value: "save" });
  }
  dialog({ title: "Bookmarks and notes", body: list, buttons, onShow: (a) => (api = a) }).then(async (v) => {
    if (v === "copy") {
      try {
        await navigator.clipboard.writeText(notesText());
        toast("Copied");
      } catch (e) {
        toast("The clipboard couldn't be used");
      }
    } else if (v === "save") {
      const a = h("a", { href: URL.createObjectURL(new Blob([notesText()], { type: "text/plain" })), download: book.title + " - notes.txt" });
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    }
  });
}

async function goToDialog() {
  if (!ready) return;
  const v = await ask("Go to page", String(book.page + 1), "Go", { type: "number", hint: "1 to " + book.pages });
  const n = parseInt(v, 10);
  if (!n) return;
  showMenu(false);
  goToPage(n - 1);
}

function setMarking(on) {
  marking = on;
  if (on) {
    showMenu(false);
    toast("Drag over a passage to highlight it. Click a highlight to remove it.");
  }
  root.classList.toggle("marking", on);
  refreshMenu();
}

function pageAt(e) {
  const cur = cache.get(screen);
  if (!cur) return null;
  for (const page of cur.el.querySelectorAll(".page")) {
    const r = page.getBoundingClientRect();
    if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) return { page, r };
  }
  return null;
}

function startMark(e) {
  const hit = pageAt(e);
  ptr.mode = "mark";
  if (!hit) return;
  const fx = (e.clientX - hit.r.left) / hit.r.width, fy = (e.clientY - hit.r.top) / hit.r.height;
  const box = h("i", { class: "hl live", style: { background: MARK_COLORS[prefs().markColor] } });
  hit.page.querySelector(".marks").append(box);
  ptr.mark = { ...hit, fx, fy, box, rect: [fx, fy, fx, fy] };
}

function moveMark(e) {
  const m = ptr.mark;
  if (!m) return;
  const fx = clamp((e.clientX - m.r.left) / m.r.width, 0, 1), fy = clamp((e.clientY - m.r.top) / m.r.height, 0, 1);
  m.rect = [Math.min(m.fx, fx), Math.min(m.fy, fy), Math.max(m.fx, fx), Math.max(m.fy, fy)];
  Object.assign(m.box.style, {
    left: m.rect[0] * 100 + "%", top: m.rect[1] * 100 + "%",
    width: (m.rect[2] - m.rect[0]) * 100 + "%", height: (m.rect[3] - m.rect[1]) * 100 + "%" });
}

function finishMark(m) {
  const i = Number(m.page.dataset.page);
  const list = book.highlights[i] || (book.highlights[i] = []);
  const [l, t, r, b] = m.rect;
  if (r - l < 0.012 && b - t < 0.008) {
    // A click, not a drag: remove the highlight under it, if there is one.
    for (let k = list.length - 1; k >= 0; k--) {
      const q = list[k];
      if (m.fx >= q[0] && m.fx <= q[2] && m.fy >= q[1] && m.fy <= q[3]) {
        list.splice(k, 1);
        break;
      }
    }
  } else {
    const rect = [l, t, r, b, prefs().markColor];
    list.push(rect);
    lastMark = { page: i, rect };
  }
  if (!list.length) delete book.highlights[i];
  store.save();
  drawMarks(m.page);
}

function undoMark() {
  if (!lastMark) return;
  const list = book.highlights[lastMark.page] || [];
  const k = list.indexOf(lastMark.rect);
  if (k >= 0) list.splice(k, 1);
  if (!list.length) delete book.highlights[lastMark.page];
  lastMark = null;
  store.save();
  redrawMarks();
}

// ---------------------------------------------------------------- text size (EPUB and text books)

/** Page numbers change with the text size, so everything tied to a page moves to the same point in the book. */
function remapPages(pages) {
  const k = book.pages > 0 ? pages / book.pages : 1;
  const at = (p) => Math.min(pages - 1, Math.round(p * k));
  book.page = at(book.page);
  book.bookmarks = [...new Set(book.bookmarks.map(at))].sort((a, b) => a - b);
  const notes = {};
  for (const [p, text] of Object.entries(book.notes)) {
    const q = at(Number(p));
    notes[q] = notes[q] ? notes[q] + "\n" + text : text;
  }
  book.notes = notes;
  // Highlights mark an area of a page and cannot be carried over.
  book.highlights = {};
  book.pages = pages;
  book.secPerPage = 0;
  book.samples = 0;
}

async function changeTextSize(step) {
  if (!ready || book.kind === "pdf") return;
  const i = TEXT_SIZES.indexOf(book.textSize), to = TEXT_SIZES[clamp((i < 0 ? 5 : i) + step, 0, TEXT_SIZES.length - 1)];
  if (to === book.textSize) return;
  ready = false;
  el.loading.classList.add("show");
  const pages = await doc.layout(to);
  book.textSize = to;
  remapPages(pages);
  store.save();
  ready = true;
  el.loading.classList.remove("show");
  await relayout();
}

// ---------------------------------------------------------------- dictionary

async function dictionaryDialog(word) {
  const input = h("input", { class: "field", type: "text", placeholder: "Type a word", value: word });
  const out = h("div", { class: "dict" });
  const look = async () => {
    const w = input.value.trim();
    if (!w) return;
    out.textContent = "Looking up…";
    try {
      const res = await fetch("https://api.dictionaryapi.dev/api/v2/entries/en/" + encodeURIComponent(w));
      if (res.status === 404) {
        out.textContent = `No definition found for “${w}”.`;
        return;
      }
      if (!res.ok) throw new Error("status " + res.status);
      const entries = await res.json();
      clear(out);
      for (const entry of entries.slice(0, 2)) {
        out.append(h("div", { class: "dict-word" }, h("b", { text: entry.word }), entry.phonetic ? h("span", { text: "  " + entry.phonetic }) : null));
        for (const m of (entry.meanings || []).slice(0, 4)) {
          out.append(h("div", { class: "dict-part", text: m.partOfSpeech }));
          const ol = h("ol");
          for (const d of (m.definitions || []).slice(0, 3)) {
            ol.append(h("li", null, d.definition, d.example ? h("em", { text: " “" + d.example + "”" }) : null));
          }
          out.append(ol);
        }
      }
    } catch (e) {
      out.textContent = "The dictionary needs an internet connection, and couldn't be reached.";
    }
  };
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.stopPropagation();
      look();
    }
  });
  dialog({
    title: "Dictionary",
    body: h("div", null, h("div", { class: "row" }, input, h("button", { class: "btn primary", text: "Look up", onclick: look })), out),
    onShow: () => input.focus(),
  });
}

function shortcutsDialog() {
  const rows = [
    ["→  ↓  Space  PgDn  or scroll down", "Next page"], ["←  ↑  Shift+Space  PgUp  or scroll up", "Previous page"],
    ["Home / End", "First / last page"], ["G", "Go to a page"], ["B", "Bookmark this page"], ["N", "Note for this page"],
    ["H", "Highlight"], ["R", "Reading ruler"], ["D", "Dictionary"], ["M or Enter", "Show or hide the settings"],
    ["Ctrl + scroll,  +  −  0", "Zoom in, out, reset"], ["F or F11", "Full screen"], ["Esc", "Back"],
  ];
  dialog({ title: "Shortcuts", body: h("table", { class: "keys" }, rows.map(([k, v]) => h("tr", null, h("td", { text: k }), h("td", { text: v })))) });
}

// ---------------------------------------------------------------- auto-turn, sleep timer, sound, finish

function armAuto() {
  clearTimeout(autoTimer);
  if (!autoSec || !book) return;
  autoTimer = setTimeout(() => {
    if (!menuVisible && !sleeping && !marking && !dialogOpen() && document.hasFocus()) {
      lastInput = now();
      turn(1);
    }
    armAuto();
  }, autoSec * 1000);
}

function setAuto(sec) {
  autoSec = sec;
  armAuto();
  refreshMenu();
}

function setSleep(min) {
  sleepMin = min;
  clearTimeout(sleepTimer);
  if (min) {
    sleepTimer = setTimeout(() => {
      credit();
      sleeping = true;
      sleepMin = 0;
      setAuto(0);
      showMenu(false);
      el.sleep.classList.add("show");
    }, min * 60000);
  }
  refreshMenu();
}

/**
 * The soft rustle of a page being turned. There is no recording behind it: the sound is a fifth
 * of a second of noise, filtered so it brightens as the page sweeps over and shaped to swell
 * and die away.
 */
function playSound() {
  try {
    if (!audio) audio = new AudioContext();
    if (!pageSound) {
      const rate = audio.sampleRate, n = Math.floor(rate * 0.21);
      pageSound = audio.createBuffer(1, n, rate);
      const out = pageSound.getChannelData(0);
      let low = 0, prev = 0;
      for (let i = 0; i < n; i++) {
        const t = i / n, white = Math.random() * 2 - 1;
        low += (0.1 + 0.5 * t) * (white - low);
        const paper = low - prev * 0.86;
        prev = low;
        const rise = Math.min(1, t / 0.07), swell = 0.55 + 0.45 * Math.exp(-Math.pow((t - 0.62) / 0.16, 2)), fall = Math.pow(1 - t, 1.6);
        out[i] = clamp(paper * rise * swell * fall * 2.1, -1, 1) * 0.27;
      }
    }
    const src = audio.createBufferSource();
    src.buffer = pageSound;
    src.connect(audio.destination);
    src.start();
  } catch (e) {
    // No sound is fine; reading must not depend on the audio system.
  }
}

function celebrate() {
  store.save();
  toast(`You finished “${book.title}”`, 5000);
  const c = el.confetti, g = c.getContext("2d");
  c.width = root.clientWidth;
  c.height = root.clientHeight;
  c.classList.add("show");
  const colours = ["#E8A33D", "#D4486A", "#3FA36B", "#5B8DEF", "#A868E8", "#F2D15B"];
  const bits = Array.from({ length: 140 }, () => ({
    x: Math.random() * c.width, y: -20 - Math.random() * c.height * 0.5, vx: (Math.random() - 0.5) * 3,
    vy: 2 + Math.random() * 4, r: 4 + Math.random() * 5, a: Math.random() * 6, va: (Math.random() - 0.5) * 0.3,
    c: colours[Math.floor(Math.random() * colours.length)] }));
  const start = now();
  const frame = () => {
    const age = now() - start;
    g.clearRect(0, 0, c.width, c.height);
    for (const b of bits) {
      b.x += b.vx;
      b.y += b.vy;
      b.a += b.va;
      g.save();
      g.translate(b.x, b.y);
      g.rotate(b.a);
      g.globalAlpha = Math.max(0, 1 - age / 3800);
      g.fillStyle = b.c;
      g.fillRect(-b.r, -b.r / 2, b.r * 2, b.r);
      g.restore();
    }
    if (age < 3800 && book) requestAnimationFrame(frame);
    else c.classList.remove("show");
  };
  frame();
}
