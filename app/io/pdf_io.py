import tempfile
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import QUrl, Qt
from PyQt6.QtGui import QTextDocument, QPageLayout, QPageSize, QFont, QColor, QPalette
from PyQt6.QtPrintSupport import QPrinter

from app.database import Database
from app.config import Config
from app.io.html_io import export_note_to_html


PDF_CSS = """
body {
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
    font-size: 12pt;
    line-height: 1.7;
    color: #1a1a1a;
}
h1 { font-size: 22pt; border-bottom: 1px solid #ccc; padding-bottom: 6px; }
h2 { font-size: 18pt; border-bottom: 1px solid #eee; padding-bottom: 4px; }
h3 { font-size: 15pt; }
h4 { font-size: 13pt; }
p { margin: 8px 0; }
code {
    background: #f0f0f0;
    padding: 1px 4px;
    border-radius: 3px;
    font-family: "SF Mono", Menlo, Consolas, monospace;
    font-size: 10pt;
}
pre {
    background: #f5f5f5;
    padding: 10px;
    border-radius: 5px;
    white-space: pre-wrap;
    word-wrap: break-word;
}
pre code { background: transparent; padding: 0; }
blockquote {
    border-left: 3px solid #ccc;
    margin: 10px 0;
    padding: 4px 12px;
    color: #555;
    background: #fafafa;
}
img { max-width: 100%; height: auto; }
table { border-collapse: collapse; width: 100%; margin: 10px 0; }
th, td { border: 1px solid #ccc; padding: 6px 10px; }
th { background: #f0f0f0; font-weight: bold; }
a { color: #1967d2; }
.note-title { font-size: 26pt; font-weight: bold; margin-bottom: 8px; }
.note-meta { font-size: 10pt; color: #666; margin-bottom: 20px; }
.note-header { border-bottom: 2px solid #ddd; padding-bottom: 12px; margin-bottom: 20px; }
.tag {
    display: inline-block;
    background: #e8f0fe;
    color: #1967d2;
    padding: 1px 8px;
    border-radius: 10px;
    font-size: 9pt;
    margin-right: 4px;
}
.note-tags { margin-top: 6px; }
"""


def export_note_to_pdf(db: Database, note_id: int, output_path: str) -> bool:
    note = db.get_note(note_id)
    if not note:
        return False

    try:
        tmp_dir = Path(tempfile.mkdtemp())
        tmp_html = tmp_dir / "note_export.html"

        ok = export_note_to_html(db, note_id, str(tmp_html), standalone=True)
        if not ok or not tmp_html.exists():
            return False

        html_content = tmp_html.read_text(encoding="utf-8")

        css_injection_pos = html_content.find("</head>")
        if css_injection_pos > 0:
            html_content = html_content[:css_injection_pos] + f"<style>{PDF_CSS}</style>" + html_content[css_injection_pos:]
        else:
            html_content = f"<head><style>{PDF_CSS}</style></head>" + html_content

        doc = QTextDocument()
        doc.setHtml(html_content)
        doc.setDefaultFont(QFont("PingFang SC", 12))

        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
        printer.setOutputFileName(output_path)

        page_layout = QPageLayout()
        page_layout.setPageSize(QPageSize(QPageSize.PageSizeId.A4))
        page_layout.setOrientation(QPageLayout.Orientation.Portrait)
        page_layout.setMargins(QPageLayout.Margins(20, 20, 20, 20, QPageLayout.Unit.Millimeter))
        printer.setPageLayout(page_layout)

        printer.setColorMode(QPrinter.ColorMode.Color)
        printer.setFullPage(False)

        doc.print(printer)

        try:
            tmp_html.unlink()
            tmp_dir.rmdir()
        except Exception:
            pass

        return Path(output_path).exists()

    except Exception:
        return False
