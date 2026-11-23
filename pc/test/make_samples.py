"""Makes small sample books in test/out for the smoke test: a PDF, an EPUB, a text file, a picture."""
import io
import os
import zipfile

import fitz
from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

PARA = ("It is a truth universally acknowledged, that a single man in possession of a good fortune, "
        "must be in want of a wife. However little known the feelings or views of such a man may be "
        "on his first entering a neighbourhood, this truth is so well fixed in the minds of the "
        "surrounding families, that he is considered the rightful property of some one or other of their daughters. ")

doc = fitz.open()
page = doc.new_page(width=420, height=595)
page.draw_rect(fitz.Rect(0, 0, 420, 595), color=(0.1, 0.25, 0.5), fill=(0.1, 0.25, 0.5))
page.insert_textbox(fitz.Rect(40, 200, 380, 400), "A Sample Book\n\nfor testing", fontsize=30, color=(1, 1, 1), align=1)
for n in range(1, 41):
    page = doc.new_page(width=420, height=595)
    page.insert_textbox(fitz.Rect(70, 80, 350, 520), f"Chapter page {n}\n\n" + PARA * 3, fontsize=10.5, align=3)
    page.insert_text((200, 560), str(n), fontsize=9)
doc.save(os.path.join(OUT, "Sample_Book_OceanofPDF.com.pdf"))

buf = io.BytesIO()
Image.new("RGB", (400, 600), (160, 40, 60)).save(buf, "JPEG")
with zipfile.ZipFile(os.path.join(OUT, "Pride-and-Testing.epub"), "w") as z:
    z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml",
               '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
               '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
               compress_type=zipfile.ZIP_DEFLATED)
    items = "".join(f'<item id="c{i}" href="text/ch{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 6))
    spine = "".join(f'<itemref idref="c{i}"/>' for i in range(1, 6))
    z.writestr("OEBPS/content.opf",
               '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">'
               '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Pride and Testing</dc:title></metadata>'
               f'<manifest><item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>{items}</manifest>'
               f'<spine>{spine}</spine></package>', compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("OEBPS/images/cover.jpg", buf.getvalue())
    for i in range(1, 6):
        body = f"<h2>Chapter {i}</h2>" + "".join(f"<p>{PARA}</p>" for _ in range(14)) + "<script>window.hacked = 1</script>"
        z.writestr(f"OEBPS/text/ch{i}.xhtml",
                   '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title>'
                   f'<style>p{{color:red}}</style></head><body>{body}</body></html>', compress_type=zipfile.ZIP_DEFLATED)

with open(os.path.join(OUT, "notes_file.txt"), "w", encoding="utf-8") as f:
    f.write("\n\n".join(PARA for _ in range(60)))
Image.new("RGB", (500, 760), (30, 120, 90)).save(os.path.join(OUT, "newcover.png"))
print("samples written to", OUT)
