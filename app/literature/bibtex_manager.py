import re
import io
from typing import List, Dict, Optional

import bibtexparser
from bibtexparser.bwriter import BibTexWriter
from bibtexparser.bibdatabase import BibDatabase

from app.database import Database


def _sanitize_key(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"[^a-zA-Z0-9]", "", text)
    return text


def _get_first_author_surname(authors_str: str) -> str:
    if not authors_str:
        return "anon"
    first = authors_str.split(",")[0].strip() if "," in authors_str else authors_str.split(" and ")[0].strip()
    if " " in first:
        parts = first.strip().split()
        surname = parts[-1]
    else:
        surname = first
    surname = _sanitize_key(surname)
    return surname.lower() if surname else "anon"


def _get_first_title_word(title: str) -> str:
    if not title:
        return ""
    words = re.findall(r"[a-zA-Z0-9]+", title)
    stop_words = {"a", "an", "the", "of", "in", "on", "for", "and", "or", "to", "with", "by", "from", "is", "are", "at"}
    for w in words:
        if w.lower() not in stop_words:
            return _sanitize_key(w).lower()
    return _sanitize_key(words[0]).lower() if words else ""


def _generate_bibtex_key(lit_dict: Dict) -> str:
    surname = _get_first_author_surname(lit_dict.get("authors", ""))
    year = str(lit_dict.get("year", "")) if lit_dict.get("year") else ""
    word = _get_first_title_word(lit_dict.get("title", ""))
    key_parts = [p for p in [surname, year, word] if p]
    key = "".join(key_parts) if key_parts else "ref"
    return key


def _escape_bibtex(value: str) -> str:
    if not value:
        return ""
    value = value.replace("\\", "\\\\")
    value = value.replace("{", "\\{").replace("}", "\\}")
    return value


def literature_to_bibtex(lit_dict: Dict) -> str:
    db = BibDatabase()
    key = lit_dict.get("bibtex_key") or _generate_bibtex_key(lit_dict)

    entry = {"ENTRYTYPE": "article", "ID": key}

    if lit_dict.get("title"):
        entry["title"] = f"{{{_escape_bibtex(lit_dict['title'])}}}"
    if lit_dict.get("authors"):
        entry["author"] = lit_dict["authors"]
    if lit_dict.get("journal"):
        entry["journal"] = lit_dict["journal"]
    if lit_dict.get("year"):
        entry["year"] = str(lit_dict["year"])
    if lit_dict.get("volume"):
        entry["volume"] = lit_dict["volume"]
    if lit_dict.get("issue"):
        entry["number"] = lit_dict["issue"]
    if lit_dict.get("pages"):
        entry["pages"] = lit_dict["pages"]
    if lit_dict.get("doi"):
        entry["doi"] = lit_dict["doi"]
    if lit_dict.get("abstract"):
        entry["abstract"] = _escape_bibtex(lit_dict["abstract"])

    db.entries = [entry]
    writer = BibTexWriter()
    writer.indent = "  "
    writer.comma_first = False
    return writer.write(db)


def parse_bibtex(bibtex_str: str) -> List[Dict]:
    if not bibtex_str or not bibtex_str.strip():
        return []
    try:
        parser = bibtexparser.bparser.BibTexParser(common_strings=True)
        parser.ignore_nonstandard_types = False
        db = bibtexparser.loads(bibtex_str, parser=parser)
        results = []
        for entry in db.entries:
            lit = {
                "bibtex_key": entry.get("ID"),
                "title": _strip_braces(entry.get("title", "")),
                "authors": entry.get("author"),
                "journal": entry.get("journal") or entry.get("booktitle"),
                "year": None,
                "volume": entry.get("volume"),
                "issue": entry.get("number") or entry.get("issue"),
                "pages": entry.get("pages"),
                "doi": entry.get("doi") or entry.get("DOI"),
                "abstract": _strip_braces(entry.get("abstract", "")),
            }
            year_raw = entry.get("year")
            if year_raw:
                try:
                    lit["year"] = int(re.search(r"\d{4}", str(year_raw)).group())
                except (ValueError, AttributeError):
                    pass
            results.append(lit)
        return results
    except Exception:
        return []


def _strip_braces(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"[{}]", "", text)
    return text.strip()


def export_all_bibtex(db: Database, output_path: str) -> bool:
    try:
        literature_list = db.list_literature()
        if not literature_list:
            return False
        bibtex_entries = []
        for lit in literature_list:
            bibtex_entries.append(literature_to_bibtex(lit))
        full_content = "\n".join(bibtex_entries)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(full_content)
        return True
    except Exception:
        return False
