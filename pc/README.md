# MyBookSpace for PC

The Windows version of MyBookSpace: the same library and reader as the Android app, built for a
mouse, a keyboard and a wide window.

## Install

Download one of these from the [Releases](../../../releases) page:

- `MyBookSpace-Setup-1.0.0.exe` installs it with a Start menu shortcut and adds MyBookSpace to
  "Open with" for PDF and EPUB files.
- `MyBookSpace-Portable-1.0.0.exe` runs without installing.

Windows may show a "Windows protected your PC" notice because the app is not code-signed; choose
**More info**, then **Run anyway**. Needs Windows 10 or 11 (64-bit).

Then click **Add books**, or drop PDF, EPUB or TXT files onto the window.

## What it does

**Library**
- Covers, reading progress, and your own shelves. "Reading" and "Finished" fill themselves in.
- Drag a book to rearrange it, onto a shelf's tab to file it, or onto another book to merge two
  versions (say a PDF and an EPUB) into one entry. Shelf tabs can be dragged into any order.
- Right-click a book to rename it, move it, see its details, or change its cover to any picture:
  from a file, pasted from the clipboard, or found with a web search.
- Search by title, covers-only view, 28 home page colour schemes.
- Today's reading time, a daily goal and a streak, folded away until you click them.
- Books are copied into the app, so a book survives the original file being moved.

**Reading**
- Page turns: a paper curl that follows the mouse, a slide, or none. Click or drag the edges, use
  the arrow keys or the mouse wheel, or hold an edge to riffle through pages.
- Two-page spread in a wide window, with the cover on its own.
- Themes: White, Paper, Sepia, Green, Gray, Night, with a warmth slider and a brightness slider.
  Night can switch itself on from 9 pm to 6 am.
- Crop margins so the text fills the window; Ctrl + scroll or double-click to zoom, drag to pan.
- EPUB and text books are laid out into pages, and their text size can be changed.
- Reading ruler, highlights in four colours, bookmarks, page notes (copy them or save them as a
  text file), go to page, and a page slider with a preview.
- Dictionary (needs a connection), auto-turn, a sleep timer, a soft page-turn sound, full screen.
- Footer with page number, time left in the book, clock and battery.

**Shortcuts**

| Key | Does |
| --- | --- |
| → ↓ Space PgDn, or scroll down | Next page |
| ← ↑ Shift+Space PgUp, or scroll up | Previous page |
| Home / End | First / last page |
| G, B, N | Go to a page, bookmark, note |
| H, R, D | Highlight, reading ruler, dictionary |
| M or Enter | Show or hide the settings |
| Ctrl + scroll, + − 0 | Zoom in, out, reset |
| F or F11 | Full screen |
| Esc | Back |
| Ctrl+O (on the home page) | Add books |

## How it is built

The app in `app/` is an ordinary web page in plain JavaScript, with no framework. PDFs are drawn
by [pdf.js](https://mozilla.github.io/pdf.js/); EPUB files are unzipped and laid out by the app's
own code. `main.js` is a small [Electron](https://www.electronjs.org/) shell that gives the page a
window, a fixed address to keep its library under, and the files it is opened with.

The library (books, covers, progress, settings) is stored on the computer under
`%APPDATA%\MyBookSpace` and nothing is sent anywhere. The only network requests are dictionary
look-ups, and the web search for a cover, which opens in your browser.

```
app/        the reader itself (index.html, style.css, js/)
main.js     the desktop window; preload.js is its bridge to the page
scripts/    copies pdf.js into app/vendor, and a local server for development
test/       an automated check that drives the app in a browser
```

## Build

Needs Node.js 20 or newer.

```powershell
cd pc
npm install          # also copies pdf.js into app/vendor
npm start            # run the app
npm run dist         # build dist\MyBookSpace-Setup-1.0.0.exe and the portable exe
```

If `npm run dist` stops with "Cannot create symbolic link" while unpacking `winCodeSign`, either
turn on Developer Mode in Windows settings, or unpack that archive once by hand without its Mac
files:

```powershell
$c = "$env:LOCALAPPDATA\electron-builder\Cache\winCodeSign"
$z = (Get-ChildItem $c -Filter *.7z | Select-Object -First 1).FullName
.\node_modules\7zip-bin\win\x64\7za.exe x -y $z "-o$c\winCodeSign-2.6.0" "-x!darwin"
```

## Test

```powershell
npm run serve                      # in one terminal
python test\make_samples.py        # sample books (needs PyMuPDF and Pillow)
python test\smoke.py               # needs Playwright for Python; uses Edge if no test browser
```

The check adds a PDF, an EPUB and a text file, then exercises shelves, covers, page turns,
bookmarks, notes, highlights, themes, text size, merging and search, and saves screenshots to
`test/out`.
