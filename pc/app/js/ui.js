// Small view helpers shared by the home page and the reader: element building, dialogs, toasts.

/** Builds an element: h("div", {class: "x", onclick: fn}, child, "text"). */
export function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
      else if (k === "text") el.textContent = v;
      else if (k === "html") el.innerHTML = v;
      else if (k in el && k !== "list") el[k] = v;
      else el.setAttribute(k, v === true ? "" : v);
    }
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

let toastTimer = 0;

export function toast(message, ms = 3200) {
  let el = document.getElementById("toast");
  if (!el) {
    el = h("div", { id: "toast" });
    document.body.append(el);
  }
  el.textContent = message;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), ms);
}

const open = [];

/** True while a dialog is up; the reader ignores its keyboard shortcuts then. */
export function dialogOpen() {
  return open.length > 0;
}

/**
 * A modal dialog. `body` is a node or string; `buttons` are {label, value, primary, danger}.
 * Resolves with the chosen button's value, or undefined if it was dismissed.
 */
export function dialog({ title, body, buttons = [{ label: "Close" }], wide = false, onShow }) {
  return new Promise((resolve) => {
    const shade = h("div", { class: "shade" });
    const box = h("div", { class: "dialog" + (wide ? " wide" : ""), role: "dialog" });
    const close = (value) => {
      const i = open.indexOf(api);
      if (i >= 0) open.splice(i, 1);
      shade.remove();
      resolve(value);
    };
    const api = { close };
    if (title) box.append(h("div", { class: "dialog-title", text: title }));
    if (body) box.append(h("div", { class: "dialog-body" }, typeof body === "string" ? h("p", { text: body }) : body));
    if (buttons.length) {
      const row = h("div", { class: "dialog-buttons" });
      for (const b of buttons) {
        row.append(h("button", {
          class: "btn" + (b.primary ? " primary" : "") + (b.danger ? " danger" : ""),
          text: b.label,
          onclick: () => close(typeof b.value === "function" ? b.value() : b.value),
        }));
      }
      box.append(row);
    }
    shade.append(box);
    shade.addEventListener("mousedown", (e) => {
      if (e.target === shade) close(undefined);
    });
    shade.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "Escape") close(undefined);
      if (e.key === "Enter" && e.target.tagName !== "TEXTAREA" && e.target.tagName !== "BUTTON") {
        const primary = buttons.find((b) => b.primary);
        if (primary) close(typeof primary.value === "function" ? primary.value() : primary.value);
      }
    });
    document.body.append(shade);
    open.push(api);
    box.tabIndex = -1;
    box.focus();
    if (onShow) onShow(api, box);
  });
}

/** A list of choices. Resolves with the index picked, or -1. Items may be strings or {label, danger}. */
export function menu(title, items) {
  return new Promise((resolve) => {
    const list = h("div", { class: "menu-list" });
    let api = null;
    items.forEach((item, i) => {
      const label = typeof item === "string" ? item : item.label;
      list.append(h("button", {
        class: "menu-item" + (item.danger ? " danger" : ""),
        text: label,
        onclick: () => {
          api.close(i);
        },
      }));
    });
    dialog({ title, body: list, buttons: [], onShow: (a) => (api = a) }).then((v) => resolve(v === undefined ? -1 : v));
  });
}

/** A one-field dialog. Resolves with the trimmed text, or null if cancelled. */
export function ask(title, value = "", okLabel = "Save", { hint = "", multiline = false, type = "text" } = {}) {
  const input = multiline
    ? h("textarea", { class: "field", rows: 6, placeholder: hint })
    : h("input", { class: "field", type, placeholder: hint });
  input.value = value;
  return dialog({
    title,
    body: input,
    buttons: [{ label: "Cancel", value: null }, { label: okLabel, primary: true, value: () => input.value.trim() }],
    onShow: () => {
      input.focus();
      input.select();
    },
  }).then((v) => (v === undefined ? null : v));
}

export function confirm(title, message, okLabel = "OK", danger = false) {
  return dialog({
    title,
    body: message,
    buttons: [{ label: "Cancel", value: false }, { label: okLabel, primary: !danger, danger, value: true }],
  }).then((v) => v === true);
}

/** Opens the file chooser. Resolves with the files picked (possibly none). */
export function pickFiles(accept, multiple) {
  return new Promise((resolve) => {
    const input = h("input", { type: "file", accept, multiple, style: { display: "none" } });
    input.addEventListener("change", () => {
      resolve(Array.from(input.files || []));
      input.remove();
    });
    input.addEventListener("cancel", () => {
      resolve([]);
      input.remove();
    });
    document.body.append(input);
    input.click();
  });
}

export function minutes(seconds) {
  const min = Math.floor(seconds / 60);
  return min < 60 ? min + " min" : Math.floor(min / 60) + " h " + (min % 60) + " min";
}

export function hex(c) {
  return "#" + (c & 0xffffff).toString(16).padStart(6, "0");
}

export function blend(a, b, t) {
  const ch = (s) => Math.round(((a >> s) & 255) + (((b >> s) & 255) - ((a >> s) & 255)) * t);
  return (ch(16) << 16) | (ch(8) << 8) | ch(0);
}
