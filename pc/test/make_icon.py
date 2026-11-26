"""Draws art/icon.svg to art/icon.png (for the installer) and app/icon.png (for the window)."""
import os

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
svg = open(os.path.join(ROOT, "art", "icon.svg"), encoding="utf-8").read()

with sync_playwright() as pw:
    try:
        browser = pw.chromium.launch()
    except Exception:
        browser = pw.chromium.launch(channel="msedge")
    page = browser.new_page(viewport={"width": 512, "height": 512})
    page.set_content(f"<style>html,body{{margin:0;background:transparent}}svg{{display:block}}</style>{svg}")
    for target in (os.path.join(ROOT, "art", "icon.png"), os.path.join(ROOT, "app", "icon.png")):
        page.screenshot(path=target, omit_background=True)
    browser.close()
print("icon written")
