from pathlib import Path
from typing import Optional, List
from datetime import datetime

from lxml import etree

from app.database import Database
from app.config import Config


def _build_folder_outline(db: Database, folder_id: Optional[int]) -> List[etree._Element]:
    outlines = []

    notes = db.list_notes(folder_id=folder_id, limit=10000)
    for note in notes:
        note_el = etree.Element("outline")
        note_el.set("text", note["title"])
        note_el.set("type", "note")
        note_el.set("noteId", str(note["id"]))
        note_el.set("created", datetime.fromtimestamp(note["created_at"]).strftime("%Y-%m-%dT%H:%M:%S"))
        note_el.set("updated", datetime.fromtimestamp(note["updated_at"]).strftime("%Y-%m-%dT%H:%M:%S"))
        tags = db.get_note_tags(note["id"])
        if tags:
            note_el.set("tags", ",".join(t["name"] for t in tags))
        outlines.append(note_el)

    subfolders = db.list_folders(parent_id=folder_id)
    for folder in subfolders:
        folder_el = etree.Element("outline")
        folder_el.set("text", folder["name"])
        folder_el.set("type", "folder")
        folder_el.set("folderId", str(folder["id"]))
        for child in _build_folder_outline(db, folder["id"]):
            folder_el.append(child)
        outlines.append(folder_el)

    return outlines


def export_opml(db: Database, output_path: str) -> bool:
    try:
        root = etree.Element("opml")
        root.set("version", "2.0")

        head = etree.SubElement(root, "head")
        title_el = etree.SubElement(head, "title")
        title_el.text = "KnowledgeVault Notes Export"

        date_created = etree.SubElement(head, "dateCreated")
        date_created.text = datetime.now().strftime("%a, %d %b %Y %H:%M:%S +0000")

        body = etree.SubElement(root, "body")

        for child in _build_folder_outline(db, None):
            body.append(child)

        tree = etree.ElementTree(root)
        out = Path(output_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        tree.write(
            str(out),
            pretty_print=True,
            xml_declaration=True,
            encoding="UTF-8",
        )
        return True
    except Exception:
        return False


def _parse_outline_recursive(
    db: Database,
    config: Config,
    outlines: List[etree._Element],
    parent_folder_id: Optional[int],
    created_note_ids: List[int],
):
    for outline in outlines:
        text = outline.get("text", "") or outline.get("title", "")
        otype = outline.get("type", "").lower()

        if otype == "note" or (not otype and outline.get("noteId")):
            title = text or "Untitled"
            note_id = db.create_note(title=title, folder_id=parent_folder_id, content="", markdown_content="")
            created_note_ids.append(note_id)

            tags_str = outline.get("tags", "")
            if tags_str:
                for t in tags_str.split(","):
                    tn = t.strip()
                    if tn:
                        tid = db.get_or_create_tag(tn)
                        db.add_tag_to_note(note_id, tid)

        elif otype == "folder" or (not otype and len(list(outline)) > 0):
            folder_name = text or "New Folder"
            new_folder_id = db.create_folder(folder_name, parent_id=parent_folder_id)
            _parse_outline_recursive(db, config, list(outline), new_folder_id, created_note_ids)

        else:
            if len(list(outline)) > 0:
                folder_name = text or "New Folder"
                new_folder_id = db.create_folder(folder_name, parent_id=parent_folder_id)
                _parse_outline_recursive(db, config, list(outline), new_folder_id, created_note_ids)
            else:
                title = text or "Untitled"
                note_id = db.create_note(title=title, folder_id=parent_folder_id, content="", markdown_content="")
                created_note_ids.append(note_id)


def import_opml(db: Database, config: Config, opml_path: str, parent_folder_id: Optional[int] = None) -> list:
    opml_file = Path(opml_path)
    if not opml_file.exists():
        raise FileNotFoundError(f"OPML file not found: {opml_path}")

    created_note_ids: List[int] = []

    try:
        parser = etree.XMLParser(recover=True, encoding="utf-8")
        tree = etree.parse(str(opml_file), parser)
        root = tree.getroot()

        body = None
        if root.tag.lower() == "opml":
            body = root.find("body")
        elif root.tag.lower() == "body":
            body = root

        if body is None:
            body = root

        top_outlines = body.findall("outline")
        if not top_outlines:
            top_outlines = list(body)

        _parse_outline_recursive(db, config, top_outlines, parent_folder_id, created_note_ids)

    except Exception:
        raise

    return created_note_ids
