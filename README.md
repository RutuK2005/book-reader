# MyBookSpace

**Designed and built by Rutuparn K.**

I wanted to read my PDF books on an old Galaxy Tab A the way you read on a Kindle, and nothing
on Android felt right, so I made this.

It is plain Java on Android's built-in `PdfRenderer`, with no libraries, so the APK is about 75 KB
and it runs fine on a tablet with 2 GB of RAM.

## Install

Download `MyBookSpace.apk` from the [Releases](../../releases) page, open it on your Android device
and allow installing from that source when asked. Works on Android phones and tablets running
Android 8 or newer. Then tap **Add books** and pick your PDFs.

**On a PC:** there is a Windows version too, with the same library and reader. Download
`MyBookSpace-Setup-1.1.0.exe` from the same Releases page; see [pc/README.md](pc/README.md).

## What it does

**Library**
- Covers, reading progress, and your own shelves (Work, Study, anything). "Reading" and "Finished"
  fill themselves in.
- Hold a book and drag it to rearrange, onto a shelf's tab to file it, or onto another book to
  merge two versions (say a PDF and an EPUB) into one entry. Shelf tabs can be dragged into any order.
- Search by title, and a details page per book with time spent reading.
- Change a book's cover to any picture on the device, with a shortcut to search the web for one.
- Today's reading time, a daily goal and a streak, folded away until you tap them.
- Opens PDF, EPUB and TXT. EPUB and text books are laid out into pages when added, and their
  text size can be changed afterwards.
- Books are copied into the app, so a book survives the original file being moved.
- Move your library to another device: export the shelves you choose into one `.mbs` file, and
  import it in MyBookSpace on a phone, tablet or PC. See [Moving your library](#moving-your-library).

**Reading**
- Page turns: a paper curl that follows your finger, a slide, or none. Tap or swipe the edges, use
  the volume keys, or hold an edge to riffle through pages.
- Themes: White, Paper, Sepia, Green, Gray, Night, with a warmth slider and in-app brightness.
  Night can switch itself on from 9 pm to 6 am.
- Crop margins so the text fills the screen; two-page spread in landscape on tablets; pinch or double-tap to zoom.
- Reading ruler: dims the page except for a band you drag along, at a height you choose.
- Highlights in four colours: drag over a passage to mark it; undo, or tap one to remove it.
- Bookmarks and page notes, go to page, and a page slider with a preview.
- Dictionary: type a word to look it up (needs a connection).
- Auto-turn for hands-free reading, and a sleep timer.
- Footer with page number, time left in the book, clock and battery.

## Moving your library

A library can be carried from one device to another (tablet to PC, phone to tablet, PC to phone)
as a single file.

1. On the device that has the library, open the home page, tap the line with today's reading
   time to unfold it, and choose **Move your library → Export books to a file**.
2. Tick the shelves you want, and choose where to save the file. It is named
   `MyBookSpace-library-<date>.mbs`.
3. Get that file onto the other device any way you like: USB cable, Google Drive, Bluetooth.
4. There, choose **Move your library → Import a library file** and pick it. On a PC you can also
   double-click the file, or drop it onto the MyBookSpace window.

The file holds the books themselves, their covers, your place in each book, your bookmarks, notes
and highlights, your shelves, and your reading log. A book that is already on the second device
is not copied twice; it takes the more recent reading position and keeps both sets of notes, so
the same file can be imported again safely.

EPUB and text books are laid out into pages separately on each device, so their page numbers
differ. Your place, bookmarks and notes move to the same point in the book; highlights in those
books cannot be carried over (highlights in PDFs are).

**Battery**
- Nothing runs while a page is on screen: no timers, no animation, no background work.
- Extreme battery saver: no animation or sound, dim screen, normal screen timeout.
- The tablet this was made for has an LCD, so dark themes do not save power on it; lower
  brightness does.

## Build

Needs JDK 17 plus Android SDK `build-tools;34.0.0` and `platforms;android-34`. `build.ps1` looks
for them in `%USERPROFILE%\android-toolchain` (override with `FOLIO_TOOLCHAIN`).

```powershell
.\build.ps1                 # -> build\MyBookSpace.apk
.\build.ps1 -Install        # build and install on the connected device
```

The signing key is created on the first build as `folio-debug.keystore` beside the toolchain
(override with `FOLIO_KEYSTORE`). Keep it: Android only accepts an update signed with the same key,
otherwise the app has to be uninstalled first, which deletes the library.

## Code

| File | Role |
|---|---|
| `PdfEngine.java` | Worker thread around `PdfRenderer`: page rendering, zoom, margin detection |
| `ReaderView.java` | The page: curl and slide drawing, gestures, zoom, ruler, highlights |
| `ReaderActivity.java` | Reader screen and its settings panel |
| `LibraryActivity.java` | Home: shelves, stats, book grid |
| `Library.java` | Books, shelves, saved progress, reading log |
| `Themes.java` | Colour themes as draw-time colour filters |

The app's internal package name is still `app.folio.reader`, from its first name, so that updates
install over older versions.

## Limits

`PdfRenderer` gives pixels, not text, so there is no text search, text reflow or table of
contents; highlights mark an area rather than words; and password-protected PDFs cannot be opened.
