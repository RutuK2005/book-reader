# Graph Report - reader-polish  (2026-11-15)

## Corpus Check
- Corpus is ~25,812 words - fits in a single context window. You may not need a graph.

## Summary
- 501 nodes · 1402 edges · 15 communities (6 shown, 9 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 153 edges (avg confidence: 0.8)
- Token cost: 72,947 input · 0 output

## Community Hubs (Navigation)
- Page Rendering and Gestures
- Reader Screen and Menu
- Library Data and Books
- Confetti and Small Views
- Reader Features (README)
- EPUB and Text Conversion
- Page Sound and Sample Tool
- Themes and Curl Shading
- Library Features (README)
- Cover Slot Layout

## God Nodes (most connected - your core abstractions)
1. `ReaderActivity` - 91 edges
2. `ReaderView` - 77 edges
3. `LibraryActivity` - 59 edges
4. `Book` - 39 edges
5. `Library` - 36 edges
6. `PdfEngine` - 26 edges
7. `MyBookSpace (Android book reader app)` - 17 edges
8. `Convert` - 14 edges
9. `Layout` - 11 edges
10. `Reading features` - 11 edges

## Surprising Connections (you probably didn't know these)
- `ReaderActivity` --references--> `Library`  [EXTRACTED]
  src/app/folio/reader/ReaderActivity.java → src/app/folio/reader/Library.java
- `ReaderActivity` --references--> `Book`  [EXTRACTED]
  src/app/folio/reader/ReaderActivity.java → src/app/folio/reader/Library.java
- `ReaderView` --references--> `Book`  [EXTRACTED]
  src/app/folio/reader/ReaderView.java → src/app/folio/reader/Library.java
- `ReaderActivity` --references--> `PageSound`  [EXTRACTED]
  src/app/folio/reader/ReaderActivity.java → src/app/folio/reader/PageSound.java
- `ReaderActivity` --references--> `PdfEngine`  [EXTRACTED]
  src/app/folio/reader/ReaderActivity.java → src/app/folio/reader/PdfEngine.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Preserving the installed library across updates** — readme_signing_key, readme_package_name_app_folio_reader, readme_books_copied_into_app [INFERRED 0.75]
- **Design for an old low-end LCD tablet** — readme_galaxy_tab_a, readme_no_libraries_plain_java, readme_battery_design, readme_extreme_battery_saver, readme_pdfrenderer [INFERRED 0.85]
- **App source files listed in the Code table** — src_app_folio_reader_pdfengine, src_app_folio_reader_readerview, src_app_folio_reader_readeractivity, src_app_folio_reader_libraryactivity, src_app_folio_reader_library, src_app_folio_reader_themes [EXTRACTED 1.00]

## Communities (15 total, 9 thin omitted)

### Community 0 - "Page Rendering and Gestures"
Cohesion: 0.05
Nodes (10): Callback, CropResult, Layout, OpenResult, PdfEngine, ThumbResult, Listener, ReaderView (+2 more)

### Community 2 - "Library Data and Books"
Cohesion: 0.08
Nodes (3): Book, Library, LibraryActivity

### Community 3 - "Confetti and Small Views"
Cohesion: 0.08
Nodes (6): ConfettiView, CoverView, ProgressLine, Ring, P, Icon

### Community 6 - "Reader Features (README)"
Cohesion: 0.11
Nodes (18): Toolchain (JDK 17, build-tools 34.0.0, android-34, FOLIO_TOOLCHAIN), Auto-turn and sleep timer, Bookmarks, page notes, go to page, page slider, build.ps1 build script, Crop margins, two-page spread, zoom, Dictionary lookup (needs a connection), Extreme battery saver, Footer (page number, time left, clock, battery) (+10 more)

### Community 8 - "Page Sound and Sample Tool"
Cohesion: 0.13
Nodes (5): PageSound, cover(), main(), numbered(), paragraph()

### Community 12 - "Library Features (README)"
Cohesion: 0.43
Nodes (6): PDF, EPUB and TXT support (EPUB/TXT paginated on add), Drag to rearrange, file to shelf, reorder shelf tabs, Library features (covers, progress, shelves, search, stats), Merge two versions of a book into one entry, Reading time, daily goal and streak, Shelves (custom plus auto Reading/Finished)

## Knowledge Gaps
- **6 isolated node(s):** `Rutuparn K. (designer and author)`, `Install from Releases (MyBookSpace.apk, Android 8+)`, `Bookmarks, page notes, go to page, page slider`, `Dictionary lookup (needs a connection)`, `Auto-turn and sleep timer` (+1 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 75 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ReaderActivity` connect `Reader Screen and Menu` to `Page Rendering and Gestures`, `Library Data and Books`, `Confetti and Small Views`, `Activity and Dialog Plumbing`, `Page Sound and Sample Tool`, `PDF Engine Worker`, `Cover Slot Layout`?**
  _High betweenness centrality (0.282) - this node is a cross-community bridge._
- **Why does `ReaderView` connect `Page Rendering and Gestures` to `Reader Screen and Menu`, `Library Data and Books`, `Confetti and Small Views`, `Turn Animation Internals`, `Themes and Curl Shading`, `PDF Engine Worker`?**
  _High betweenness centrality (0.256) - this node is a cross-community bridge._
- **Why does `LibraryActivity` connect `Library Data and Books` to `Page Rendering and Gestures`, `Reader Screen and Menu`, `Confetti and Small Views`, `Activity and Dialog Plumbing`, `Cover Slot Layout`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **What connects `Rutuparn K. (designer and author)`, `Install from Releases (MyBookSpace.apk, Android 8+)`, `Bookmarks, page notes, go to page, page slider` to the rest of the system?**
  _6 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Page Rendering and Gestures` be split into smaller, more focused modules?**
  _Cohesion score 0.052464525765496636 - nodes in this community are weakly interconnected._
- **Should `Reader Screen and Menu` be split into smaller, more focused modules?**
  _Cohesion score 0.06741573033707865 - nodes in this community are weakly interconnected._
- **Should `Library Data and Books` be split into smaller, more focused modules?**
  _Cohesion score 0.08206361935311414 - nodes in this community are weakly interconnected._