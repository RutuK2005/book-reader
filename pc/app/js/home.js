// Home: today's reading at a glance, the book in progress, and the shelves. Shelves are the
// reader's own categories; "Reading" and "Finished" are worked out from each book's progress.
import { h, clear, toast, dialog, menu, ask, confirm, pickFiles, minutes, hex, blend } from "./ui.js";
import * as store from "./store.js";
import { THEME_BG, THEME_FG, isDark, SCHEMES, SCHEME_NAMES, DEFAULT_ACCENT } from "./themes.js";

/** Filters that are not user shelves; a shelf name can never start with '#'. */
const ALL = "", READING = "#reading", FINISHED = "#finished";
const GOALS = [5, 10, 15, 20, 30, 45, 60, 90];
const BOOK_ACCEPT = ".pdf,.epub,.txt,application/pdf,application/epub+zip,text/plain";

let root = null, openBook = null;
let filter = ALL, query = "", searching = false, importing = 0;
const coverUrls = new Map();
/** What is being dragged: {book} or {tab}. Kept here because drag data cannot carry objects. */
let dragging = null;

export function init(el, onOpen) {
  root = el;
  openBook = onOpen;
  filter = store.prefs().shelf || ALL;
  // Books can be dropped onto the window from Explorer.
  window.addEventListener("dragover", (e) => {
    if (e.dataTransfer && Array.from(e.dataTransfer.types).includes("Files")) e.preventDefault();
  });
  window.addEventListener("drop", (e) => {
    if (!e.dataTransfer || !e.dataTransfer.files.length || root.hidden) return;
    e.preventDefault();
    addFiles(Array.from(e.dataTransfer.files));
  });
}

export function forgetCover(id) {
  const url = coverUrls.get(id);
  if (url) URL.revokeObjectURL(url);
  coverUrls.delete(id);
}

async function coverUrl(b) {
  if (!coverUrls.has(b.id)) {
    const blob = await store.coverFile(b);
    coverUrls.set(b.id, blob ? URL.createObjectURL(blob) : "");
  }
  return coverUrls.get(b.id);
}

function setFilter(f) {
  filter = f;
  store.setPref("shelf", f);
}

// ---------------------------------------------------------------- page

function applyColours() {
  const p = store.prefs();
  const theme = p.theme;
  // A lightly tinted page with paper-white cards raised off it; in the dark themes the cards
  // are simply a lighter grey, since shadows do not show on black.
  const paper = theme === 5 ? 0x121212 : THEME_BG[theme];
  let dark = isDark(theme), fg = THEME_FG[theme];
  let bg = dark ? paper : blend(paper, fg, 0.055);
  let card = dark ? blend(paper, fg, 0.1) : blend(paper, 0xffffff, 0.65);
  let accent = DEFAULT_ACCENT;
  const sc = SCHEMES[p.homeScheme];
  if (sc) {
    // The home page has its own colours, separate from the reading theme.
    [bg, card, fg, accent] = sc;
    dark = sc[4] === 1;
  }
  const s = root.style;
  s.setProperty("--bg", hex(bg));
  s.setProperty("--card", hex(card));
  s.setProperty("--fg", hex(fg));
  s.setProperty("--dim", hex(blend(bg, fg, 0.6)));
  s.setProperty("--accent", hex(accent));
  s.setProperty("--line", hex(blend(card, fg, 0.14)));
  root.classList.toggle("dark", dark);
  document.documentElement.style.background = hex(bg);
}

export function build() {
  const keep = root.scrollTop;
  applyColours();
  clear(root);
  const p = store.prefs();
  const all = store.list();
  if (!filter.startsWith("#") && filter && !store.state.shelves.includes(filter)) setFilter(ALL);

  const page = h("div", { class: "home" });
  page.append(header());
  page.append(summaryRow(p.homeOpen));
  if (p.homeOpen) {
    page.append(statsCard());
    let current = null;
    for (const b of store.state.books) {
      if (store.started(b) && !store.finished(b) && (!current || b.opened > current.opened)) current = b;
    }
    if (current) page.append(continueCard(current));
    page.append(coloursRow());
  }
  if (!all.length) {
    page.append(h("p", { class: "empty big" },
      "Your library is empty.", h("br"), h("br"),
      "Click “Add books” or drop files onto this window (PDF, EPUB or TXT). They are copied into MyBookSpace, so they stay here even if you move the originals."));
  } else {
    page.append(chips(all));
    const listBox = h("div", { class: "list" });
    if (searching) page.append(searchField(listBox));
    page.append(listBox);
    fillList(listBox);
  }
  root.append(page);
  root.append(h("div", { class: "busy" + (importing ? " show" : ""), text: "Adding books…" }));
  root.scrollTop = keep;
}

/** The heading and the books under it. Kept separate so typing in the search box only redraws this. */
function fillList(listBox) {
  clear(listBox);
  const q = query.toLowerCase();
  const shown = store.list().filter((b) => matches(b) && (!q || b.title.toLowerCase().includes(q)));
  const coversOnly = store.prefs().coversOnly;
  listBox.append(h("div", { class: "list-head" },
    h("span", { class: "caps grow", text: filterTitle() + "   " + shown.length }),
    h("button", { class: "link", text: searching ? "Close search" : "Search", onclick: () => {
      searching = !searching;
      query = "";
      build();
      if (searching) root.querySelector(".search")?.focus();
    } }),
    h("button", { class: "link", text: coversOnly ? "Show titles" : "Covers only", onclick: () => {
      store.setPref("coversOnly", !coversOnly);
      build();
    } })));
  if (!shown.length) {
    listBox.append(h("p", { class: "empty", text: q ? `No book matches “${query}”.`
      : filter === READING ? "Nothing in progress. Open a book to start reading."
      : filter === FINISHED ? "No finished books yet."
      : "Nothing on this shelf yet. Drag a book onto this shelf's tab, or right-click a book and choose “Move to shelf”." }));
    return;
  }
  const grid = h("div", { class: "grid" + (coversOnly ? " covers-only" : "") });
  for (const b of shown) grid.append(gridItem(b, coversOnly));
  listBox.append(grid);
}

function searchField(listBox) {
  return h("input", { class: "search", type: "search", placeholder: "Search your books", value: query, oninput: (e) => {
    query = e.target.value.trim();
    fillList(listBox);
  } });
}

function header() {
  const date = new Date().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" });
  return h("div", { class: "head" },
    h("div", { class: "grow" },
      h("div", { class: "caps", text: date }),
      h("h1", { text: "MyBookSpace" }),
      h("a", { class: "by", href: "https://github.com/RutuK2005", target: "_blank", text: "by Rutuparn K   ·   github.com/RutuK2005" })),
    h("button", { class: "pill accent big", text: "+  Add books", title: "Add books (Ctrl+O)", onclick: pick }));
}

/** One line that unfolds into the daily goal and the book in progress. Folded by default. */
function summaryRow(open) {
  const streak = store.streak();
  return h("button", { class: "card row summary", onclick: () => {
    store.setPref("homeOpen", !open);
    build();
  } },
    h("span", { class: "grow", text: `Today  ${minutes(store.secondsOn(0))}   ·   ${streak} ${streak === 1 ? "day" : "days"} streak` }),
    h("span", { class: "accent-text", text: open ? "Hide  ▴" : "Goal and current book  ▾" }));
}

/** Today's minutes against the daily goal, the streak, and the week. Click to change the goal. */
function statsCard() {
  const goal = store.prefs().goalMin, today = store.secondsOn(0), streak = store.streak();
  const frac = Math.min(1, today / (goal * 60));
  const R = 31, C = 2 * Math.PI * R;
  const ring = h("div", { class: "ring", html:
    `<svg viewBox="0 0 74 74"><circle cx="37" cy="37" r="${R}" class="track"/>` +
    (frac > 0 ? `<circle cx="37" cy="37" r="${R}" class="fill" stroke-dasharray="${C}" stroke-dashoffset="${C * (1 - frac)}" transform="rotate(-90 37 37)"/>` : "") + "</svg>" +
    `<div class="ring-text"><b>${Math.floor(today / 60)}</b><span>min</span></div>` });
  const stat = (value, label) => h("div", { class: "stat grow" }, h("b", { text: value }), h("span", { text: label }));
  return h("button", { class: "card row stats", title: "Change the daily goal", onclick: goalDialog },
    ring,
    stat(minutes(today), today >= goal * 60 ? "Goal reached" : `of ${goal} min today`),
    stat(streak + (streak === 1 ? " day" : " days"), "Streak"),
    stat(minutes(store.weekSeconds()), "This week"));
}

function continueCard(b) {
  const pct = store.percent(b);
  let where = `Page ${b.page + 1} of ${b.pages}  ·  ${pct}%`;
  if (b.samples >= 5 && b.secPerPage > 0) where += `  ·  about ${minutes(Math.round((b.pages - 1 - b.page) * b.secPerPage))} left`;
  const cover = h("img", { class: "cover small", alt: "" });
  coverUrl(b).then((u) => (cover.src = u));
  return h("div", { class: "card row continue", onclick: () => openBook(b) },
    cover,
    h("div", { class: "grow" },
      h("div", { class: "caps accent-text bold", text: "Continue reading" }),
      h("div", { class: "book-title", text: b.title }),
      h("div", { class: "dim small-text", text: where }),
      h("div", { class: "progress" }, h("i", { style: { width: pct + "%" } })),
      h("button", { class: "pill accent", text: "Resume" })));
}

function coloursRow() {
  const scheme = store.prefs().homeScheme;
  return h("button", { class: "card row colours", onclick: colourPicker },
    h("i", { class: "dot" }),
    h("span", { class: "grow", text: "Home page colours   ·   " + (SCHEME_NAMES[scheme] || SCHEME_NAMES[0]) }),
    h("span", { class: "accent-text", text: "Change" }));
}

/** A grid of colour schemes for the home page, each tile drawn in its own colours. */
function colourPicker() {
  const current = store.prefs().homeScheme;
  const grid = h("div", { class: "scheme-grid" });
  let api = null;
  SCHEMES.forEach((sc, i) => {
    const [tileBg, tileCard, tileFg, tileAccent] = sc || [0xffffff, 0xefefef, 0x222222, DEFAULT_ACCENT];
    grid.append(h("button", { class: "scheme" + (i === current ? " on" : ""),
      style: { background: hex(tileBg), color: hex(tileFg), borderColor: i === current ? hex(tileAccent) : "rgba(0,0,0,.2)" },
      onclick: () => {
        store.setPref("homeScheme", i);
        api.close();
        build();
      } },
      // A miniature of the page: a card with a line of text and an accent button.
      h("span", { class: "mini", style: { background: hex(tileCard) } },
        h("b", { text: "Aa" }), h("i", { style: { background: hex(tileAccent) } })),
      h("span", { text: SCHEME_NAMES[i] })));
  });
  dialog({ title: "Home page colours", body: grid, wide: true, onShow: (a) => (api = a) });
}

async function goalDialog() {
  const current = store.prefs().goalMin;
  const i = await menu("Daily reading goal", GOALS.map((g) => g + " minutes a day" + (g === current ? "   ✓" : "")));
  if (i >= 0) {
    store.setPref("goalMin", GOALS[i]);
    build();
  }
}

// ---------------------------------------------------------------- shelves

/** The tabs in the reader's chosen order; anything new goes at the end. */
function tabOrder() {
  const valid = [ALL, READING, FINISHED, ...store.state.shelves];
  const out = [];
  for (const k of store.prefs().tabOrder) if (valid.includes(k) && !out.includes(k)) out.push(k);
  for (const k of valid) if (!out.includes(k)) out.push(k);
  return out;
}

function moveTab(key, onto) {
  const order = tabOrder();
  const from = order.indexOf(key), to = order.indexOf(onto);
  if (from < 0 || to < 0 || from === to) return;
  order.splice(from, 1);
  order.splice(to, 0, key);
  store.setPref("tabOrder", order);
}

function chips(all) {
  let reading = 0, done = 0;
  const counts = {};
  for (const b of all) {
    if (store.finished(b)) done++;
    else if (store.started(b)) reading++;
    if (b.shelf) counts[b.shelf] = (counts[b.shelf] || 0) + 1;
  }
  const row = h("div", { class: "chips" });
  for (const key of tabOrder()) {
    if (key === ALL) row.append(chip("All", all.length, ALL, false));
    else if (key === READING) row.append(chip("Reading", reading, READING, false));
    else if (key === FINISHED) row.append(chip("Finished", done, FINISHED, false));
    else row.append(chip(key, counts[key] || 0, key, true));
  }
  row.append(h("button", { class: "pill outline", text: "+  New shelf", onclick: () => newShelf(null) }));
  return row;
}

function chip(name, count, key, custom) {
  const el = h("button", { class: "pill chip" + (filter === key ? " on" : ""), draggable: true, text: `${name}  ${count}`,
    title: custom ? "Right-click to rename or delete. Drag to rearrange." : "Drag to rearrange.",
    onclick: () => {
      setFilter(key);
      build();
    },
    oncontextmenu: (e) => {
      e.preventDefault();
      if (custom) shelfMenu(key);
    },
    // Drag a tab onto another to rearrange them. A book dropped on a shelf's tab is filed there.
    ondragstart: (e) => {
      dragging = { tab: key };
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", name);
    },
    ondragend: () => (dragging = null),
    ondragover: (e) => {
      if (!dragging || (dragging.book && !custom)) return;
      e.preventDefault();
      el.classList.add("over");
    },
    ondragleave: () => el.classList.remove("over"),
    ondrop: (e) => {
      e.preventDefault();
      el.classList.remove("over");
      const what = dragging;
      dragging = null;
      if (!what) return;
      if (what.book && custom) {
        what.book.shelf = key;
        store.save();
        toast("Moved to " + key);
        build();
      } else if (what.tab !== undefined && what.tab !== key) {
        moveTab(what.tab, key);
        build();
      }
    } });
  return el;
}

async function newShelf(forBook) {
  const name = await ask("New shelf", "", "Create", { hint: "Work, Study, Fiction…" });
  if (!name) return;
  const made = store.addShelf(name);
  if (!made) {
    toast("There is already a shelf with that name");
    return;
  }
  if (forBook) {
    forBook.shelf = made;
    store.save();
  }
  build();
}

async function shelfMenu(name) {
  const i = await menu(name, ["Rename shelf", { label: "Delete shelf", danger: true }]);
  if (i === 0) {
    const to = await ask("Rename shelf", name);
    if (to && !store.renameShelf(name, to)) toast("There is already a shelf with that name");
    if (filter === name && to) filter = store.prefs().shelf;
    build();
  } else if (i === 1) {
    if (await confirm(`Delete “${name}”?`, "The books on it stay in your library, without a shelf.", "Delete", true)) {
      store.deleteShelf(name);
      build();
    }
  }
}

async function shelfPicker(b) {
  const shelves = store.state.shelves;
  const items = shelves.map((s) => s + (s === b.shelf ? "   ✓" : ""));
  items.push("No shelf" + (b.shelf ? "" : "   ✓"), "New shelf…");
  const i = await menu("Move to shelf", items);
  if (i < 0) return;
  if (i < shelves.length) b.shelf = shelves[i];
  else if (i === shelves.length) b.shelf = "";
  else return newShelf(b);
  store.save();
  build();
}

// ---------------------------------------------------------------- books

function matches(b) {
  if (filter === ALL) return true;
  if (filter === READING) return store.started(b) && !store.finished(b);
  if (filter === FINISHED) return store.finished(b);
  return filter === b.shelf;
}

function filterTitle() {
  return filter === ALL ? "All books" : filter === READING ? "Reading" : filter === FINISHED ? "Finished" : filter;
}

function gridItem(b, coversOnly) {
  const img = h("img", { class: "cover", alt: b.title, draggable: false });
  coverUrl(b).then((u) => (img.src = u));
  const pct = store.percent(b);
  const sub = store.finished(b) ? "Finished" : store.started(b) ? `${pct}%  ·  page ${b.page + 1} of ${b.pages}` : `${b.pages} pages`;
  const item = h("div", { class: "book", draggable: true, title: coversOnly ? b.title : "",
    onclick: () => chooseVersion(b),
    oncontextmenu: (e) => {
      e.preventDefault();
      bookMenu(b);
    },
    // Drag a book to rearrange it, onto a shelf's tab to file it, or onto another book to merge
    // the two as versions of one book.
    ondragstart: (e) => {
      dragging = { book: b };
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", b.title);
      item.classList.add("lifted");
    },
    ondragend: () => {
      dragging = null;
      item.classList.remove("lifted");
    },
    ondragover: (e) => {
      if (!dragging || !dragging.book || dragging.book === b) return;
      e.preventDefault();
      item.classList.add("over");
    },
    ondragleave: () => item.classList.remove("over"),
    ondrop: (e) => {
      e.preventDefault();
      item.classList.remove("over");
      const dragged = dragging && dragging.book;
      dragging = null;
      if (dragged && dragged !== b) confirmMerge(dragged, b);
    } },
    h("div", { class: "slot" }, img, b.partner ? h("span", { class: "badge", text: "2 versions" }) : null),
    coversOnly ? null : h("div", { class: "book-name", text: b.title }),
    coversOnly ? null : h("div", { class: "dim small-text", text: sub }),
    coversOnly || !store.started(b) || store.finished(b) ? null : h("div", { class: "progress thin" }, h("i", { style: { width: pct + "%" } })));
  return item;
}

/** A merged book asks which version to open; any other book just opens. */
async function chooseVersion(b) {
  const other = b.partner ? store.find(b.partner) : null;
  if (!other) return openBook(b);
  const versions = [b, other];
  const i = await menu(b.title, versions.map((v) =>
    `${store.kindName(v)}   ·   ${store.started(v) ? `page ${v.page + 1} of ${v.pages}` : v.pages + " pages"}`));
  if (i >= 0) openBook(versions[i]);
}

async function confirmMerge(dragged, target) {
  const v = await dialog({
    title: "Merge these two?",
    body: `“${dragged.title}” and “${target.title}” become one book with two versions. Opening it asks which to read. You can separate them again from the book's menu.`,
    buttons: [{ label: "Cancel" }, { label: "Just move it", value: "move" }, { label: "Merge", primary: true, value: "merge" }],
  });
  if (v === "merge" && !store.merge(dragged, target)) toast("One of these already has a second version");
  if (v === "move") store.move(dragged, target);
  if (v) build();
}

async function bookMenu(b) {
  const items = ["Open", "Move to shelf…", "Rename", "Change cover…", "Details", { label: "Remove from library", danger: true }];
  if (b.partner) items.push("Separate the two versions");
  const i = await menu(b.title, items);
  if (i === 0) chooseVersion(b);
  else if (i === 1) shelfPicker(b);
  else if (i === 2) rename(b);
  else if (i === 3) coverMenu(b);
  else if (i === 4) details(b);
  else if (i === 5) confirmRemove(b);
  else if (i === 6) {
    store.unmerge(b);
    build();
  }
}

async function rename(b) {
  const t = await ask("Rename", b.title);
  if (t) {
    b.title = t;
    store.save();
    build();
  }
}

async function confirmRemove(b) {
  const ok = await confirm(`Remove “${b.title}”?`,
    "This deletes MyBookSpace's copy, your place, your bookmarks and notes. The original file is not touched.", "Remove", true);
  if (!ok) return;
  forgetCover(b.id);
  await store.remove(b);
  build();
}

function details(b) {
  let marks = 0;
  for (const list of Object.values(b.highlights)) marks += list.length;
  const lines = [
    `${store.kindName(b)}  ·  ${b.pages} pages`,
    "",
    store.finished(b) ? "Finished" : store.started(b) ? `On page ${b.page + 1}  (${store.percent(b)}%)` : "Not started",
    "Time spent reading: " + minutes(b.readSec),
  ];
  if (b.samples >= 5 && b.secPerPage > 0 && !store.finished(b)) {
    lines.push(`About ${minutes(Math.round((b.pages - 1 - b.page) * b.secPerPage))} left at your pace`);
  }
  lines.push("", `${b.bookmarks.length} bookmarks  ·  ${Object.keys(b.notes).length} notes  ·  ${marks} highlights`);
  lines.push("Shelf: " + (b.shelf || "none"));
  if (b.added) lines.push("Added " + new Date(b.added).toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" }));
  lines.push("", "File: " + b.src);
  dialog({ title: b.title, body: h("p", { class: "pre", text: lines.join("\n") }) });
}

// ---------------------------------------------------------------- covers

async function coverMenu(b) {
  const items = ["Choose a picture from this computer", "Paste a picture from the clipboard", "Find a cover on the web"];
  if (b.ownCover) items.push("Use the original cover again");
  const i = await menu("Cover", items);
  if (i === 0) {
    const [file] = await pickFiles("image/*", false);
    if (file) applyCover(b, () => store.setCover(b, file));
  } else if (i === 1) {
    applyCover(b, async () => {
      let picture = null;
      try {
        for (const item of await navigator.clipboard.read()) {
          const type = item.types.find((t) => t.startsWith("image/"));
          if (type) picture = await item.getType(type);
        }
      } catch (e) {
        throw new Error("The clipboard couldn't be read");
      }
      if (!picture) throw new Error("There is no picture on the clipboard. Copy an image first, then try again.");
      await store.setCover(b, picture);
    });
  } else if (i === 2) {
    // Opens an image search for the book in the browser; the picture is then copied or saved.
    window.open("https://www.google.com/search?tbm=isch&q=" + encodeURIComponent(b.title + " book cover"), "_blank");
    toast("Copy or save a picture, then come back and choose it as the cover", 6000);
  } else if (i === 3) {
    applyCover(b, () => store.resetCover(b));
  }
}

async function applyCover(b, change) {
  try {
    await change();
  } catch (e) {
    toast(e.message || "That picture couldn't be used");
  }
  forgetCover(b.id);
  build();
}

// ---------------------------------------------------------------- adding books

export async function pick() {
  addFiles(await pickFiles(BOOK_ACCEPT, true));
}

export async function addFiles(files) {
  if (!files.length) return null;
  importing++;
  build();
  // New books land on the shelf being looked at, if it is one of the reader's own.
  const shelf = filter.startsWith("#") ? "" : filter;
  let error = null, last = null;
  for (const f of files) {
    try {
      const b = await store.importFile(f);
      if (shelf && !b.shelf) b.shelf = shelf;
      last = b;
    } catch (e) {
      console.error(e);
      error = e.message || "One file couldn't be opened";
    }
  }
  await store.save();
  importing--;
  build();
  if (error) toast(error, 5000);
  return last;
}
