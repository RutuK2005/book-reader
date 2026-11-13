# Book Reader

I wanted to read my PDF books on an old Galaxy Tab A the way you read on a Kindle, and nothing
on Android felt right, so I made this. It shows up on the tablet as **Folio**.

It is plain Java on Android's built-in `PdfRenderer`, with no libraries, so the APK is about 50 KB
and it runs fine on a tablet with 2 GB of RAM.

## Install

Download `Folio.apk` from the [Releases](../../releases) page, open it on your Android device and
allow installing from that source when asked. Needs Android 8 or newer. Then tap **Add books** and
pick your PDFs.

## Features

- **Library** with covers and reading progress. PDFs are copied into the app, so a book survives
  the original file being moved. "Open with Folio" from a file manager also adds the book.
- **Page turns**: paper curl that follows your finger, slide, or none. Tap or swipe the screen
  edges, or use the volume keys.
- **Themes**: White, Paper, Sepia, Green, Gray, Night, plus a warmth slider. Themes are applied
  when a page is drawn, so switching is instant.
- **Crop margins**: finds the text block and zooms it to fill the screen.
- **Two-page spread** in landscape, with the cover on its own.
- **Zoom**: pinch or double-tap the middle; the visible area is re-rendered sharp.
- Bookmarks, go-to-page, a page scrubber, remembered position per book.
- Footer with page number, percent, a "time left" estimate from your reading speed, clock and battery.
- In-app brightness, independent of the system setting.

## Battery

Nothing runs while a page is on screen: no timers, no animation, no background work. The next and
previous pages are rendered ahead of time, so a turn only composites bitmaps. The screen is kept
on for 10 minutes after the last touch, then the normal screen timeout applies.

The Tab A has an LCD, so dark themes do not save power on it; lowering brightness does.

## Build

Needs JDK 17 plus Android SDK `build-tools;34.0.0` and `platforms;android-34`. `build.ps1` looks
for them in `%USERPROFILE%\android-toolchain` (override with `FOLIO_TOOLCHAIN`).

```powershell
.\build.ps1                 # -> build\Folio.apk
.\build.ps1 -Install        # build and install on the connected device
```

The signing key is created on the first build as `folio-debug.keystore` beside the toolchain
(override with `FOLIO_KEYSTORE`), outside the repo so every checkout signs with the same key. Keep
it: Android only accepts an update signed with the same key, otherwise the app has to be
uninstalled first, which deletes the library.

`python tools/make_sample.py build/sample-book.pdf` writes a novel-shaped test PDF (needs reportlab).

## Layout

| File | Role |
|---|---|
| `PdfEngine.java` | Worker thread around `PdfRenderer`: screen rendering, zoom tiles, margin detection |
| `ReaderView.java` | The page surface: curl/slide drawing, gestures, zoom, footer |
| `ReaderActivity.java` | Reader screen and its settings panel |
| `LibraryActivity.java` | Bookshelf |
| `Library.java` | Book list, import, saved progress |
| `Themes.java` | Colour themes as draw-time colour filters |

## Limits

`PdfRenderer` exposes pixels only, not text: there is no text search, text reflow, or table of
contents, and password-protected PDFs cannot be opened.
