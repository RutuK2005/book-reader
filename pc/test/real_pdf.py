"""Opens one real book in the app and saves screenshots of a few pages: python test/real_pdf.py <file> [name]"""
import os
import sys
import time

from playwright.sync_api import sync_playwright

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
path = sys.argv[1]
name = sys.argv[2] if len(sys.argv) > 2 else "real"

with sync_playwright() as pw:
    try:
        browser = pw.chromium.launch()
    except Exception:
        browser = pw.chromium.launch(channel="msedge")
    page = browser.new_context(viewport={"width": 1440, "height": 900}, bypass_csp=True).new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(os.environ.get("MBS_URL", "http://localhost:8377/"))
    page.wait_for_selector(".home")
    start = time.time()
    with page.expect_file_chooser() as fc:
        page.click("text=+  Add books")
    fc.value.set_files(path)
    page.wait_for_function("document.querySelectorAll('.book').length === 1", timeout=120000)
    print("imported in %.1f s" % (time.time() - start))
    page.wait_for_timeout(500)
    page.screenshot(path=os.path.join(OUT, name + "-home.png"))
    page.click(".book")
    page.wait_for_selector(".guide", timeout=60000)
    page.click(".guide")
    page.wait_for_timeout(2500)
    page.screenshot(path=os.path.join(OUT, name + "-p1.png"))
    for i in range(3):
        page.keyboard.press("ArrowRight")
        page.wait_for_timeout(900)
    page.wait_for_timeout(1500)
    page.screenshot(path=os.path.join(OUT, name + "-p2.png"))
    print("footer:", page.inner_text(".footer").replace("\n", " "))
    page.keyboard.press("+")
    page.keyboard.press("+")
    page.wait_for_timeout(1800)
    page.screenshot(path=os.path.join(OUT, name + "-zoom.png"))
    print("errors:", errors[:5])
    browser.close()
