// Copies pdf.js out of node_modules into app/vendor, so the app folder is complete on its own.
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const from = path.join(root, "node_modules", "pdfjs-dist");
const to = path.join(root, "app", "vendor", "pdfjs");

if (!fs.existsSync(from)) {
  console.log("pdfjs-dist is not installed yet; run npm install");
  process.exit(0);
}
fs.rmSync(to, { recursive: true, force: true });
fs.mkdirSync(to, { recursive: true });
for (const f of ["pdf.min.mjs", "pdf.worker.min.mjs"]) {
  fs.copyFileSync(path.join(from, "build", f), path.join(to, f));
}
// Character maps and the standard fonts, for PDFs that do not embed their own.
for (const dir of ["cmaps", "standard_fonts"]) {
  fs.cpSync(path.join(from, dir), path.join(to, dir), { recursive: true });
}
console.log("pdf.js copied to app/vendor/pdfjs");
