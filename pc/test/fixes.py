"""
Checks for bugs that were found and fixed after 1.1.0: each one is a thing that used to go wrong.
Drives the app in a real browser against the local server (npm run serve).

    python test/make_samples.py && python test/fixes.py
"""
import io
import os
import sys
import zipfile

import fitz
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


def add(page, *names):
    with page.expect_file_chooser() as fc:
        page.click("text=+  Add books")
    fc.value.set_files([os.path.join(OUT, n) for n in names])


def toast(page):
    return page.evaluate("(document.getElementById('toast') || {}).textContent || ''")


def screens(page):
    return page.locator("#reader .stage > .screen").count()


def footer(page):
    return page.inner_text(".footer").replace("\n", " ")


# ---- sample files of the awkward kinds
doc = fitz.open()
for n in range(5):
    p = doc.new_page(width=420, height=595)
    p.insert_textbox(fitz.Rect(60, 80, 360, 500), f"Odd book, page {n + 1}\n\n" + "Some words to read. " * 40, fontsize=11)
doc.save(os.path.join(OUT, "Odd Book.pdf"))
with open(os.path.join(OUT, "One Liner.txt"), "w", encoding="utf-8") as f:
    f.write("Just one short line.")
with open(os.path.join(OUT, "holiday.jpg"), "wb") as f:
    f.write(bytes([0xFF, 0xD8, 0xFF, 0xE0, 0, 16]) + bytes(range(256)) * 20)
with zipfile.ZipFile(os.path.join(OUT, "report.docx"), "w") as z:
    z.writestr("[Content_Types].xml", "<Types/>")
    z.writestr("word/document.xml", "<w:document/>")
with open(os.path.join(OUT, "Unicode Notes.txt"), "wb") as f:
    f.write(b"\xff\xfe" + ("A note saved by Notepad as Unicode.\r\n\r\n" * 30).encode("utf-16-le"))

with sync_playwright() as pw:
    try:
        browser = pw.chromium.launch()
    except Exception:
        browser = pw.chromium.launch(channel="msedge")
    ctx = browser.new_context(viewport={"width": 1280, "height": 860}, bypass_csp=True)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    page.goto(URL)
    page.wait_for_selector(".home")

    # ---- files that are not books are refused, by name
    add(page, "holiday.jpg")
    page.wait_for_function("(document.getElementById('toast') || {}).textContent")
    check("a picture is refused", "holiday.jpg" in toast(page) and "isn't a book" in toast(page), toast(page))
    page.wait_for_function("!document.querySelector('.busy.show')")
    add(page, "report.docx")
    page.wait_for_function("document.getElementById('toast').textContent.includes('report.docx')", timeout=15000)
    check("a Word document is refused", "isn't a book" in toast(page), toast(page))
    page.wait_for_function("!document.querySelector('.busy.show')")
    check("neither was added", len(state(page)["books"]) == 0, [b["title"] for b in state(page)["books"]])
    errors.clear()  # the refusals are logged on purpose

    # ---- real books
    add(page, "Odd Book.pdf", "One Liner.txt", "Unicode Notes.txt", "Sample_Book_OceanofPDF.com.pdf", "Pride-and-Testing.epub")
    page.wait_for_function("document.querySelectorAll('.book').length === 5", timeout=60000)
    page.wait_for_function("!document.querySelector('.busy.show')")
    one = book(page, "One Liner")
    check("a one-page book is not finished before it is opened", one["pages"] == 1 and "Finished  0" in page.inner_text(".chips"),
          page.inner_text(".chips"))

    # ---- adding a book again says so
    add(page, "Odd Book.pdf")
    page.wait_for_function("document.getElementById('toast').textContent.includes('already')", timeout=15000)
    check("adding a book twice says it is already here", "Odd Book" in toast(page), toast(page))
    check("and does not add it again", len(state(page)["books"]) == 5)

    # ---- a text file saved as Unicode reads as text
    page.click(".book:has-text('Unicode Notes')")
    page.wait_for_selector(".guide")
    page.click(".guide")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(600)
    check("a UTF-16 text file is readable", "A note saved by Notepad" in page.inner_text("#reader .stage"), page.inner_text("#reader .stage")[:80])
    page.keyboard.press("Escape")
    page.wait_for_selector(".home .book")

    # ---- an odd number of pages, read to the end in a spread, is finished
    page.click(".book:has-text('Odd Book')")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(900)
    for _ in range(4):
        page.keyboard.press("ArrowRight")
        page.wait_for_timeout(700)
    check("last spread of a 5-page book", "Pages 4–5 of 5" in footer(page) and "100%" in footer(page), footer(page))
    page.keyboard.press("Escape")
    page.wait_for_selector(".home .book")
    check("an odd-length book read in a spread counts as finished", "Finished  1" in page.inner_text(".chips"), page.inner_text(".chips"))
    check("its tile says Finished", "Finished" in page.inner_text(".book:has-text('Odd Book')"))

    # ---- keyboard: a book opens with Enter
    page.focus(".book:has-text('Sample Book')")
    page.keyboard.press("Enter")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(1500)
    check("Enter on a book opens it", not page.evaluate("document.getElementById('reader').hidden"))
    box = page.locator(".stage").bounding_box()
    cx, cy = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2

    # ---- a resize in the middle of a page turn leaves exactly one page showing
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(900)
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(90)
    page.set_viewport_size({"width": 1180, "height": 800})
    page.wait_for_timeout(1500)
    check("one screen on stage after resizing mid-turn", screens(page) == 1, screens(page))
    check("no curl shadow left behind", page.evaluate("getComputedStyle(document.querySelector('.curl-shade')).display") == "none")
    page.set_viewport_size({"width": 1280, "height": 860})
    page.wait_for_timeout(900)
    before = footer(page)
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(900)
    check("pages still turn after that", footer(page) != before and screens(page) == 1, (before, footer(page)))

    # ---- End pressed during a turn lands on the last page
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(60)
    page.keyboard.press("End")
    page.wait_for_timeout(1800)
    check("End during a turn goes to the last page", "of 41" in footer(page) and "100%" in footer(page) and screens(page) == 1, footer(page))
    page.keyboard.press("Home")
    page.wait_for_timeout(900)
    check("Home goes to the first page", "Page 1 of 41" in footer(page), footer(page))
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(900)

    # ---- double-click zooms without flashing the settings
    page.mouse.dblclick(cx, cy)
    page.wait_for_timeout(500)
    check("double-click zooms", page.evaluate("document.querySelector('.stage').classList.contains('zoomed')"))
    check("and does not open the settings", not page.evaluate("document.getElementById('reader').classList.contains('menu')"))
    page.mouse.dblclick(box["x"] + box["width"] * 0.9, cy)
    page.wait_for_timeout(500)
    check("double-click at the edge zooms back out", not page.evaluate("document.querySelector('.stage').classList.contains('zoomed')"))
    page.mouse.click(cx, cy)
    page.wait_for_selector("#reader.menu", timeout=3000)
    check("a single click still opens the settings", True)

    # ---- with the page-turn animation off, a drag still turns the page
    page.click(".sheet .pill >> text=None")
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    before = footer(page)
    page.mouse.move(box["x"] + box["width"] * 0.6, cy)
    page.mouse.down()
    page.mouse.move(box["x"] + box["width"] * 0.3, cy, steps=5)
    page.mouse.up()
    page.wait_for_timeout(700)
    check("dragging turns the page with animation off", footer(page) != before, (before, footer(page)))
    page.keyboard.press("m")
    page.wait_for_selector("#reader.menu")
    page.click(".sheet .pill >> text=Curl")
    page.keyboard.press("Escape")

    # ---- in a spread the bookmark star answers for both pages
    page.wait_for_timeout(400)
    page.keyboard.press("b")
    page.wait_for_timeout(300)
    marks = book(page, "Sample Book")["bookmarks"]
    star = page.evaluate("document.querySelector('.star').textContent")
    page.keyboard.press("b")
    page.wait_for_timeout(300)
    check("B bookmarks, and B again removes it", len(marks) == 1 and star == "★" and book(page, "Sample Book")["bookmarks"] == [],
          (marks, star, footer(page), toast(page)))

    # ---- Ctrl+letter is left to the system
    page.keyboard.press("Control+b")
    check("Ctrl+B does not bookmark", book(page, "Sample Book")["bookmarks"] == [])

    # ---- Escape while a book is still opening goes back cleanly
    page.keyboard.press("Escape")
    page.wait_for_selector(".home .book")
    page.click(".book:has-text('Pride and Testing')")
    page.keyboard.press("Escape")
    page.wait_for_timeout(2500)
    check("Escape while opening returns to the library", page.evaluate("document.getElementById('reader').hidden")
          and not page.evaluate("document.getElementById('home').hidden"))
    check("the Opening notice is gone", not page.evaluate("document.querySelector('.loading').classList.contains('show')"))
    page.click(".book:has-text('Sample Book')")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(1200)
    check("the next book opened is the right one", "of 41" in footer(page), footer(page))
    page.keyboard.press("Escape")
    page.wait_for_selector(".home .book")

    # ---- changing the text size asks before removing highlights
    page.click(".book:has-text('Pride and Testing')")
    page.wait_for_function("document.querySelectorAll('#reader .screen .page').length >= 1")
    page.wait_for_timeout(1200)
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(900)
    page.keyboard.press("h")
    pg = page.locator("#reader .stage > .screen .page").first.bounding_box()
    page.mouse.move(pg["x"] + pg["width"] * 0.2, pg["y"] + pg["height"] * 0.3)
    page.mouse.down()
    page.mouse.move(pg["x"] + pg["width"] * 0.8, pg["y"] + pg["height"] * 0.36, steps=4)
    page.mouse.up()
    page.click(".markbar >> text=Done")
    check("highlight made", len(book(page, "Pride and Testing")["highlights"]) == 1)
    page.keyboard.press("m")
    page.wait_for_selector("#reader.menu")
    page.click(".sheet .pill >> text=A  +")
    page.wait_for_selector(".dialog")
    check("text size change warns about highlights", "highlights" in page.inner_text(".dialog"), page.inner_text(".dialog"))
    page.click(".dialog .btn >> text=Cancel")
    page.wait_for_timeout(300)
    check("cancelling keeps them", len(book(page, "Pride and Testing")["highlights"]) == 1)
    page.keyboard.press("Escape")
    page.keyboard.press("Escape")
    page.wait_for_selector(".home .book")

    # ---- renaming a shelf to the name it has is not an error; names differing only in case are one shelf
    page.click("text=+  New shelf")
    page.fill(".field", "Work")
    page.click(".btn.primary")
    page.wait_for_selector(".chip >> text=Work")
    page.click(".chip >> text=Work", button="right")
    page.click(".menu-item >> text=Rename shelf")
    page.click(".btn.primary")
    page.wait_for_timeout(400)
    check("renaming a shelf to its own name is quiet", "already a shelf" not in toast(page), toast(page))
    page.click("text=+  New shelf")
    page.fill(".field", "work")
    page.click(".btn.primary")
    page.wait_for_timeout(400)
    check("“work” is not a second shelf beside “Work”", state(page)["shelves"] == ["Work"], state(page)["shelves"])

    # ---- a dialog keeps the Tab key and closes with Escape wherever the focus is
    page.click(".book:has-text('One Liner')", button="right")
    page.wait_for_selector(".dialog")
    for _ in range(12):
        page.keyboard.press("Tab")
    check("Tab stays inside the dialog", page.evaluate("!!document.activeElement.closest('.dialog')"))
    page.evaluate("document.activeElement.blur()")
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    check("Escape closes a dialog that has lost the focus", page.locator(".dialog").count() == 0)

    check("no script errors", not errors, errors[:5])
    browser.close()

print()
if failures:
    print(len(failures), "FAILED:", ", ".join(failures))
    sys.exit(1)
print("All checks passed")
