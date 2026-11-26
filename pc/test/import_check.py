"""Imports one library file into an empty library and prints what arrived: python test/import_check.py file.mbs"""
import json
import os
import sys

from playwright.sync_api import sync_playwright

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")

with sync_playwright() as pw:
    try:
        browser = pw.chromium.launch()
    except Exception:
        browser = pw.chromium.launch(channel="msedge")
    page = browser.new_context(viewport={"width": 1280, "height": 860}, bypass_csp=True).new_page()
    page.on("pageerror", lambda e: print("PAGE ERROR", e))
    page.goto(os.environ.get("MBS_URL", "http://localhost:8377/"))
    page.wait_for_selector(".home")
    with page.expect_file_chooser() as fc:
        page.click("text=+  Add books")
    fc.value.set_files(sys.argv[1])
    page.wait_for_selector("#toast.show", timeout=600000)
    print("message:", page.inner_text("#toast"))
    state = page.evaluate("""async () => {
        const r = indexedDB.open('mybookspace');
        const db = await new Promise(ok => r.onsuccess = () => ok(r.result));
        const g = db.transaction('kv').objectStore('kv').get('state');
        return await new Promise(ok => g.onsuccess = () => ok(g.result));
    }""")
    print("shelves:", state["shelves"])
    for b in state["books"]:
        marks = sum(len(v) for v in b["highlights"].values())
        print(f"  {b['title'][:44]:44} {b['kind']:4} p{b['page'] + 1}/{b['pages']}  shelf={b['shelf'] or '-'}  "
              f"bookmarks={len(b['bookmarks'])} notes={len(b['notes'])} highlights={marks} hidden={b['hidden']} ownCover={b['ownCover']}")
    page.wait_for_function("[...document.querySelectorAll('.book img')].every(i => i.complete && i.naturalWidth > 0)")
    page.wait_for_timeout(300)
    page.screenshot(path=os.path.join(OUT, "import-check.png"))
    browser.close()
