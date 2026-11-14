"""Generates a novel-shaped test PDF (6x9 in, cover, chapters, page numbers) for trying out MyBookSpace.

    python tools/make_sample.py build/sample-book.pdf
"""
import random
import sys

from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

PAGE = (6 * inch, 9 * inch)
WORDS = (
    "the of and a to in is you that it he was for on are as with his they I at be this have from or "
    "one had by word but not what all were we when your can said there use an each which she do how "
    "their if will up other about out many then them these so some her would make like him into time "
    "has look two more write go see number no way could people my than first water been call who oil "
    "its now find long down day did get come made may part river lantern harbour winter letter garden "
    "window quiet morning road stone answer silence candle paper shadow promise story evening"
).split()


def paragraph(rng, sentences):
    out = []
    for _ in range(sentences):
        n = rng.randint(7, 22)
        s = " ".join(rng.choice(WORDS) for _ in range(n))
        out.append(s[0].upper() + s[1:] + ".")
    return " ".join(out)


def cover(canvas, doc):
    w, h = PAGE
    canvas.setFillColor(HexColor("#2F4858"))
    canvas.rect(0, 0, w, h, stroke=0, fill=1)
    canvas.setFillColor(HexColor("#F4ECD8"))
    canvas.setFont("Times-Bold", 34)
    canvas.drawCentredString(w / 2, h * 0.62, "The Quiet Harbour")
    canvas.setFont("Times-Italic", 15)
    canvas.drawCentredString(w / 2, h * 0.55, "A sample book for MyBookSpace")
    canvas.setFont("Times-Roman", 13)
    canvas.drawCentredString(w / 2, h * 0.12, "Test Edition")


def numbered(canvas, doc):
    canvas.setFont("Times-Roman", 9)
    canvas.setFillColor(HexColor("#444444"))
    canvas.drawCentredString(PAGE[0] / 2, 0.55 * inch, str(doc.page))


def main(path):
    rng = random.Random(7)
    body = ParagraphStyle("body", fontName="Times-Roman", fontSize=11, leading=15.5,
                          alignment=TA_JUSTIFY, firstLineIndent=16)
    head = ParagraphStyle("head", fontName="Times-Bold", fontSize=20, leading=26,
                          alignment=TA_CENTER, spaceAfter=22)
    kicker = ParagraphStyle("kicker", fontName="Times-Italic", fontSize=11, alignment=TA_CENTER, spaceAfter=6)
    story = [PageBreak()]
    for ch in range(1, 9):
        story += [Spacer(1, 1.1 * inch), Paragraph("Chapter %d" % ch, kicker),
                  Paragraph(paragraph(rng, 1)[:28].rstrip(" .").title(), head)]
        for _ in range(rng.randint(14, 22)):
            story.append(Paragraph(paragraph(rng, rng.randint(3, 7)), body))
        story.append(PageBreak())
    doc = SimpleDocTemplate(path, pagesize=PAGE, leftMargin=0.95 * inch, rightMargin=0.95 * inch,
                            topMargin=0.95 * inch, bottomMargin=0.95 * inch, title="The Quiet Harbour")
    doc.build(story, onFirstPage=cover, onLaterPages=numbered)
    print("wrote", path)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sample-book.pdf")
