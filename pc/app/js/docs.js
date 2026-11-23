// Opens a book file and draws its pages. PDFs are drawn by pdf.js; EPUB and text files are laid
// out into pages of a fixed size, so a page number means the same thing whatever the window size.
import * as pdfjs from "../vendor/pdfjs/pdf.min.mjs";

pdfjs.GlobalWorkerOptions.workerSrc = new URL("../vendor/pdfjs/pdf.worker.min.mjs", import.meta.url).href;
const PDF_ASSETS = new URL("../vendor/pdfjs/", import.meta.url).href;

/** The page that EPUB and text books are set on, in CSS pixels before scaling to the window. */
const PAGE_W = 620, PAGE_H = 900, PAD_X = 62, PAD_Y = 64;
export const DEFAULT_TEXT_SIZE = 19;
export const TEXT_SIZES = [14, 15, 16, 17, 18, 19, 20, 22, 24, 26, 28, 32];

const MAX_PIXELS = 14e6;

export async function sniff(blob) {
  const head = new Uint8Array(await blob.slice(0, 1024).arrayBuffer());
  const text = String.fromCharCode(...head);
  if (text.includes("%PDF-")) return "pdf";
  if (head[0] === 0x50 && head[1] === 0x4b) return "epub";
  return "txt";
}

// ---------------------------------------------------------------- PDF

class PdfBook {
  constructor(pdf) {
    this.pdf = pdf;
    this.pages = pdf.numPages;
    this.kind = "pdf";
    this.sizes = new Map();
    this.title = "";
  }

  async page(i) {
    return this.pdf.getPage(i + 1);
  }

  /** Width / height of a page. */
  async aspect(i) {
    if (!this.sizes.has(i)) {
      const p = await this.page(i);
      const v = p.getViewport({ scale: 1 });
      this.sizes.set(i, v.width / v.height);
    }
    return this.sizes.get(i);
  }

  /**
   * Draws the part of page i inside `crop` (l, t, r, b as fractions; null for all of it) onto a
   * new canvas `width` device pixels wide. Returns {canvas, cancel}.
   */
  draw(i, width, crop) {
    const canvas = document.createElement("canvas");
    let task = null, cancelled = false;
    const done = (async () => {
      const p = await this.page(i);
      if (cancelled) return canvas;
      const base = p.getViewport({ scale: 1 });
      const c = crop || [0, 0, 1, 1];
      let scale = width / (base.width * (c[2] - c[0]));
      const pixels = base.width * (c[2] - c[0]) * scale * base.height * (c[3] - c[1]) * scale;
      if (pixels > MAX_PIXELS) scale *= Math.sqrt(MAX_PIXELS / pixels);
      const view = p.getViewport({ scale });
      canvas.width = Math.max(1, Math.round(view.width * (c[2] - c[0])));
      canvas.height = Math.max(1, Math.round(view.height * (c[3] - c[1])));
      // Not an opaque canvas: on one, text is smoothed with coloured sub-pixels, and the dark
      // themes turn those into blue and orange fringes around every letter.
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#fff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      task = p.render({
        canvasContext: ctx,
        viewport: view,
        transform: [1, 0, 0, 1, -view.width * c[0], -view.height * c[1]],
      });
      try {
        await task.promise;
      } catch (e) {
        if (!cancelled) throw e;
      }
      return canvas;
    })();
    return {
      canvas,
      done,
      cancel() {
        cancelled = true;
        if (task) task.cancel();
      },
    };
  }

  /** Bounds of everything that is not blank paper on page i, as fractions of the page. */
  async inkBox(i) {
    const { done } = this.draw(i, 240, null);
    const canvas = await done;
    const w = canvas.width, hgt = Math.min(480, canvas.height);
    const px = canvas.getContext("2d").getImageData(0, 0, w, canvas.height).data;
    const cols = new Int32Array(w), rows = new Int32Array(canvas.height);
    for (let y = 0; y < canvas.height; y++) {
      for (let x = 0; x < w; x++) {
        const o = (y * w + x) * 4;
        if (px[o] < 200 || px[o + 1] < 200 || px[o + 2] < 200) {
          cols[x]++;
          rows[y]++;
        }
      }
    }
    // A row or column needs two dark pixels to count, so single specks are ignored.
    const H = canvas.height;
    let l = 0, r = w - 1, t = 0, b = H - 1;
    while (l < w && cols[l] < 2) l++;
    while (r > l && cols[r] < 2) r--;
    while (t < H && rows[t] < 2) t++;
    while (b > t && rows[b] < 2) b--;
    if (l >= w || t >= H || hgt < 1) return null;
    return [l / w, t / H, (r + 1) / w, (b + 1) / H];
  }

  /**
   * Finds the text block so margins can be cropped away. Cropping each page on its own would
   * make a half-empty page zoom in further than a full one, so this takes the union of the ink
   * bounds over a sample of pages, separately for left- and right-hand pages.
   */
  async computeCrop() {
    const union = async (parity) => {
      const candidates = [];
      for (let i = parity === 0 ? 2 : 1; i < this.pages; i += 2) candidates.push(i);
      if (!candidates.length) candidates.push(Math.min(parity, this.pages - 1));
      const step = Math.max(1, Math.floor(candidates.length / 10));
      let box = null;
      for (let k = 0, n = 0; k < candidates.length && n < 10; k += step, n++) {
        const ink = await this.inkBox(candidates[k]);
        if (!ink) continue;
        box = box ? [Math.min(box[0], ink[0]), Math.min(box[1], ink[1]), Math.max(box[2], ink[2]), Math.max(box[3], ink[3])] : ink;
      }
      if (!box) return null;
      const m = 0.012;
      box = [Math.max(0, box[0] - m), Math.max(0, box[1] - m), Math.min(1, box[2] + m), Math.min(1, box[3] + m)];
      return box[2] - box[0] > 0.95 && box[3] - box[1] > 0.95 ? null : box;
    };
    const even = await union(0), odd = await union(1);
    let cover = false;
    const first = await this.inkBox(0);
    if (even && first) {
      const t = 0.02;
      cover = first[0] >= even[0] - t && first[1] >= even[1] - t && first[2] <= even[2] + t && first[3] <= even[3] + t;
    }
    return { even, odd, cover };
  }

  close() {
    this.pdf.destroy();
  }
}

export async function openPdf(blob) {
  const data = new Uint8Array(await blob.arrayBuffer());
  let pdf;
  try {
    pdf = await pdfjs.getDocument({
      data,
      cMapUrl: PDF_ASSETS + "cmaps/",
      cMapPacked: true,
      standardFontDataUrl: PDF_ASSETS + "standard_fonts/",
      isEvalSupported: false,
    }).promise;
  } catch (e) {
    throw new Error(e && e.name === "PasswordException" ? "That PDF is password-protected" : "That PDF couldn't be opened");
  }
  if (pdf.numPages < 1) throw new Error("That PDF has no pages");
  return new PdfBook(pdf);
}

// ---------------------------------------------------------------- zip (for EPUB)

async function inflate(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Reads a zip file's directory. Returns a Map of path -> function giving the file's bytes. */
function unzip(buf) {
  const v = new DataView(buf), u8 = new Uint8Array(buf);
  let end = -1;
  for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 66000); i--) {
    if (v.getUint32(i, true) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("That file isn't a readable EPUB");
  const count = v.getUint16(end + 10, true);
  let at = v.getUint32(end + 16, true);
  const files = new Map(), utf8 = new TextDecoder();
  for (let n = 0; n < count && at + 46 <= buf.byteLength; n++) {
    if (v.getUint32(at, true) !== 0x02014b50) break;
    const method = v.getUint16(at + 10, true), size = v.getUint32(at + 20, true);
    const nameLen = v.getUint16(at + 28, true), extra = v.getUint16(at + 30, true), comment = v.getUint16(at + 32, true);
    const local = v.getUint32(at + 42, true);
    const name = utf8.decode(u8.subarray(at + 46, at + 46 + nameLen));
    files.set(name, async () => {
      const start = local + 30 + v.getUint16(local + 26, true) + v.getUint16(local + 28, true);
      const raw = u8.subarray(start, start + size);
      return method === 0 ? raw : inflate(raw);
    });
    at += 46 + nameLen + extra + comment;
  }
  return files;
}

function resolvePath(base, href) {
  const parts = base.split("/").slice(0, -1);
  for (const seg of decodeURIComponent(href.split("#")[0]).split("/")) {
    if (seg === "..") parts.pop();
    else if (seg !== "." && seg !== "") parts.push(seg);
  }
  return parts.join("/");
}

const IMAGE_TYPES = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp", svg: "image/svg+xml" };

// ---------------------------------------------------------------- EPUB and text

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

async function parseEpub(blob) {
  const files = unzip(await blob.arrayBuffer());
  const text = async (path) => {
    const get = files.get(path);
    if (!get) throw new Error("That EPUB is missing a part: " + path);
    return new TextDecoder().decode(await get());
  };
  const xml = (s) => new DOMParser().parseFromString(s, "application/xml");
  const container = xml(await text("META-INF/container.xml"));
  const rootfile = container.getElementsByTagNameNS("*", "rootfile")[0];
  if (!rootfile) throw new Error("That file isn't a readable EPUB");
  const opfPath = rootfile.getAttribute("full-path");
  const opf = xml(await text(opfPath));
  const manifest = new Map();
  let coverPath = null;
  for (const item of opf.getElementsByTagNameNS("*", "item")) {
    const href = item.getAttribute("href");
    if (!href) continue;
    const path = resolvePath(opfPath, href);
    manifest.set(item.getAttribute("id"), path);
    if ((item.getAttribute("properties") || "").includes("cover-image")) coverPath = path;
  }
  // Many EPUBs name their cover picture in the metadata instead.
  for (const meta of opf.getElementsByTagNameNS("*", "meta")) {
    if (!coverPath && meta.getAttribute("name") === "cover") coverPath = manifest.get(meta.getAttribute("content")) || null;
  }
  const titleEl = opf.getElementsByTagNameNS("*", "title")[0];
  const title = titleEl ? titleEl.textContent.trim() : "";

  const urls = new Map();
  const imageUrl = async (path) => {
    if (urls.has(path)) return urls.get(path);
    const get = files.get(path);
    if (!get) return null;
    const ext = path.split(".").pop().toLowerCase();
    const url = URL.createObjectURL(new Blob([await get()], { type: IMAGE_TYPES[ext] || "application/octet-stream" }));
    urls.set(path, url);
    return url;
  };

  const chapters = [];
  for (const ref of opf.getElementsByTagNameNS("*", "itemref")) {
    const path = manifest.get(ref.getAttribute("idref"));
    if (!path || !files.has(path)) continue;
    const source = await text(path);
    let doc = new DOMParser().parseFromString(source, "application/xhtml+xml");
    if (doc.getElementsByTagName("parsererror").length || !doc.body) doc = new DOMParser().parseFromString(source, "text/html");
    const body = doc.body;
    if (!body) continue;
    body.querySelectorAll("script, style, link, iframe, object, embed, form, input, button, audio, video, nav[hidden]").forEach((n) => n.remove());
    // Covers are often an SVG wrapper around one picture; treat it as a plain image.
    for (const svg of Array.from(body.getElementsByTagNameNS("*", "svg"))) {
      const im = svg.getElementsByTagNameNS("*", "image")[0];
      const href = im && (im.getAttribute("xlink:href") || im.getAttribute("href") || im.getAttributeNS("http://www.w3.org/1999/xlink", "href"));
      if (href) {
        const img = doc.createElement("img");
        img.setAttribute("src", href);
        svg.replaceWith(img);
      } else {
        svg.remove();
      }
    }
    for (const el of Array.from(body.querySelectorAll("*"))) {
      for (const a of Array.from(el.attributes)) {
        const n = a.name.toLowerCase();
        if (n.startsWith("on") || n === "style" || n === "href" || n === "id" || n === "width" || n === "height") el.removeAttribute(a.name);
      }
    }
    for (const img of Array.from(body.querySelectorAll("img"))) {
      const src = img.getAttribute("src");
      const url = src && !/^(https?:|data:)/i.test(src) ? await imageUrl(resolvePath(path, src)) : null;
      if (url) img.setAttribute("src", url);
      else img.remove();
    }
    const holder = document.createElement("div");
    for (const n of Array.from(body.childNodes)) holder.append(document.importNode(n, true));
    if (!holder.textContent.trim() && !holder.querySelector("img")) continue;
    chapters.push(holder.innerHTML);
  }
  if (!chapters.length) throw new Error("That EPUB has nothing to read in it");
  let cover = null;
  if (coverPath && files.has(coverPath)) {
    cover = await imageUrl(coverPath);
    // If the first page has no picture of its own, the cover goes in front.
    if (cover && !/<img\b/i.test(chapters[0])) chapters.unshift(`<div class="cover-page"><img src="${cover}"></div>`);
  }
  return { title, chapters, cover };
}

async function parseText(blob) {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let text = new TextDecoder("utf-8").decode(bytes);
  if ((text.match(/�/g) || []).length > text.length / 200) text = new TextDecoder("windows-1252").decode(bytes);
  text = text.replace(/\r\n?/g, "\n");
  const paras = (/\n\s*\n/.test(text) ? text.split(/\n\s*\n+/) : text.split("\n")).map((p) => p.replace(/\s*\n\s*/g, " ").trim()).filter(Boolean);
  if (!paras.length) throw new Error("That text file is empty");
  const chapters = [];
  for (let i = 0; i < paras.length; i += 240) {
    chapters.push(paras.slice(i, i + 240).map((p) => "<p>" + escapeHtml(p) + "</p>").join(""));
  }
  return { title: "", chapters, cover: null };
}

async function imagesReady(root) {
  const waits = [];
  for (const img of root.querySelectorAll("img")) {
    if (!img.complete) waits.push(new Promise((ok) => {
      img.addEventListener("load", ok, { once: true });
      img.addEventListener("error", ok, { once: true });
    }));
  }
  if (waits.length) await Promise.race([Promise.all(waits), new Promise((ok) => setTimeout(ok, 4000))]);
}

class TextBook {
  constructor(parsed, kind) {
    this.kind = kind;
    this.title = parsed.title;
    this.chapters = parsed.chapters;
    this.coverUrl = parsed.cover;
    this.textSize = DEFAULT_TEXT_SIZE;
    this.counts = [];
    this.starts = [];
    this.pages = 1;
    /** Parsed chapters kept as templates, so showing a page clones nodes rather than re-parsing. */
    this.templates = new Map();
  }

  async aspect() {
    return PAGE_W / PAGE_H;
  }

  template(c) {
    let t = this.templates.get(c);
    if (!t) {
      t = document.createElement("template");
      t.innerHTML = this.chapters[c];
      this.templates.set(c, t);
      if (this.templates.size > 6) this.templates.delete(this.templates.keys().next().value);
    }
    return t;
  }

  flow(c) {
    const flow = document.createElement("div");
    flow.className = "tflow";
    flow.style.fontSize = this.textSize + "px";
    flow.append(this.template(c).content.cloneNode(true));
    return flow;
  }

  /** Lays every chapter out once to count its pages. */
  async layout(size) {
    this.textSize = size;
    const view = document.createElement("div");
    view.className = "tview measuring";
    document.body.append(view);
    this.counts = [];
    this.starts = [];
    let total = 0;
    try {
      for (let c = 0; c < this.chapters.length; c++) {
        const flow = this.flow(c);
        view.replaceChildren(flow);
        await imagesReady(flow);
        const n = Math.max(1, Math.round((flow.scrollWidth + 2 * PAD_X) / PAGE_W));
        this.starts.push(total);
        this.counts.push(n);
        total += n;
      }
    } finally {
      view.remove();
    }
    this.pages = total;
    return total;
  }

  locate(i) {
    let c = 0;
    while (c + 1 < this.starts.length && this.starts[c + 1] <= i) c++;
    return [c, i - this.starts[c]];
  }

  /** A page as an element `width` CSS pixels wide. */
  element(i, width) {
    const [c, col] = this.locate(Math.max(0, Math.min(this.pages - 1, i)));
    const view = document.createElement("div");
    view.className = "tview";
    const k = width / PAGE_W;
    view.style.transform = `scale(${k})`;
    const flow = this.flow(c);
    flow.style.transform = `translateX(${-col * PAGE_W}px)`;
    view.append(flow);
    const wrap = document.createElement("div");
    wrap.className = "twrap";
    wrap.style.width = width + "px";
    wrap.style.height = Math.round(PAGE_H * k) + "px";
    wrap.append(view);
    return wrap;
  }

  close() {
    this.templates.clear();
  }
}

export async function openText(blob, kind, size) {
  const parsed = kind === "epub" ? await parseEpub(blob) : await parseText(blob);
  const book = new TextBook(parsed, kind);
  await book.layout(size || DEFAULT_TEXT_SIZE);
  return book;
}

export async function openBook(blob, kind, textSize) {
  return kind === "pdf" ? openPdf(blob) : openText(blob, kind, textSize);
}
