// Serves the app folder on http://localhost:8377 so it can be opened in a browser while developing.
const http = require("http");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..", "app");
const types = {
  ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".png": "image/png", ".svg": "image/svg+xml", ".json": "application/json",
};
const port = Number(process.env.PORT || 8377);
http.createServer((req, res) => {
  const rel = decodeURIComponent(req.url.split("?")[0]);
  const file = path.normalize(path.join(root, rel === "/" ? "index.html" : rel));
  if (!file.startsWith(root)) {
    res.writeHead(403).end();
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404).end("Not found");
      return;
    }
    res.writeHead(200, { "Content-Type": types[path.extname(file)] || "application/octet-stream", "Cache-Control": "no-store" });
    res.end(data);
  });
}).listen(port, "127.0.0.1", () => console.log("MyBookSpace on http://localhost:" + port));
