"""
Smoke test: drives the app in a real browser against the local server (npm run serve) and checks
the main paths: adding books, the home page, the reader, turning, bookmarks, notes, highlights,
themes, text size, covers. Screenshots land in test/out.

    python test/make_samples.py && python test/smoke.py
"""
import os
import sys

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
URL = os.environ.get("MBS_URL", "http://localhost:8377/")
failures = []
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -- " + str(detail)) if detail and not ok else ""))
    if not ok:
        failures.append(name)


def shot(page, name):
    page.screenshot(path=os.path.join(OUT, name + ".png"))


def state(page):
    return page.evaluate("""async () => {
        const r = indexedDB.open('mybookspace');
        const db = await new Promise(ok => r.onsuccess = () => ok(r.result));
        const g = db.transaction('kv').objectStore('kv').get('state');
        return await new Promise(ok => g.onsuccess = () => ok(g.result));
    }""")


def book(page, title):
    for b in state(page)["books"]:
        if b["title"] == title:
            return b
    return None


with sync_playwright() as pw:
    try:
        browser = pw.chromium.launch()
    except Exception:
        # No downloaded test browser: use the Edge that comes with Windows.
        browser = pw.chromium.launch(channel="msedge")
    # The page's own security policy forbids evaluated strings, which is how these checks are run.
    ctx = browser.new_context(viewport={"width": 1280, "height": 860}, bypass_csp=True)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    page.goto(URL)
    page.wait_for_selector(".home")
    check("empty library message", "Your library is empty" in page.inner_text("#home"))
    shot(page, "01-empty")

    # ---- add books
    with page.expect_file_chooser() as fc:
        page.click("text=+  Add books")
    fc.value.set_files([os.path.join(OUT, n) for n in ("Sample_Book_OceanofPDF.com.pdf", "Pride-and-Testing.epub", "notes_file.txt")])
    page.wait_for_function("document.querySelectorAll('.book').length === 3", timeout=60000)
    page.wait_for_function("[...document.querySelectorAll('.book img')].every(i => i.complete && i.naturalWidth > 0)")
    titles = page.eval_on_selector_all(".book-name", "els => els.map(e => e.textContent)")
    check("three books with tidy titles", sorted(titles) == ["Pride and Testing", "Sample Book", "notes file"], titles)
    pdf = book(page, "Sample Book")
    check("pdf page count", pdf and pdf["pages"] == 41, pdf and pdf["pages"])
    epub = book(page, "Pride and Testing")
    check("epub laid out into pages", epub and epub["pages"] > 10, epub and epub["pages"])
    shot(page, "02-home")

    # ---- expanded summary, colour scheme
    page.click(".summary")
    page.wait_for_selector(".stats")
    page.click(".colours")
    page.click(".scheme >> text=Midnight")
    check("colour scheme applied", page.evaluate("getComputedStyle(document.querySelector('#home')).backgroundColor") == "rgb(15, 20, 32)")
    shot(page, "03-home-midnight")

    # ---- shelves
    page.click("text=+  New shelf")
    page.fill(".field", "Work")
    page.click(".btn.primary")
    page.wait_for_selector(".chip >> text=Work")
    page.click(".book:has-text('Sample Book')", button="right")
    page.click(".menu-item >> text=Move to shelf")
    page.click(".menu-item >> text=Work")
    check("book filed on shelf", book(page, "Sample Book")["shelf"] == "Work")

    # ---- change cover
    page.click(".book:has-text('Sample Book')", button="right")
    page.click(".menu-item >> text=Change cover")
    with page.expect_file_chooser() as fc:
        page.click(".menu-item >> text=Choose a picture")
    fc.value.set_files(os.path.join(OUT, "newcover.png"))
    for _ in range(50):
        if book(page, "Sample Book")["ownCover"]:
            break
        page.wait_for_timeout(100)
    check("own cover set", book(page, "Sample Book")["ownCover"] is True)
    page.wait_for_timeout(400)
    shot(page, "04-own-cover")

    # ---- open the PDF
    page.click(".book:has-text('Sample Book')")
    page.wait_for_selector(".guide")
    shot(page, "05-guide")
    page.click(".guide")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(1200)
    check("first screen is the cover alone", page.locator("#reader .stage > .screen .page").count() == 1)
    shot(page, "06-reader-cover")
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(900)
    check("two-page spread in a wide window", page.locator("#reader .stage > .screen .page").count() == 2)
    check("footer shows the spread", "Pages 2–3 of 41" in page.inner_text(".footer"), page.inner_text(".footer"))
    shot(page, "07-reader-spread")
    check("margins were cropped", book(page, "Sample Book")["crop"] is not None and book(page, "Sample Book")["crop"]["odd"] is not None,
          book(page, "Sample Book")["crop"])

    # ---- click zones and drag
    box = page.locator(".stage").bounding_box()
    page.mouse.click(box["x"] + box["width"] * 0.9, box["y"] + box["height"] / 2)
    page.wait_for_timeout(800)
    check("clicking the right edge turns the page", "Pages 4–5" in page.inner_text(".footer"), page.inner_text(".footer"))
    page.mouse.move(box["x"] + box["width"] * 0.8, box["y"] + 300)
    page.mouse.down()
    page.mouse.move(box["x"] + box["width"] * 0.55, box["y"] + 300, steps=6)
    shot(page, "08-curl-midway")
    page.mouse.move(box["x"] + box["width"] * 0.2, box["y"] + 300, steps=6)
    page.mouse.up()
    page.wait_for_timeout(800)
    check("dragging turns the page", "Pages 6–7" in page.inner_text(".footer"), page.inner_text(".footer"))
    check("one screen left on stage after a turn", page.locator("#reader .stage > .screen").count() == 1)
    page.mouse.wheel(0, -200)
    page.wait_for_timeout(500)
    check("wheel turns back", "Pages 4–5" in page.inner_text(".footer"), page.inner_text(".footer"))

    # ---- bookmark, note, highlight
    page.keyboard.press("b")
    check("bookmark saved", book(page, "Sample Book")["bookmarks"] == [3], book(page, "Sample Book")["bookmarks"])
    page.keyboard.press("n")
    page.fill("textarea.field", "A thought worth keeping")
    page.click(".btn.primary")
    check("note saved", book(page, "Sample Book")["notes"].get("3") == "A thought worth keeping")
    page.keyboard.press("h")
    pg = page.locator("#reader .stage > .screen .page").first.bounding_box()
    page.mouse.move(pg["x"] + pg["width"] * 0.2, pg["y"] + pg["height"] * 0.3)
    page.mouse.down()
    page.mouse.move(pg["x"] + pg["width"] * 0.8, pg["y"] + pg["height"] * 0.36, steps=4)
    page.mouse.up()
    hl = book(page, "Sample Book")["highlights"]
    check("highlight saved", len(hl.get("3", [])) == 1, hl)
    shot(page, "09-highlight")
    page.click(".markbar >> text=Done")

    # ---- menu, themes, slider
    page.mouse.click(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.wait_for_selector("#reader.menu")
    page.wait_for_timeout(350)
    shot(page, "10-menu")
    page.click(".swatch[title='Night theme']")
    page.wait_for_timeout(300)
    check("night theme", page.evaluate("getComputedStyle(document.querySelector('#reader')).getPropertyValue('--paper').trim()") == "#000000")
    shot(page, "11-night")
    page.click(".swatch[title='Sepia theme']")
    page.click(".tab >> text=Layout")
    shot(page, "12-menu-layout")
    page.click(".switch:has-text('Two pages')")
    page.wait_for_timeout(900)
    check("single page when the spread is off", page.locator("#reader .stage > .screen .page").count() == 1)
    page.click(".tab >> text=Tools")
    shot(page, "13-menu-tools")
    page.click(".tab >> text=More")
    shot(page, "14-menu-more")
    page.click(".tab >> text=Display")
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    shot(page, "15-single-sepia")
    # ---- bookmarks list, slider preview, slide turn, auto-turn, dictionary
    page.keyboard.press("m")
    page.wait_for_selector("#reader.menu")
    page.click(".topbar .icon[title='Bookmarks and notes']")
    listing = page.inner_text(".dialog")
    check("bookmarks list shows the page and its note", "Page 4" in listing and "A thought worth keeping" in listing, listing)
    page.click(".btn >> text=Close")
    page.evaluate("""() => { const s = document.querySelector('.sheet .seek'); s.value = 20; s.dispatchEvent(new Event('input')); }""")
    page.wait_for_selector(".thumb.show canvas", timeout=15000)
    check("slider shows a page preview", "Page 21 of 41" in page.inner_text(".page-label"), page.inner_text(".page-label"))
    shot(page, "15a-slider-preview")
    page.evaluate("""() => { const s = document.querySelector('.sheet .seek'); s.value = 3; s.dispatchEvent(new Event('change')); }""")
    page.click(".sheet .pill >> text=Slide")
    page.click(".tab >> text=More")
    page.click(".sheet .pill >> text=Auto-turn")
    check("auto-turn cycles", "every 15 s" in page.inner_text(".panel.on"), page.inner_text(".panel.on"))
    page.click(".sheet .pill >> text=Auto-turn: every 15 s")
    for _ in range(5):
        page.click(".sheet .pill >> text=Auto-turn")
    check("auto-turn back to off", "Auto-turn: off" in page.inner_text(".panel.on"), page.inner_text(".panel.on"))
    page.click(".tab >> text=Display")
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    before = page.inner_text(".footer")
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(150)
    shot(page, "15b-slide-midway")
    page.wait_for_timeout(700)
    check("slide turn moves on", page.inner_text(".footer") != before and page.locator("#reader .stage > .screen").count() == 1,
          (before, page.inner_text(".footer")))
    page.keyboard.press("d")
    page.fill(".dialog .field", "book")
    page.click(".dialog .btn.primary")
    page.wait_for_function("!document.querySelector('.dict').textContent.includes('Looking up')", timeout=20000)
    answer = page.inner_text(".dict")
    check("dictionary answers (or says it is offline)", "noun" in answer or "couldn't be reached" in answer, answer[:120])
    print("      dictionary said:", answer[:70].replace("\n", " | "))
    page.click(".btn >> text=Close")

    page.keyboard.press("g")
    page.fill(".field", "30")
    page.click(".btn.primary")
    page.wait_for_timeout(700)
    check("go to page", "Page 30 of 41" in page.inner_text(".footer"), page.inner_text(".footer"))
    page.keyboard.press("Control+=") if False else None
    page.keyboard.press("+")
    page.wait_for_timeout(900)
    shot(page, "16-zoomed")
    page.keyboard.press("0")
    page.keyboard.press("End")
    page.wait_for_timeout(700)
    page.keyboard.press("Escape")
    page.wait_for_selector(".home", state="visible")
    b = book(page, "Sample Book")
    check("progress saved on close", b["page"] == 40, b["page"])
    check("finished book counted", "Finished  1" in page.inner_text(".chips"), page.inner_text(".chips"))

    # ---- EPUB
    page.click(".chip >> text=All")
    page.click(".book:has-text('Pride and Testing')")
    page.wait_for_function("document.querySelectorAll('#reader .screen .tflow').length >= 1", timeout=30000)
    page.wait_for_timeout(600)
    shot(page, "17-epub-cover")
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(800)
    shot(page, "18-epub-text")
    check("epub text shown", "truth universally acknowledged" in page.inner_text("#reader .stage"))
    check("epub scripts never run", page.evaluate("window.hacked === undefined"))
    before = book(page, "Pride and Testing")["pages"]
    page.keyboard.press("m")
    page.wait_for_selector("#reader.menu")
    page.click("text=A  +")
    page.wait_for_function("!document.querySelector('.loading.show')")
    page.wait_for_timeout(600)
    after = book(page, "Pride and Testing")["pages"]
    check("bigger text makes more pages", after > before, (before, after))
    shot(page, "19-epub-bigger")
    page.keyboard.press("Escape")
    page.keyboard.press("Escape")
    page.wait_for_selector(".home", state="visible")

    # ---- export the library to one file, and bring it into an empty library
    page.click(".colours:has-text('Move your library')")
    page.click(".menu-item >> text=Export books to a file")
    check("export lists the shelves", "Work" in page.inner_text(".dialog") and "Books not on a shelf" in page.inner_text(".dialog"),
          page.inner_text(".dialog"))
    shot(page, "19a-export-dialog")
    with page.expect_download(timeout=60000) as dl:
        page.click(".btn.primary >> text=Export")
    library_file = os.path.join(OUT, "exported.mbs")
    dl.value.save_as(library_file)
    check("library file name", dl.value.suggested_filename.startswith("MyBookSpace-library-") and dl.value.suggested_filename.endswith(".mbs"),
          dl.value.suggested_filename)
    import json
    import zipfile
    with zipfile.ZipFile(library_file) as z:
        names = z.namelist()
        manifest = json.loads(z.read("manifest.json"))
        check("library file is a sound zip, manifest first", z.testzip() is None and names[0] == "manifest.json", names[:3])
        check("library file holds 3 books and 3 covers", sum(n.startswith("books/") for n in names) == 3
              and sum(n.startswith("covers/") for n in names) == 3, names)
        check("manifest describes the books", manifest["format"] == "mybookspace-library" and len(manifest["books"]) == 3, manifest.get("format"))

    other = browser.new_context(viewport={"width": 1280, "height": 860}, bypass_csp=True).new_page()
    other.on("pageerror", lambda e: errors.append("import: " + str(e)))
    other.goto(URL)
    other.wait_for_selector(".home")
    with other.expect_file_chooser() as fc:
        other.click("text=+  Add books")
    fc.value.set_files(library_file)
    other.wait_for_function("document.querySelectorAll('.book').length === 3", timeout=60000)
    moved = book(other, "Sample Book")
    check("imported: place, bookmark, note, highlight", moved["page"] == 40 and moved["bookmarks"] == [3]
          and moved["notes"].get("3") == "A thought worth keeping" and len(moved["highlights"].get("3", [])) == 1, moved)
    check("imported: shelf and own cover", moved["shelf"] == "Work" and moved["ownCover"] is True and "Work" in state(other)["shelves"],
          (moved["shelf"], moved["ownCover"]))
    check("imported: epub keeps its text size and place", book(other, "Pride and Testing")["pages"] == book(page, "Pride and Testing")["pages"]
          and book(other, "Pride and Testing")["page"] == book(page, "Pride and Testing")["page"])
    other.wait_for_function("[...document.querySelectorAll('.book img')].every(i => i.complete && i.naturalWidth > 0)")
    shot(other, "19b-imported")
    # Importing the same file again must not duplicate anything.
    with other.expect_file_chooser() as fc:
        other.click("text=+  Add books")
    fc.value.set_files(library_file)
    other.wait_for_selector("#toast.show >> text=already here")
    check("importing twice adds nothing", len(state(other)["books"]) == 3, len(state(other)["books"]))

    # A file as the Android app writes it: streamed, so sizes follow each entry, and a text book
    # whose page numbers mean something different there.
    android_file = os.path.join(OUT, "from-android.mbs")

    class Stream:
        def __init__(self, f):
            self.f = f

        def write(self, b):
            return self.f.write(b)

        def flush(self):
            self.f.flush()

    amanifest = {"format": "mybookspace-library", "version": 1, "app": "android", "shelves": ["Tablet shelf"],
                 "daily": {"2026-11-20": 900}, "goalMin": 30, "books": [{
                     "id": "andr1", "title": "From The Tablet", "src": "tablet-book.txt", "shelf": "Tablet shelf", "kind": "txt",
                     "file": "books/andr1.txt", "pages": 100, "page": 50, "opened": 5, "added": 4,
                     "size": os.path.getsize(os.path.join(OUT, "notes_file.txt")) + 7, "bookmarks": [10, 50], "notes": {"50": "halfway"},
                     "highlights": {"50": [[0.1, 0.1, 0.5, 0.2, 2]]}, "textSize": 12.5, "partner": "", "hidden": False}]}
    with open(android_file, "wb") as raw:
        with zipfile.ZipFile(Stream(raw), "w", zipfile.ZIP_DEFLATED, compresslevel=0) as z:
            z.writestr("manifest.json", json.dumps(amanifest))
            z.writestr("books/andr1.txt", open(os.path.join(OUT, "notes_file.txt"), "rb").read() + b" tablet")
    with other.expect_file_chooser() as fc:
        other.click("text=+  Add books")
    fc.value.set_files(android_file)
    other.wait_for_function("document.querySelectorAll('.book').length === 4", timeout=60000)
    tb = book(other, "From The Tablet")
    half = tb["pages"] // 2
    check("android file: text book lands at the same point", tb["kind"] == "txt" and abs(tb["page"] - half) <= 1
          and tb["notes"].get(str(tb["page"])) == "halfway" and tb["highlights"] == {} and tb["textSize"] == 19, tb)
    check("android file: shelf and reading log", "Tablet shelf" in state(other)["shelves"] and state(other)["daily"].get("2026-11-20") == 900)
    other.context.close()

    # ---- text file
    page.click(".book:has-text('notes file')")
    page.wait_for_function("document.querySelectorAll('#reader .screen .tflow').length >= 1", timeout=30000)
    page.wait_for_timeout(500)
    check("text file opens", "truth universally acknowledged" in page.inner_text("#reader .stage"))
    shot(page, "20-text")
    page.keyboard.press("Escape")
    page.wait_for_selector(".home", state="visible")

    # ---- search, covers only, details, remove
    page.click("text=Search")
    page.fill(".search", "pride")
    check("search narrows the list", page.locator(".book").count() == 1)
    page.click("text=Close search")
    page.click(".book:has-text('notes file')", button="right")
    page.click(".menu-item >> text=Details")
    check("details dialog", "Text  ·" in page.inner_text(".dialog"), page.inner_text(".dialog"))
    page.click(".btn >> text=Close")
    page.click(".book:has-text('notes file')", button="right")
    page.click(".menu-item >> text=Remove from library")
    page.click(".btn.danger")
    page.wait_for_function("document.querySelectorAll('.book').length === 2")
    check("book removed", book(page, "notes file") is None)

    # ---- merge two versions by dragging one book onto the other, then separate them
    page.locator(".book:has-text('Pride and Testing')").drag_to(page.locator(".book:has-text('Sample Book')"))
    page.click(".btn >> text=Merge")
    page.wait_for_function("document.querySelectorAll('.book').length === 1")
    check("merged into one entry", book(page, "Sample Book")["partner"] != "" and book(page, "Pride and Testing")["hidden"] is True)
    page.click(".book", button="right")
    page.click(".menu-item >> text=Separate the two versions")
    page.wait_for_function("document.querySelectorAll('.book').length === 2")
    check("separated again", book(page, "Sample Book")["partner"] == "")

    # ---- the library survives a restart
    page.reload()
    page.wait_for_function("document.querySelectorAll('.book').length === 2")
    check("library kept after reload", True)

    # ---- phone-width window
    page.set_viewport_size({"width": 430, "height": 820})
    page.wait_for_timeout(300)
    shot(page, "21-narrow-home")
    page.set_viewport_size({"width": 1280, "height": 860})
    shot(page, "22-home-final")

    real = [e for e in errors if "favicon" not in e]
    check("no script errors", not real, real[:5])
    browser.close()

print()
print("FAILED: " + ", ".join(failures) if failures else "All checks passed")
sys.exit(1 if failures else 0)
