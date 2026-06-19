import re
from pathlib import Path
from typing import Optional, Dict

import fitz


def extract_text(pdf_path: str) -> str:
    doc = fitz.open(pdf_path)
    text_parts = []
    for page in doc:
        text_parts.append(page.get_text())
    doc.close()
    return "\n".join(text_parts)


def extract_metadata(pdf_path: str) -> Dict:
    result = {
        "title": None,
        "authors": None,
        "doi": None,
        "abstract": None,
    }
    try:
        doc = fitz.open(pdf_path)
    except Exception:
        return result

    try:
        meta = doc.metadata or {}
        if meta.get("title") and meta["title"].strip():
            result["title"] = meta["title"].strip()
        if meta.get("author") and meta["author"].strip():
            result["authors"] = meta["author"].strip()
    except Exception:
        pass

    first_page_text = ""
    try:
        if len(doc) > 0:
            first_page_text = doc[0].get_text()
    except Exception:
        pass

    if not result.get("title") and first_page_text:
        lines = [l.strip() for l in first_page_text.splitlines() if l.strip()]
        if lines:
            title_candidates = [l for l in lines if len(l) >= 5 and len(l) <= 200]
            if title_candidates:
                result["title"] = title_candidates[0]

    if not result.get("authors") and first_page_text:
        author_patterns = [
            r"(?:by|authors?)\s*:\s*([^\n\r]+)",
            r"^([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*(?:,\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)*)$",
        ]
        for pat in author_patterns:
            m = re.search(pat, first_page_text, re.IGNORECASE | re.MULTILINE)
            if m:
                authors_raw = m.group(1).strip()
                if len(authors_raw) <= 300:
                    result["authors"] = authors_raw
                    break

    full_text = first_page_text + "\n"
    try:
        for i in range(1, min(3, len(doc))):
            full_text += doc[i].get_text() + "\n"
    except Exception:
        pass

    doi_pattern = r"10\.\d{4,9}/[-._;()/:A-Z0-9]+"
    m = re.search(doi_pattern, full_text, re.IGNORECASE)
    if m:
        doi = m.group(0).rstrip(".,;")
        result["doi"] = doi

    abstract_patterns = [
        r"(?:Abstract|ABSTRACT)\s*[:\-]?\s*\n?\s*([\s\S]{50,2000}?)(?:\n\s*(?:Keywords|KEYWORDS|Index Terms|INTRODUCTION|Introduction|1\s*\.?\s*Introduction)\b|$)",
        r"(?:Abstract|ABSTRACT)\s*[:\-]?\s*([\s\S]{50,2000})",
    ]
    for pat in abstract_patterns:
        m = re.search(pat, full_text, re.IGNORECASE)
        if m:
            abstract = m.group(1).strip()
            abstract = re.sub(r"\s+", " ", abstract)
            if len(abstract) >= 50:
                result["abstract"] = abstract
                break

    doc.close()
    return result


def extract_first_page_image(pdf_path: str, output_path: str) -> bool:
    try:
        doc = fitz.open(pdf_path)
        if len(doc) == 0:
            doc.close()
            return False
        page = doc[0]
        zoom = 2.0
        mat = fitz.Matrix(zoom, zoom)
        pix = page.get_pixmap(matrix=mat)
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        if output_path.lower().endswith(".png"):
            pix.save(output_path, "png")
        elif output_path.lower().endswith(".jpg") or output_path.lower().endswith(".jpeg"):
            pix.save(output_path, "jpeg")
        else:
            pix.save(output_path, "png")
        doc.close()
        return True
    except Exception:
        return False
